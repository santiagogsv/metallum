package com.metallum.mtl;

import com.metallum.nativebridge.NativeMetalDevice;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import static java.lang.foreign.ValueLayout.*;

/** Exercises production Java adapters against a recording C fixture, not a GPU. */
public class RenderAdapterSmoke {
    private static MethodHandle snapshot;
    private static MemorySegment output;
    private static final MemorySegment NIL = MemorySegment.NULL;
    private static long bits(double value) { return Double.doubleToRawLongBits(value); }
    private static void check(Runnable call, int op, long p0, long p1, long... words) throws Throwable {
        call.run(); snapshot.invokeExact(output);
        if (output.getAtIndex(JAVA_LONG, 0) != op || output.getAtIndex(JAVA_LONG, 1) != p0
                || output.getAtIndex(JAVA_LONG, 2) != p1) throw new AssertionError("Operation/pointers " + op);
        for (int i = 0; i < 8; i++) if (output.getAtIndex(JAVA_LONG, 3 + i) != (i < words.length ? words[i] : 0))
            throw new AssertionError("Operation " + op + " argument " + i);
    }
    public static void main(String[] args) throws Throwable {
        Path fixture = Path.of(args[0]);
        try (Arena arena = Arena.ofConfined(); var device = new NativeMetalDevice(fixture)) {
            snapshot = Linker.nativeLinker().downcallHandle(SymbolLookup.libraryLookup(fixture, arena).findOrThrow("metallum_test_last_render"), FunctionDescriptor.ofVoid(ADDRESS));
            output = arena.allocate(88, 8);
            try (var texture = device.createTexture(70, 8, 8, 1, 1, false, true, null);
                 var command = device.createCommandBuffer(null);
                 var buffer = new MTLBuffer(device.createBuffer(128, true));
                 var indirect = new MTLBuffer(device.createBuffer(128, true));
                 var fence = new MTLFence(device.createFence())) {
                var pass = device.createRenderPass(command, texture, null, 1, 0, new double[]{0,0,0,0,1});
                var encoder = new MTLRenderCommandEncoder(device, pass);
                NativeMetalDevice.Resource ptr = texture; // Fixture records pointers without interpreting Metal types.
                check(() -> encoder.setRenderPipelineState(ptr), 0, ptr.id(device), 0);
                check(() -> encoder.setDepthStencilState(null), 1, 0, 0);
                check(() -> encoder.setDepthBias(-1.5f, 2.25f, 0.5f), 2, 0, 0, bits(-1.5), bits(2.25), bits(0.5));
                check(() -> encoder.setFrontFacingWinding(MTLWinding.CounterClockwise), 3, 0, 0, 1);
                check(() -> encoder.setCullMode(MTLCullMode.Back), 4, 0, 0, 2);
                check(() -> encoder.setTriangleFillMode(MTLTriangleFillMode.Lines), 5, 0, 0, 1);
                check(() -> encoder.setVertexBuffer(buffer, 8, 3), 6, buffer.nativeOwner().id(device), 0, 8, 3);
                check(() -> encoder.setFragmentBuffer(buffer, 16, 2), 7, buffer.nativeOwner().id(device), 0, 16, 2);
                check(() -> encoder.setVertexBufferOffset(24, 3), 8, 0, 0, 24, 3);
                check(() -> encoder.setFragmentBufferOffset(32, 2), 9, 0, 0, 32, 2);
                check(() -> encoder.setVertexTexture(ptr, 4), 10, ptr.id(device), 0, 4);
                check(() -> encoder.setFragmentTexture(ptr, 5), 11, ptr.id(device), 0, 5);
                check(() -> encoder.setVertexSamplerState(ptr, 6), 12, ptr.id(device), 0, 6);
                check(() -> encoder.setFragmentSamplerState(ptr, 7), 13, ptr.id(device), 0, 7);
                check(() -> encoder.setScissorRect(1, 2, 6, 5), 14, 0, 0, 1, 2, 6, 5);
                check(() -> encoder.setViewport(0.25, 0.5, 8, 7, 0, 1), 15, 0, 0, bits(0.25), bits(0.5), bits(8), bits(7), bits(0), bits(1));
                check(() -> encoder.setVertexBytes(output, 16, 2), 16, output.address(), 0, 16, 2);
                check(() -> encoder.drawPrimitives(MTLPrimitiveType.Triangle, 3, 6, 2, 7), 17, 0, 0, 3, 3, 6, 2, 7);
                check(() -> encoder.drawIndexedPrimitives(MTLPrimitiveType.Triangle, 6, MTLIndexType.UInt32, buffer, 8, 2, -3, 4), 18, buffer.nativeOwner().id(device), 0, 3, 6, 1, 8, 2, -3, 4);
                check(() -> encoder.drawIndexedPrimitivesIndirect(MTLPrimitiveType.Triangle, MTLIndexType.UInt16, buffer, indirect, 20), 19, buffer.nativeOwner().id(device), indirect.nativeOwner().id(device), 3, 0, 20);
                check(() -> encoder.drawPrimitivesIndirect(MTLPrimitiveType.Triangle, indirect, 16), 20, indirect.nativeOwner().id(device), 0, 3, 16);
                check(() -> encoder.updateFence(fence, MTLRenderStages.Fragment), 21, fence.owner().id(device), 0, 2);
                check(() -> encoder.waitForFence(fence, MTLRenderStages.VertexAndFragment), 22, fence.owner().id(device), 0, 3);
                encoder.endEncoding(); encoder.endEncoding();
                try { encoder.setVertexTexture(ptr, 0); throw new AssertionError("Closed pass accepted"); }
                catch (IllegalStateException expected) { }
                try (var layer = new CAMetalLayer(new MTLDevice(device), 2)) {
                    layer.configure(1708, 960, false);
                    device.present(command, layer.owner(), ptr, fence.owner(), ptr, ptr, ptr);
                }
            }
            if (device.memoryStats().resources() != 0) throw new AssertionError("Adapter resources leaked");
        }
        System.out.println("All 23 render adapter operations and presentation ownership passed (C fixture)");
    }
}
