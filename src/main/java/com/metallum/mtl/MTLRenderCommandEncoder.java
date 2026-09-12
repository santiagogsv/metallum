package com.metallum.mtl;

import com.metallum.nativebridge.NativeMetalDevice;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;


@Environment(EnvType.CLIENT)
public final class MTLRenderCommandEncoder extends MTLCommandEncoder {
    private final NativeMetalDevice device;
    private final NativeMetalDevice.Resource pass;

    MTLRenderCommandEncoder(NativeMetalDevice device, NativeMetalDevice.Resource pass) {
        super(pass);
        this.device = device;
        this.pass = pass;
    }

    private void command(int op, MemorySegment p0, MemorySegment p1,
                         long a, long b, long c, long d, long e, long f, long g, long h) {
        device.renderCommand(pass, op, p0, p1, a, b, c, d, e, f, g, h);
    }

    private static long bits(double value) { return Double.doubleToRawLongBits(value); }

    public void setRenderPipelineState(final MemorySegment pipeline) {
        command(0, ObjC.orNil(pipeline), MemorySegment.NULL, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setDepthStencilState(final MemorySegment depthStencilState) {
        command(1, ObjC.orNil(depthStencilState), MemorySegment.NULL, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setDepthBias(final float depthBias, final float slopeScale, final float clamp) {
        command(2, MemorySegment.NULL, MemorySegment.NULL, bits(depthBias), bits(slopeScale), bits(clamp), 0, 0, 0, 0, 0);
    }

    public void setFrontFacingWinding(final MTLWinding winding) {
        command(3, MemorySegment.NULL, MemorySegment.NULL, winding.value, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setCullMode(final MTLCullMode cullMode) {
        command(4, MemorySegment.NULL, MemorySegment.NULL, cullMode.value, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setTriangleFillMode(final MTLTriangleFillMode fillMode) {
        command(5, MemorySegment.NULL, MemorySegment.NULL, fillMode.value, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setVertexBuffer(final MTLBuffer buffer, final long offset, final long index) {
        command(6, seg(buffer), MemorySegment.NULL, offset, index, 0, 0, 0, 0, 0, 0);
    }

    public void setFragmentBuffer(final MTLBuffer buffer, final long offset, final long index) {
        command(7, seg(buffer), MemorySegment.NULL, offset, index, 0, 0, 0, 0, 0, 0);
    }

    public void setVertexBufferOffset(final long offset, final long index) {
        command(8, MemorySegment.NULL, MemorySegment.NULL, offset, index, 0, 0, 0, 0, 0, 0);
    }

    public void setFragmentBufferOffset(final long offset, final long index) {
        command(9, MemorySegment.NULL, MemorySegment.NULL, offset, index, 0, 0, 0, 0, 0, 0);
    }

    public void setVertexTexture(final MemorySegment texture, final long index) {
        command(10, ObjC.orNil(texture), MemorySegment.NULL, index, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setFragmentTexture(final MemorySegment texture, final long index) {
        command(11, ObjC.orNil(texture), MemorySegment.NULL, index, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setVertexSamplerState(final MemorySegment sampler, final long index) {
        command(12, ObjC.orNil(sampler), MemorySegment.NULL, index, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setFragmentSamplerState(final MemorySegment sampler, final long index) {
        command(13, ObjC.orNil(sampler), MemorySegment.NULL, index, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setScissorRect(final long x, final long y, final long width, final long height) {
        command(14, MemorySegment.NULL, MemorySegment.NULL, x, y, width, height, 0, 0, 0, 0);
    }

    public void setViewport(final double originX, final double originY, final double width, final double height, final double znear, final double zfar) {
        command(15, MemorySegment.NULL, MemorySegment.NULL, bits(originX), bits(originY), bits(width), bits(height), bits(znear), bits(zfar), 0, 0);
    }

    public void setVertexBytes(final MemorySegment bytes, final long length, final long index) {
        command(16, bytes, MemorySegment.NULL, length, index, 0, 0, 0, 0, 0, 0);
    }

    public void clearDraw(
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            @Nullable final Vector4fc clearColor,
            @Nullable final Double clearDepth
    ) {
        MTLBuiltinPipelines.clearDraw(
                this,
                colorTexture,
                depthTexture,
                viewportWidth,
                viewportHeight,
                clearColor,
                clearDepth
        );
    }

    public void drawPrimitives(final MTLPrimitiveType primitiveType, final int firstVertex, final int vertexCount, final int instanceCount, final int baseInstance) {
        command(17, MemorySegment.NULL, MemorySegment.NULL, primitiveType.value, firstVertex, vertexCount, instanceCount, baseInstance, 0, 0, 0);
    }

    public void drawIndexedPrimitives(final MTLPrimitiveType primitiveType, final int indexCount, final MTLIndexType indexType, final MTLBuffer indexBuffer, final long offset, final int instanceCount, final int baseVertex, final int baseInstance) {
        command(18, indexBuffer.handle(), MemorySegment.NULL, primitiveType.value, indexCount, indexType.value, offset, instanceCount, baseVertex, baseInstance, 0);
    }

    public void drawIndexedPrimitivesIndirect(final MTLPrimitiveType primitiveType, final MTLIndexType indexType, final MTLBuffer indexBuffer, final MTLBuffer indirectBuffer, final long indirectBufferOffset) {
        command(19, indexBuffer.handle(), indirectBuffer.handle(), primitiveType.value, indexType.value, indirectBufferOffset, 0, 0, 0, 0, 0);
    }

    public void drawPrimitivesIndirect(final MTLPrimitiveType primitiveType, final MTLBuffer indirectBuffer, final long indirectBufferOffset) {
        command(20, indirectBuffer.handle(), MemorySegment.NULL, primitiveType.value, indirectBufferOffset, 0, 0, 0, 0, 0, 0);
    }

    public void updateFence(final MTLFence fence, final MTLRenderStages stages) {
        command(21, fence.handle(), MemorySegment.NULL, stages.value, 0, 0, 0, 0, 0, 0, 0);
    }

    public void waitForFence(final MTLFence fence, final MTLRenderStages stages) {
        command(22, fence.handle(), MemorySegment.NULL, stages.value, 0, 0, 0, 0, 0, 0, 0);
    }

    private static MemorySegment seg(final MTLBuffer buffer) {
        return buffer == null ? MemorySegment.NULL : buffer.handle();
    }
}
