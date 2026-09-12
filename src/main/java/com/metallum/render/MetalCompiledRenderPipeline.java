package com.metallum.render;

import com.metallum.nativebridge.NativeMetalDevice;
import com.metallum.nativebridge.NativePipelineDescriptor;
import com.metallum.mtl.*;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

@Environment(EnvType.CLIENT)
final class MetalCompiledRenderPipeline implements CompiledRenderPipeline, AutoCloseable {
    enum ResourceKind {
        UNIFORM_BUFFER,
        SAMPLED_IMAGE,
        TEXEL_BUFFER
    }

    static final int STAGE_VERTEX = 1;
    static final int STAGE_FRAGMENT = 2;
    static final int STAGE_ALL = STAGE_VERTEX | STAGE_FRAGMENT;

    record ResourceBinding(ResourceKind kind, String name, int bindingIndex, int stageMask,
                           @Nullable GpuFormat texelBufferFormat) {
    }

    private final List<ResourceBinding> resources;
    private final Map<String, ResourceBinding> resourcesByName;
    private final long allResourceMask;
    private final int firstAvailableVertexBufferSlot;
    private final MTLCullMode cullMode;
    private final MTLTriangleFillMode fillMode;
    private final float depthBiasScaleFactor;
    private final float depthBiasConstant;
    private final MTLPrimitiveType topology;
    private final int vertexBufferCount;

    private NativeMetalDevice.Resource withDepthOwner, withoutDepthOwner;
    private boolean closed;
    private final NativeMetalDevice.Resource depthStencilState;
    private final NativeMetalDevice.Resource withDepthPipeline;
    private final NativeMetalDevice.Resource withoutDepthPipeline;

