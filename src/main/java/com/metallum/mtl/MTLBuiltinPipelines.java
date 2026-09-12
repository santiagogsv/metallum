package com.metallum.mtl;

import com.metallum.objc.AutoreleasePool;
import com.metallum.nativebridge.NativeMetalDevice;
import com.metallum.nativebridge.NativePipelineDescriptor;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.ArrayDeque;
import java.util.Map;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

@Environment(EnvType.CLIENT)
public final class MTLBuiltinPipelines {
    private static final String PRESENT_MSL = """
            #include <metal_stdlib>
            using namespace metal;
            
            struct PresentVertexOut {
              float4 position [[position]];
              float2 uv;
            };
            
            vertex PresentVertexOut metallum_present_vs(uint vertexId [[vertex_id]]) {
              const float2 positions[3] = {
                float2(-1.0,  1.0),
                float2( 3.0,  1.0),
                float2(-1.0, -3.0)
              };
            
              // Y-flip version:
              // old equivalent was uvMin=(0,1), uvMax=(1,0)
              const float2 uvs[3] = {
                float2(0.0,  1.0),
                float2(2.0,  1.0),
                float2(0.0, -1.0)
              };
            
              PresentVertexOut out;
              out.position = float4(positions[vertexId], 0.0, 1.0);
              out.uv = uvs[vertexId];
              return out;
            }
            
            fragment float4 metallum_present_fs(
              PresentVertexOut in [[stage_in]],
              texture2d<float> tex [[texture(0)]],
              sampler smp [[sampler(0)]]
            ) {
              return tex.sample(smp, in.uv);
            }
            """;

    private static final String CLEAR_MSL = """
            #include <metal_stdlib>
            using namespace metal;
            
            struct ClearUniforms {
              float z;
              float3 _padding0;
              float4 color;
            };
            
            struct ClearVertexOut {
              float4 position [[position]];
              float4 color;
            };
            
            vertex ClearVertexOut metallum_clear_vs(
              uint vertexId [[vertex_id]],
              constant ClearUniforms& u [[buffer(1)]]
            ) {
              const float2 positions[3] = {
                float2(-1.0,  1.0),
                float2( 3.0,  1.0),
                float2(-1.0, -3.0)
              };
            
              ClearVertexOut out;
              out.position = float4(positions[vertexId], u.z, 1.0);
              out.color = u.color;
              return out;
            }
            
            fragment float4 metallum_clear_fs(ClearVertexOut in [[stage_in]]) {
              return in.color;
            }
            """;

    private static MTLDevice device;
    private static final Map<Long, ArrayDeque<NativeMetalDevice.Resource>> nativeResources = new HashMap<>();
    private static MemorySegment presentPipeline = MemorySegment.NULL;
    private static MemorySegment presentLinearSampler = MemorySegment.NULL;
    private static MemorySegment presentNearestSampler = MemorySegment.NULL;
    private static final Map<Long, MemorySegment> clearPipelines = new HashMap<>();
    private static final Map<Long, MemorySegment> depthStencilStates = new HashMap<>();

    private MTLBuiltinPipelines() {
    }

    public static void init(final MTLDevice mtlDevice) {
        device = mtlDevice;
        presentPipeline = buildPipeline(PRESENT_MSL, "metallum_present_vs", "metallum_present_fs",
                MTLPixelFormat.BGRA8Unorm.value, MTLPixelFormat.Invalid.value, MTLColorWriteMask.All.value);
        presentLinearSampler = buildPresentSampler(MTLSamplerMinMagFilter.Linear);
        presentNearestSampler = buildPresentSampler(MTLSamplerMinMagFilter.Nearest);
        ensureClearPipeline(MTLPixelFormat.BGRA8Unorm.value, MTLPixelFormat.Depth32Float.value, true);
        ensureClearPipeline(MTLPixelFormat.RGBA8Unorm.value, MTLPixelFormat.Depth32Float.value, true);
        ensureClearPipeline(MTLPixelFormat.BGRA8Unorm.value, MTLPixelFormat.Invalid.value, true);
    }

    public static void close() {
        if (!ObjC.isNil(presentPipeline)) {
            releaseResource(presentPipeline);
            presentPipeline = MemorySegment.NULL;
        }
        if (!ObjC.isNil(presentLinearSampler)) {
            releaseResource(presentLinearSampler);
            presentLinearSampler = MemorySegment.NULL;
        }
        if (!ObjC.isNil(presentNearestSampler)) {
            releaseResource(presentNearestSampler);
            presentNearestSampler = MemorySegment.NULL;
        }
        clearPipelines.values().forEach(MTLBuiltinPipelines::releaseResource);
        clearPipelines.clear();
        depthStencilStates.values().forEach(MTLBuiltinPipelines::releaseResource);
        depthStencilStates.clear();
        device = null;
    }

