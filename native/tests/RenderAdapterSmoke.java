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
    private static void checkCopy(MethodHandle snapshot, MemorySegment output, long[] expected) throws Throwable {
        snapshot.invokeExact(output);
        for (int i = 0; i < 16; i++) if (output.getAtIndex(JAVA_LONG, i) != expected[i])
            throw new AssertionError("Copy argument " + i + " for operation " + expected[0]);
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
                var pass = device.createRenderPass(command, texture, null, 1, 0, 0,0,0,0,1);
                var encoder = new MTLRenderCommandEncoder(device, pass);
                NativeMetalDevice.Resource ptr = texture; // Fixture records pointers without interpreting Metal types.
                check(() -> encoder.setRenderPipelineState(ptr), 0, ptr.id(device), 0);
                check(() -> encoder.setDepthStencilState(null), 1, 0, 0);
                check(() -> encoder.setDepthBias(-1.5f, 2.25f, 0.5f), 2, 0, 0, bits(-1.5), bits(2.25), bits(0.5));
                check(() -> encoder.setFrontFacingWinding(MTLWinding.CounterClockwise), 3, 0, 0, 1);
                check(() -> encoder.setCullMode(MTLCullMode.Back), 4, 0, 0, 2);
                check(() -> encoder.setTriangleFillMode(MTLTriangleFillMode.Lines), 5, 0, 0, 1);
                check(() -> encoder.setScissorRect(1, 2, 6, 5), 14, 0, 0, 1, 2, 6, 5);
                check(() -> encoder.setViewport(0.25, 0.5, 8, 7, 0, 1), 15, 0, 0, bits(0.25), bits(0.5), bits(8), bits(7), bits(0), bits(1));
                check(() -> encoder.setVertexBytes(output, 16, 2), 16, output.address(), 0, 16, 2);
                check(() -> encoder.drawPrimitives(MTLPrimitiveType.Triangle, 3, 6, 2, 7), 17, 0, 0, 3, 3, 6, 2, 7);
                check(() -> encoder.drawIndexedPrimitives(MTLPrimitiveType.Triangle, 6, MTLIndexType.UInt32, buffer, 8, 2, -3, 4), 18, buffer.nativeOwner().id(device), 0, 3, 6, 1, 8, 2, -3, 4);
                check(() -> encoder.drawIndexedPrimitivesIndirect(MTLPrimitiveType.Triangle, MTLIndexType.UInt16, buffer, indirect, 20, 3), 19, buffer.nativeOwner().id(device), indirect.nativeOwner().id(device), 3, 0, 20, 3);
                check(() -> encoder.drawPrimitivesIndirect(MTLPrimitiveType.Triangle, indirect, 16, 4), 20, indirect.nativeOwner().id(device), 0, 3, 16, 4);
                check(() -> encoder.updateFence(fence, MTLRenderStages.Fragment), 21, fence.owner().id(device), 0, 2);
                check(() -> encoder.waitForFence(fence, MTLRenderStages.VertexAndFragment), 22, fence.owner().id(device), 0, 3);
                check(() -> encoder.bindBuffer(buffer, 8, 3, 1), 23, buffer.nativeOwner().id(device), 0, 8, 3, 1);
                check(() -> encoder.bindBuffer(buffer, 16, 2, 2), 23, buffer.nativeOwner().id(device), 0, 16, 2, 2);
                check(() -> encoder.bindBuffer(buffer, 12, 2, 3), 23, buffer.nativeOwner().id(device), 0, 12, 2, 3);
                check(() -> encoder.bindTexture(ptr, ptr, 4, 3, true), 24, ptr.id(device), ptr.id(device), 4, 3, 1);
                check(() -> encoder.bindTexture(ptr, null, 7, 1, false), 24, ptr.id(device), 0, 7, 1, 0);
                check(() -> encoder.discardAttachments(true, false), 25, 0, 0, 1, 0);
                var view = buffer.nativeOwner().cachedTexture(70, 0, 4, 16);
                if (view != buffer.nativeOwner().cachedTexture(70, 0, 4, 16)) throw new AssertionError("Texel view not reused");
                var replacement = buffer.nativeOwner().cachedTexture(70, 16, 4, 16);
                if (view == replacement) throw new AssertionError("Texel range change was ignored");
                try { view.id(device); throw new AssertionError("Evicted view handle stayed open"); }
                catch (IllegalStateException expected) { }
                var symbols = SymbolLookup.libraryLookup(fixture, arena);
                var clearSnapshot = Linker.nativeLinker().downcallHandle(symbols.findOrThrow("metallum_test_last_clear"), FunctionDescriptor.ofVoid(ADDRESS));
                // Render words and pass clears share scratch: interleave integer and double payloads.
                for (int repeat = 0; repeat < 3; repeat++) {
                    encoder.setScissorRect(1, 2, 3, 4);
                    try (var nextPass = device.createRenderPass(command, texture, null, 2, 0,
                            0.125 * repeat, 0.25, 0.5, 1, 0.75)) {
                        clearSnapshot.invokeExact(output);
                        double[] expectedClear = {0.125 * repeat, 0.25, 0.5, 1, 0.75};
                        for (int i = 0; i < 5; i++) if (output.getAtIndex(JAVA_DOUBLE, i) != expectedClear[i])
                            throw new AssertionError("Reused clear payload argument " + i);
                    }
                    check(() -> encoder.setCullMode(MTLCullMode.Back), 4, 0, 0, 2);
                }
                var batchStats = Linker.nativeLinker().downcallHandle(symbols.findOrThrow("metallum_test_batch_stats"), FunctionDescriptor.ofVoid(ADDRESS));
                var resetBatch = Linker.nativeLinker().downcallHandle(symbols.findOrThrow("metallum_test_reset_batch"), FunctionDescriptor.ofVoid());
                var parameters = java.nio.IntBuffer.allocate(258 * 3);
                for (int i = 0; i < 258; i++) { parameters.put(i * 3, 2); parameters.put(i * 3 + 1, 3); parameters.put(i * 3 + 2, -7); }
                parameters.put(1, 0); // One skipped draw, 257 active draws span two chunks.
                parameters.position(9); // Packed overload intentionally uses absolute element zero.
                resetBatch.invokeExact();
                encoder.multiDrawIndexed(MTLPrimitiveType.Triangle, MTLIndexType.UInt32, buffer, parameters, 2, 5, 258);
                batchStats.invokeExact(output);
                long[] expectedBatch = {2, 257, 257 * 8, 257 * 3, -257 * 7, 3, 1, 2, 5, buffer.nativeOwner().id(device)};
                for (int i = 0; i < expectedBatch.length; i++) if (output.getAtIndex(JAVA_LONG, i) != expectedBatch[i])
                    throw new AssertionError("Packed batch argument " + i);
                if (parameters.position() != 9) throw new AssertionError("Packed input position changed");
                try { encoder.multiDrawIndexed(MTLPrimitiveType.Triangle, MTLIndexType.UInt32, buffer, parameters, 1, 0, 259); throw new AssertionError("Short packed input accepted"); }
                catch (IllegalArgumentException expected) { }
                var offsets = org.lwjgl.PointerBuffer.create(arena.allocate(24, 8).address(), 3);
                offsets.put(0, 900); offsets.put(1, 4); offsets.put(2, 8); offsets.position(1);
                var counts = java.nio.IntBuffer.wrap(new int[]{900, 6, -1}); counts.position(1);
                var vertices = java.nio.IntBuffer.wrap(new int[]{900, -3, 8}); vertices.position(1);
                resetBatch.invokeExact();
                encoder.multiDrawIndexed(MTLPrimitiveType.Triangle, MTLIndexType.UInt16, buffer, offsets, counts, vertices, 2);
                batchStats.invokeExact(output);
                long[] expectedArrays = {1, 1, 4, 6, -3, 3, 0, 1, 0, buffer.nativeOwner().id(device)};
                for (int i = 0; i < expectedArrays.length; i++) if (output.getAtIndex(JAVA_LONG, i) != expectedArrays[i])
                    throw new AssertionError("Array batch argument " + i);
                if (offsets.position() != 1 || counts.position() != 1 || vertices.position() != 1) throw new AssertionError("Array positions changed");
                resetBatch.invokeExact();
                encoder.multiDrawIndexed(MTLPrimitiveType.Triangle, MTLIndexType.UInt16, buffer, offsets, counts, vertices, 0);
                batchStats.invokeExact(output);
                if (output.getAtIndex(JAVA_LONG, 0) != 0) throw new AssertionError("Empty batch crossed FFM");
                try { encoder.multiDrawIndexed(MTLPrimitiveType.Triangle, MTLIndexType.UInt16, buffer, offsets, counts, vertices, 3); throw new AssertionError("Short arrays accepted"); }
                catch (IllegalArgumentException expected) { }
                var copySnapshot = Linker.nativeLinker().downcallHandle(symbols.findOrThrow("metallum_test_last_copy"), FunctionDescriptor.ofVoid(ADDRESS));
                var copyOutput = arena.allocate(128, 8);
                var copyCommand = new MTLCommandBuffer(device, "Copy adapter reuse");
                try {
                    var copies = copyCommand.copyPass(fence);
                    if (copies != copyCommand.copyPass(fence)) throw new AssertionError("Copy adapter was not reused");
                    long sourceID = buffer.nativeOwner().id(device), destinationID = indirect.nativeOwner().id(device), textureID = ptr.id(device);
                    for (int repeat = 0; repeat < 3; repeat++) {
                        copies.copyFromTextureToTexture(ptr, 1, 2, 3, 4, 5, 6, ptr, 7, 8, 9, 10);
                        checkCopy(copySnapshot, copyOutput, new long[]{3,textureID,textureID,1,2,3,4,5,6,7,8,9,10,0,0,0});
                        copies.copyFromTextureToBuffer(ptr, 2, 1, 4, 3, 6, 5, indirect, 8, 24, 120);
                        checkCopy(copySnapshot, copyOutput, new long[]{2,textureID,destinationID,2,1,4,3,6,5,8,0,0,0,24,120,0});
                        copies.copyFromBufferToTexture(buffer, 4, 24, 120, 6, 5, ptr, 2, 1, 4, 3);
                        checkCopy(copySnapshot, copyOutput, new long[]{1,sourceID,textureID,4,0,0,0,6,5,2,1,4,3,24,120,0});
                        copies.copyFromBufferToBuffer(buffer, 4, indirect, 8, 12);
                        checkCopy(copySnapshot, copyOutput, new long[]{0,sourceID,destinationID,4,0,0,0,0,0,8,0,0,0,0,0,12});
                    }
                } finally { copyCommand.close(); }
                encoder.endEncoding(); encoder.endEncoding();
                try { encoder.bindTexture(ptr, null, 0, 1, false); throw new AssertionError("Closed pass accepted"); }
                catch (IllegalStateException expected) { }
                try (var layer = new CAMetalLayer(new MTLDevice(device), 2)) {
                    layer.configure(1708, 960, false);
                    device.present(command, layer.owner(), ptr, fence.owner(), ptr, ptr, ptr);
                }
            }
            if (device.memoryStats().resources() != 0) throw new AssertionError("Adapter resources leaked");
        }
        System.out.println("Render adapters, reusable copy payloads, native draw batching and presentation ownership passed (C fixture)");
    }
}