    MetalCompiledRenderPipeline(
            final MetalDevice device,
            final RenderPipeline info,
            final String vertexMsl,
            final String fragmentMsl,
            final String vertexEntryPoint,
            final String fragmentEntryPoint,
            final List<ResourceBinding> resources
    ) {
        this.resources = resources;
        this.resourcesByName = resources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ResourceBinding::name, binding -> binding));

        int maxBindingIndex = -1;
        long resourceMask = 0L;
        for (ResourceBinding binding : resources) {
            maxBindingIndex = Math.max(maxBindingIndex, binding.bindingIndex());
            resourceMask |= 1L << binding.bindingIndex();
        }
        if (maxBindingIndex >= Long.SIZE) {
            throw new IllegalStateException("Pipeline " + info.getLocation() + " has binding index " + maxBindingIndex + ", limit is " + (Long.SIZE - 1));
        }
        this.allResourceMask = resourceMask;

        this.firstAvailableVertexBufferSlot = firstAvailableVertexBufferSlot(resources);
        this.cullMode = info.isCull() ? MTLCullMode.Back : MTLCullMode.None;
        this.fillMode = info.getPolygonMode() == PolygonMode.WIREFRAME ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill;
        this.topology = MTLPrimitiveType.from(info.getPrimitiveTopology());
        this.vertexBufferCount = info.getVertexFormatBindings().length;

        MTLCompareFunction depthCompareOp;
        int depthWrite;
        var depthStencilState = info.getDepthStencilState();
        if (depthStencilState == null) {
            depthCompareOp = MTLCompareFunction.Always;
            depthWrite = 0;
            this.depthBiasScaleFactor = 0.0f;
            this.depthBiasConstant = 0.0f;
        } else {
            depthCompareOp = MTLCompareFunction.from(depthStencilState.depthTest());
            depthWrite = depthStencilState.writeDepth() ? 1 : 0;
            this.depthBiasScaleFactor = depthStencilState.depthBiasScaleFactor();
            this.depthBiasConstant = depthStencilState.depthBiasConstant();
        }

        this.depthStencilState = device.depthStencilState(depthCompareOp, depthWrite != 0);

        var colorTarget = info.getColorTargetState();
        MTLPixelFormat colorFormat = colorTarget != null ? MTLPixelFormat.from(colorTarget.format()) : MTLPixelFormat.RGBA8Unorm;

        MTLFunction vertexFunction = device.getOrCompileFunction(vertexMsl, vertexEntryPoint);
        MTLFunction fragmentFunction = device.getOrCompileFunction(fragmentMsl, fragmentEntryPoint);

        this.withDepthOwner = device.nativeOwner().createPipeline(vertexFunction.nativeResource(), fragmentFunction.nativeResource(),
                nativeDescriptor(info, this.firstAvailableVertexBufferSlot, colorFormat, MTLPixelFormat.Depth32Float));
        try {
            this.withoutDepthOwner = device.nativeOwner().createPipeline(vertexFunction.nativeResource(), fragmentFunction.nativeResource(),
                    nativeDescriptor(info, this.firstAvailableVertexBufferSlot, colorFormat, MTLPixelFormat.Invalid));
        } catch (Throwable failure) {
            this.withDepthOwner.close();
            throw failure;
        }
        this.withDepthPipeline = this.withDepthOwner;
        this.withoutDepthPipeline = this.withoutDepthOwner;
    }

    private static NativePipelineDescriptor nativeDescriptor(RenderPipeline info, int firstSlot, MTLPixelFormat color, MTLPixelFormat depth) {
        var target = info.getColorTargetState();
        long mask = target == null ? MTLColorWriteMask.All.value : MTLColorWriteMask.from(target.writeMask());
        var descriptor = new NativePipelineDescriptor(color.value, depth.value, MTLPixelFormat.Invalid.value, mask);
        if (target != null && target.blendFunction().isPresent()) {
            var blend = target.blendFunction().get();
            descriptor.blend(MTLBlendFactor.from(blend.color().sourceFactor()).value, MTLBlendFactor.from(blend.color().destFactor()).value,
                    MTLBlendOperation.from(blend.color().op()).value, MTLBlendFactor.from(blend.alpha().sourceFactor()).value,
                    MTLBlendFactor.from(blend.alpha().destFactor()).value, MTLBlendOperation.from(blend.alpha().op()).value);
        }
        int attributeIndex = 0;
        VertexFormat[] bindings = info.getVertexFormatBindings();
        for (int i = 0; i < bindings.length; i++) {
            var binding = bindings[i];
            if (binding == null || binding.getElements().isEmpty()) continue;
            long rate = binding.getStepRate();
            descriptor.layout(firstSlot + i, binding.getVertexSize(),
                    rate > 0 ? MTLVertexStepFunction.PerInstance.value : MTLVertexStepFunction.PerVertex.value, rate > 0 ? rate : 1);
            for (var element : binding.getElements()) {
                var format = MTLVertexFormat.from(element.format());
                if (format == MTLVertexFormat.Invalid) throw new IllegalStateException("Unsupported vertex attribute format: " + element.format());
                descriptor.attribute(attributeIndex++, format.value, element.offset(), firstSlot + i);
            }
        }
        return descriptor;
    }

    @Override
    public boolean isValid() {
        return !(this.withDepthPipeline == null);
    }

    List<ResourceBinding> resources() {
        return this.resources;
    }

    long allResourceMask() {
        return this.allResourceMask;
    }

    @Nullable
    ResourceBinding resource(final String name) {
        return this.resourcesByName.get(name);
    }

    int firstAvailableVertexBufferSlot() {
        return this.firstAvailableVertexBufferSlot;
    }

    float depthBiasScaleFactor() {
        return this.depthBiasScaleFactor;
    }

    float depthBiasConstant() {
        return this.depthBiasConstant;
    }

    NativeMetalDevice.Resource getDepthStencilState() {
        return this.depthStencilState;
    }

    NativeMetalDevice.Resource getNativePipeline(final boolean useDepth) {
        return useDepth ? this.withDepthPipeline : this.withoutDepthPipeline;
    }

    MTLCullMode cullMode() {
        return this.cullMode;
    }

    MTLTriangleFillMode fillMode() {
        return this.fillMode;
    }

    MTLPrimitiveType topology() {
        return this.topology;
    }

    int vertexBufferCount() {
        return this.vertexBufferCount;
    }

    private static int firstAvailableVertexBufferSlot(final List<ResourceBinding> resources) {
        int maxVertexBufferBinding = -1;
        for (ResourceBinding resource : resources) {
            if (resource.kind() == ResourceKind.UNIFORM_BUFFER && (resource.stageMask() & STAGE_VERTEX) != 0) {
                maxVertexBufferBinding = Math.max(maxVertexBufferBinding, resource.bindingIndex());
            }
        }
        return maxVertexBufferBinding + 1;
    }

    @Override
    public void close() {
        if (closed) return;
        withDepthOwner.close();
        withoutDepthOwner.close();
        closed = true;
    }
}
