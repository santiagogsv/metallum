package com.metallum.mtl;

import com.metallum.nativebridge.NativeMetalDevice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import static java.lang.foreign.ValueLayout.*;


@Environment(EnvType.CLIENT)
public final class MTLRenderCommandEncoder extends MTLCommandEncoder {
    private final NativeMetalDevice device;
    private final NativeMetalDevice.Resource pass;

    MTLRenderCommandEncoder(NativeMetalDevice device, NativeMetalDevice.Resource pass) {
        super(pass);
        this.device = device;
        this.pass = pass;
    }

    private void command(int op, long p0, long p1,
                         long a, long b, long c, long d, long e, long f, long g, long h) {
        device.renderCommand(pass, op, p0, p1, a, b, c, d, e, f, g, h);
    }

    private long id(NativeMetalDevice.Resource resource) { return resource == null ? 0 : resource.id(device); }

    private static long bits(double value) { return Double.doubleToRawLongBits(value); }

    public void setRenderPipelineState(final NativeMetalDevice.Resource pipeline) {
        command(0, id(pipeline), 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setDepthStencilState(final NativeMetalDevice.Resource depthStencilState) {
        command(1, id(depthStencilState), 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setDepthBias(final float depthBias, final float slopeScale, final float clamp) {
        command(2, 0, 0, bits(depthBias), bits(slopeScale), bits(clamp), 0, 0, 0, 0, 0);
    }

    public void setFrontFacingWinding(final MTLWinding winding) {
        command(3, 0, 0, winding.value, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setCullMode(final MTLCullMode cullMode) {
        command(4, 0, 0, cullMode.value, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setTriangleFillMode(final MTLTriangleFillMode fillMode) {
        command(5, 0, 0, fillMode.value, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setVertexBuffer(final MTLBuffer buffer, final long offset, final long index) {
        command(6, seg(buffer), 0, offset, index, 0, 0, 0, 0, 0, 0);
    }

    public void setFragmentBuffer(final MTLBuffer buffer, final long offset, final long index) {
        command(7, seg(buffer), 0, offset, index, 0, 0, 0, 0, 0, 0);
    }

    public void setVertexBufferOffset(final long offset, final long index) {
        command(8, 0, 0, offset, index, 0, 0, 0, 0, 0, 0);
    }

    public void setFragmentBufferOffset(final long offset, final long index) {
        command(9, 0, 0, offset, index, 0, 0, 0, 0, 0, 0);
    }

    public void setVertexTexture(final NativeMetalDevice.Resource texture, final long index) {
        command(10, id(texture), 0, index, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setFragmentTexture(final NativeMetalDevice.Resource texture, final long index) {
        command(11, id(texture), 0, index, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setVertexSamplerState(final NativeMetalDevice.Resource sampler, final long index) {
        command(12, id(sampler), 0, index, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setFragmentSamplerState(final NativeMetalDevice.Resource sampler, final long index) {
        command(13, id(sampler), 0, index, 0, 0, 0, 0, 0, 0, 0);
    }

    public void setScissorRect(final long x, final long y, final long width, final long height) {
        command(14, 0, 0, x, y, width, height, 0, 0, 0, 0);
    }

    public void setViewport(final double originX, final double originY, final double width, final double height, final double znear, final double zfar) {
        command(15, 0, 0, bits(originX), bits(originY), bits(width), bits(height), bits(znear), bits(zfar), 0, 0);
    }

    public void setVertexBytes(final MemorySegment bytes, final long length, final long index) {
        device.renderBytes(pass, bytes, length, index);
    }

    public void clearDraw(
            final NativeMetalDevice.Resource colorTexture,
            final NativeMetalDevice.Resource depthTexture,
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
        command(17, 0, 0, primitiveType.value, firstVertex, vertexCount, instanceCount, baseInstance, 0, 0, 0);
    }

    public void drawIndexedPrimitives(final MTLPrimitiveType primitiveType, final int indexCount, final MTLIndexType indexType, final MTLBuffer indexBuffer, final long offset, final int instanceCount, final int baseVertex, final int baseInstance) {
        command(18, indexBuffer.nativeOwner().id(device), 0, primitiveType.value, indexCount, indexType.value, offset, instanceCount, baseVertex, baseInstance, 0);
    }

    public void drawIndexedPrimitivesIndirect(final MTLPrimitiveType primitiveType, final MTLIndexType indexType, final MTLBuffer indexBuffer, final MTLBuffer indirectBuffer, final long indirectBufferOffset, final int drawCount) {
        command(19, indexBuffer.nativeOwner().id(device), indirectBuffer.nativeOwner().id(device), primitiveType.value, indexType.value, indirectBufferOffset, drawCount, 0, 0, 0, 0);
    }

    public void drawPrimitivesIndirect(final MTLPrimitiveType primitiveType, final MTLBuffer indirectBuffer, final long indirectBufferOffset, final int drawCount) {
        command(20, indirectBuffer.nativeOwner().id(device), 0, primitiveType.value, indirectBufferOffset, drawCount, 0, 0, 0, 0, 0);
    }

    private static void record(MemorySegment batch, int slot, long offset, int count, int vertex) {
        long start = slot * 16L;
        batch.set(JAVA_LONG, start, offset);
        batch.set(JAVA_INT, start + 8, count);
        batch.set(JAVA_INT, start + 12, vertex);
    }

    public void multiDrawIndexed(MTLPrimitiveType primitive, MTLIndexType type, MTLBuffer indices,
                                 IntBuffer parameters, int instances, int baseInstance, int drawCount) {
        if (drawCount < 0 || drawCount > parameters.limit() / 3 || instances < 0 || baseInstance < 0)
            throw new IllegalArgumentException("Invalid indexed draw parameters");
        MemorySegment batch = device.indexedDrawScratch();
        int size = 0;
        for (int i = 0; i < drawCount; i++) {
            int count = parameters.get(i * 3 + 1);
            if (count <= 0) continue;
            record(batch, size++, (long) parameters.get(i * 3) * type.bytes, count, parameters.get(i * 3 + 2));
            if (size == NativeMetalDevice.INDEXED_BATCH_CAPACITY) {
                device.renderIndexedBatch(pass, indices.nativeOwner(), primitive.value, type.value, instances, baseInstance, size);
                size = 0;
            }
        }
        device.renderIndexedBatch(pass, indices.nativeOwner(), primitive.value, type.value, instances, baseInstance, size);
    }

    public void multiDrawIndexed(MTLPrimitiveType primitive, MTLIndexType type, MTLBuffer indices,
                                 PointerBuffer offsets, IntBuffer counts, IntBuffer vertices, int drawCount) {
        if (drawCount < 0 || drawCount > offsets.remaining() || drawCount > counts.remaining() || drawCount > vertices.remaining())
            throw new IllegalArgumentException("Invalid indexed draw arrays");
        MemorySegment batch = device.indexedDrawScratch();
        int size = 0;
        for (int i = 0; i < drawCount; i++) {
            int count = counts.get(counts.position() + i);
            if (count <= 0) continue;
            record(batch, size++, offsets.get(offsets.position() + i), count, vertices.get(vertices.position() + i));
            if (size == NativeMetalDevice.INDEXED_BATCH_CAPACITY) {
                device.renderIndexedBatch(pass, indices.nativeOwner(), primitive.value, type.value, 1, 0, size);
                size = 0;
            }
        }
        device.renderIndexedBatch(pass, indices.nativeOwner(), primitive.value, type.value, 1, 0, size);
    }

    public void updateFence(final MTLFence fence, final MTLRenderStages stages) {
        command(21, id(fence.owner()), 0, stages.value, 0, 0, 0, 0, 0, 0, 0);
    }

    public void waitForFence(final MTLFence fence, final MTLRenderStages stages) {
        command(22, id(fence.owner()), 0, stages.value, 0, 0, 0, 0, 0, 0, 0);
    }

    private long seg(final MTLBuffer buffer) {
        return buffer == null ? 0 : buffer.nativeOwner().id(device);
    }
}