    static void clearDraw(
            final MTLRenderCommandEncoder encoder,
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            @Nullable final Vector4fc clearColor,
            @Nullable final Double clearDepth
    ) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MemorySegment sizeTexture = ObjC.isNil(colorTexture) ? depthTexture : colorTexture;
            if (ObjC.isNil(sizeTexture)) {
                return;
            }

            long colorFormat = ObjC.isNil(colorTexture) ? MTLPixelFormat.Invalid.value : MTLTexture.pixelFormat(colorTexture);
            long depthFormat = ObjC.isNil(depthTexture) ? MTLPixelFormat.Invalid.value : MTLTexture.pixelFormat(depthTexture);
            MemorySegment pipeline = ensureClearPipeline(colorFormat, depthFormat, clearColor != null);
            if (ObjC.isNil(pipeline)) {
                return;
            }

            MemorySegment depthState = depthFormat != MTLPixelFormat.Invalid.value
                    ? ensureDepthStencilState(MTLCompareFunction.Always, clearDepth != null)
                    : MemorySegment.NULL;

            long width = MTLTexture.width(sizeTexture);
            long height = MTLTexture.height(sizeTexture);
            if (width <= 0 || height <= 0) {
                return;
            }

            encodeClearDraw(
                    encoder, pipeline,
                    (long) viewportWidth, (long) viewportHeight,
                    clearColor,
                    0L, 0L, width, height,
                    depthState, clearDepth
            );
        }
    }

    static void clearColorDepthTexturesRegion(
            final MTLCommandBuffer commandBuffer,
            final MemorySegment colorTexture,
            final Vector4fc clearColor,
            final MemorySegment depthTexture,
            final double clearDepth,
            final int x,
            final int y,
            final int width,
            final int height,
            final MTLFence globalFence
    ) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            if (width <= 0 || height <= 0) {
                return;
            }

            long textureWidth = Math.min(MTLTexture.width(colorTexture), MTLTexture.width(depthTexture));
            long textureHeight = Math.min(MTLTexture.height(colorTexture), MTLTexture.height(depthTexture));
            long clampedX = Math.max(x, 0);
            long clampedY = Math.max(y, 0);
            long clampedMaxX = Math.min((long) x + width, textureWidth);
            long clampedMaxY = Math.min((long) y + height, textureHeight);
            if (clampedX >= clampedMaxX || clampedY >= clampedMaxY) {
                return;
            }
            boolean fullRegion = clampedX == 0 && clampedY == 0 && clampedMaxX == textureWidth && clampedMaxY == textureHeight;

            MTLRenderCommandEncoder encoder = commandBuffer.makeRenderCommandEncoder(
                    colorTexture, fullRegion ? 2 : 1, clearColor, depthTexture, fullRegion ? 2 : 1, clearDepth);

            if (globalFence != null) {
                encoder.waitForFence(globalFence, MTLRenderStages.Fragment);
            }

            if (!fullRegion) {
                MemorySegment pipeline = ensureClearPipeline(MTLTexture.pixelFormat(colorTexture), MTLTexture.pixelFormat(depthTexture), true);
                MemorySegment depthState = ensureDepthStencilState(MTLCompareFunction.Always, true);
                if (ObjC.isNil(pipeline) || ObjC.isNil(depthState)) {
                    encoder.endEncoding();
                    return;
                }
                encodeClearDraw(
                        encoder, pipeline,
                        textureWidth, textureHeight,
                        clearColor,
                        clampedX, clampedY, clampedMaxX - clampedX, clampedMaxY - clampedY,
                        depthState, clearDepth
                );
            }

            if (globalFence != null) {
                encoder.updateFence(globalFence, MTLRenderStages.Fragment);
            }

            encoder.endEncoding();
        }
    }

    static void encodePresentTextureToDrawable(
            final MTLCommandBuffer commandBuffer,
            final CAMetalLayer layer,
            final MemorySegment sourceTexture,
            final MTLFence globalFence
    ) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            CAMetalDrawable drawable = layer.nextDrawable();
            if (drawable == null) {
                return;
            }
            MemorySegment drawableTexture = drawable.texture();

            MTLRenderCommandEncoder encoder = commandBuffer.makeRenderCommandEncoder(
                    drawableTexture, 0, null, MemorySegment.NULL, 0, null);

            if (globalFence != null) {
                encoder.waitForFence(globalFence, MTLRenderStages.Fragment);
            }

            long drawableWidth = MTLTexture.width(drawableTexture);
            long drawableHeight = MTLTexture.height(drawableTexture);
            encoder.setViewport(0.0, 0.0, drawableWidth, drawableHeight, 0.0, 1.0);
            encoder.setRenderPipelineState(presentPipeline);
            encoder.setFragmentTexture(sourceTexture, 0L);

            boolean requiresScaling = MTLTexture.width(sourceTexture) != drawableWidth
                    || MTLTexture.height(sourceTexture) != drawableHeight;
            encoder.setFragmentSamplerState(requiresScaling ? presentLinearSampler : presentNearestSampler, 0L);

            encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);

            if (globalFence != null) {
                encoder.updateFence(globalFence, MTLRenderStages.Fragment);
            }

            encoder.endEncoding();
            commandBuffer.presentDrawable(drawable);
        }
    }

    private static void encodeClearDraw(
            final MTLRenderCommandEncoder encoder,
            final MemorySegment pipeline,
            final long viewportWidth,
            final long viewportHeight,
            @Nullable final Vector4fc clearColor,
            final long scissorX,
            final long scissorY,
            final long scissorWidth,
            final long scissorHeight,
            final MemorySegment depthState,
            @Nullable final Double clearDepth
    ) {
        encoder.setViewport(0.0, 0.0, viewportWidth, viewportHeight, 0.0, 1.0);
        encoder.setScissorRect(scissorX, scissorY, scissorWidth, scissorHeight);
        encoder.setRenderPipelineState(pipeline);
        if (!ObjC.isNil(depthState)) {
            encoder.setDepthStencilState(depthState);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            MemorySegment uniforms = MemorySegment.ofAddress(stack.nmalloc(16, 48)).reinterpret(48);
            float z = ObjC.isNil(depthState) || clearDepth == null ? 0.0f : (float) Math.clamp(clearDepth, 0.0, 1.0);
            uniforms.set(JAVA_FLOAT, 0, z);
            uniforms.set(JAVA_FLOAT, 32, clearColor == null ? 0.0f : clearColor.x());
            uniforms.set(JAVA_FLOAT, 36, clearColor == null ? 0.0f : clearColor.y());
            uniforms.set(JAVA_FLOAT, 40, clearColor == null ? 0.0f : clearColor.z());
            uniforms.set(JAVA_FLOAT, 44, clearColor == null ? 0.0f : clearColor.w());
            encoder.setVertexBytes(uniforms, 48L, 1L);
        }

        encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);
    }

    private static MemorySegment ensureClearPipeline(final long colorFormat, final long depthFormat, final boolean writeColor) {
        long key = (colorFormat << 32) | (depthFormat << 1) | (writeColor ? 1L : 0L);
        MemorySegment cached = clearPipelines.get(key);
        if (cached != null) {
            return cached;
        }
        MemorySegment pipeline = buildPipeline(CLEAR_MSL, "metallum_clear_vs", "metallum_clear_fs",
                colorFormat, depthFormat, writeColor ? MTLColorWriteMask.All.value : MTLColorWriteMask.None.value);
        if (!ObjC.isNil(pipeline)) {
            clearPipelines.put(key, pipeline);
        }
        return pipeline;
    }

    private static MemorySegment ensureDepthStencilState(final MTLCompareFunction compareOp, final boolean writeDepth) {
        long key = (compareOp.value << 1) | (writeDepth ? 1L : 0L);
        MemorySegment cached = depthStencilStates.get(key);
        if (cached != null) {
            return cached;
        }
        MemorySegment state = own(device.nativeOwner().createDepthState(compareOp.value, writeDepth));
        depthStencilStates.put(key, state);
        return state;
    }

    private static MemorySegment buildPipeline(
            final String mslSource,
            final String vertexEntry,
            final String fragmentEntry,
            final long colorFormat,
            final long depthFormat,
            final long writeMask
    ) {
        try (MTLFunction vertex = device.newFunction(mslSource, vertexEntry);
             MTLFunction fragment = device.newFunction(mslSource, fragmentEntry)) {
            if (ObjC.isNil(vertex.handle()) || ObjC.isNil(fragment.handle())) return MemorySegment.NULL;
            return own(device.nativeOwner().createPipeline(vertex.nativeResource(), fragment.nativeResource(),
                    new NativePipelineDescriptor(colorFormat, depthFormat, MTLPixelFormat.Invalid.value, writeMask)));
        }
    }

    private static MemorySegment own(NativeMetalDevice.Resource resource) {
        MemorySegment handle = resource.borrowedHandle();
        nativeResources.computeIfAbsent(handle.address(), ignored -> new ArrayDeque<>()).addLast(resource);
        return handle;
    }

    private static void releaseResource(MemorySegment handle) {
        var owners = nativeResources.get(handle.address());
        if (owners == null) throw new IllegalStateException("Unknown built-in Metal resource");
        owners.removeFirst().close();
        if (owners.isEmpty()) nativeResources.remove(handle.address());
    }

    private static MemorySegment buildPresentSampler(final MTLSamplerMinMagFilter filter) {
        return own(device.nativeOwner().createPresentSampler(filter == MTLSamplerMinMagFilter.Linear));
    }

}
