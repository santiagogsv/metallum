package com.metallum.mtl;

import com.metallum.nativebridge.NativeMetalDevice;
import com.metallum.nativebridge.NativePipelineDescriptor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
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
    private static NativeMetalDevice.Resource presentPipeline = null;
    private static NativeMetalDevice.Resource presentLinearSampler = null;
    private static NativeMetalDevice.Resource presentNearestSampler = null;
    private static final Map<Long, NativeMetalDevice.Resource> clearPipelines = new HashMap<>();
    private static final Map<Long, NativeMetalDevice.Resource> depthStencilStates = new HashMap<>();

    private MTLBuiltinPipelines() {
    }

    public static void init(final MTLDevice mtlDevice) {
        device = mtlDevice;
        presentPipeline = buildPipeline(PRESENT_MSL, "metallum_present_vs", "metallum_present_fs",
                MTLPixelFormat.BGRA8Unorm.value, MTLColorWriteMask.All.value);
        presentLinearSampler = device.nativeOwner().createPresentSampler(true);
        presentNearestSampler = device.nativeOwner().createPresentSampler(false);
        ensureClearPipeline(MTLPixelFormat.BGRA8Unorm.value, true);
        ensureClearPipeline(MTLPixelFormat.RGBA8Unorm.value, true);
    }

    public static void close() {
        if (presentPipeline != null) {
            presentPipeline.close();
            presentPipeline = null;
        }
        if (presentLinearSampler != null) {
            presentLinearSampler.close();
            presentLinearSampler = null;
        }
        if (presentNearestSampler != null) {
            presentNearestSampler.close();
            presentNearestSampler = null;
        }
        clearPipelines.values().forEach(NativeMetalDevice.Resource::close);
        clearPipelines.clear();
        depthStencilStates.values().forEach(NativeMetalDevice.Resource::close);
        depthStencilStates.clear();
        device = null;
    }

    static void clearDraw(
            final MTLRenderCommandEncoder encoder,
            final NativeMetalDevice.Resource colorTexture,
            final NativeMetalDevice.Resource depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            @Nullable final Vector4fc clearColor,
            @Nullable final Double clearDepth
    ) {
        {
            NativeMetalDevice.Resource sizeTexture = (colorTexture == null) ? depthTexture : colorTexture;
            if ((sizeTexture == null)) {
                return;
            }

            long colorFormat = (colorTexture == null) ? MTLPixelFormat.Invalid.value : MTLTexture.pixelFormat(colorTexture);
            NativeMetalDevice.Resource pipeline = ensureClearPipeline(colorFormat, clearColor != null);
            if ((pipeline == null)) {
                return;
            }

            NativeMetalDevice.Resource depthState = depthTexture != null
                    ? ensureDepthStencilState(MTLCompareFunction.Always, clearDepth != null)
                    : null;

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
            final NativeMetalDevice.Resource colorTexture,
            final Vector4fc clearColor,
            final NativeMetalDevice.Resource depthTexture,
            final double clearDepth,
            final int x,
            final int y,
            final int width,
            final int height,
            final MTLFence globalFence
    ) {
        {
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
                NativeMetalDevice.Resource pipeline = ensureClearPipeline(MTLTexture.pixelFormat(colorTexture), true);
                NativeMetalDevice.Resource depthState = ensureDepthStencilState(MTLCompareFunction.Always, true);
                if ((pipeline == null) || (depthState == null)) {
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
            final NativeMetalDevice.Resource sourceTexture,
            final MTLFence globalFence
    ) {
        commandBuffer.present(layer, sourceTexture, globalFence, presentPipeline, presentNearestSampler, presentLinearSampler);
    }

    private static void encodeClearDraw(
            final MTLRenderCommandEncoder encoder,
            final NativeMetalDevice.Resource pipeline,
            final long viewportWidth,
            final long viewportHeight,
            @Nullable final Vector4fc clearColor,
            final long scissorX,
            final long scissorY,
            final long scissorWidth,
            final long scissorHeight,
            final NativeMetalDevice.Resource depthState,
            @Nullable final Double clearDepth
    ) {
        encoder.setViewport(0.0, 0.0, viewportWidth, viewportHeight, 0.0, 1.0);
        encoder.setScissorRect(scissorX, scissorY, scissorWidth, scissorHeight);
        encoder.setRenderPipelineState(pipeline);
        // A clear may share an encoder with scene draws; inherited raster state
        // must not cull it, turn it into lines, or bias its requested depth.
        encoder.setCullMode(MTLCullMode.None);
        encoder.setTriangleFillMode(MTLTriangleFillMode.Fill);
        encoder.setDepthBias(0, 0, 0);
        encoder.setDepthStencilState(depthState);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            MemorySegment uniforms = MemorySegment.ofAddress(stack.nmalloc(16, 48)).reinterpret(48);
            float z = (depthState == null) || clearDepth == null ? 0.0f : (float) Math.clamp(clearDepth, 0.0, 1.0);
            uniforms.set(JAVA_FLOAT, 0, z);
            uniforms.set(JAVA_FLOAT, 32, clearColor == null ? 0.0f : clearColor.x());
            uniforms.set(JAVA_FLOAT, 36, clearColor == null ? 0.0f : clearColor.y());
            uniforms.set(JAVA_FLOAT, 40, clearColor == null ? 0.0f : clearColor.z());
            uniforms.set(JAVA_FLOAT, 44, clearColor == null ? 0.0f : clearColor.w());
            encoder.setVertexBytes(uniforms, 48L, 1L);
        }

        encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);
    }

    private static NativeMetalDevice.Resource ensureClearPipeline(final long colorFormat, final boolean writeColor) {
        long key = (colorFormat << 1) | (writeColor ? 1L : 0L);
        NativeMetalDevice.Resource cached = clearPipelines.get(key);
        if (cached != null) {
            return cached;
        }
        NativeMetalDevice.Resource pipeline = buildPipeline(CLEAR_MSL, "metallum_clear_vs", "metallum_clear_fs",
                colorFormat, writeColor ? MTLColorWriteMask.All.value : MTLColorWriteMask.None.value);
        clearPipelines.put(key, pipeline);
        return pipeline;
    }

    private static NativeMetalDevice.Resource ensureDepthStencilState(final MTLCompareFunction compareOp, final boolean writeDepth) {
        long key = (compareOp.value << 1) | (writeDepth ? 1L : 0L);
        NativeMetalDevice.Resource cached = depthStencilStates.get(key);
        if (cached != null) {
            return cached;
        }
        NativeMetalDevice.Resource state = device.nativeOwner().createDepthState(compareOp.value, writeDepth);
        depthStencilStates.put(key, state);
        return state;
    }

    private static NativeMetalDevice.Resource buildPipeline(
            final String mslSource,
            final String vertexEntry,
            final String fragmentEntry,
            final long colorFormat,
            final long writeMask
    ) {
        try (MTLFunction vertex = device.newFunction(mslSource, vertexEntry);
             MTLFunction fragment = device.newFunction(mslSource, fragmentEntry)) {
            return device.nativeOwner().createPipeline(vertex.nativeResource(), fragment.nativeResource(),
                    new NativePipelineDescriptor(colorFormat, writeMask));
        }
    }

}
