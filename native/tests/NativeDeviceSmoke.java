import java.lang.foreign.MemorySegment;
import com.metallum.nativebridge.NativeMetalDevice;
import com.metallum.nativebridge.NativePipelineDescriptor;
import java.nio.file.Path;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicReference;

public final class NativeDeviceSmoke {
    public static void main(String[] args) {
        Path library = Path.of(args[0]);
        for (int i = 0; i < 100; i++) {
            NativeMetalDevice device = new NativeMetalDevice(library);
            try (device) {
                if (args.length > 1 && !device.diagnostics().equals(new NativeMetalDevice.Diagnostics(
                        100,101,102,103,104,105,106,107,108,109,110,111,112,113,114,115,116,117))) {
                    throw new AssertionError("Diagnostics FFM field mapping changed");
                }
                if (args.length > 1) {
                    try (var command = device.createCommandBuffer("Upscale contract");
                         var source = device.createTexture(70, 4, 4, 1, 1, false, true, "source");
                         var destination = device.createTexture(70, 8, 8, 1, 1, false, true, "destination");
                         var fence = device.createFence(); var other = new NativeMetalDevice(library);
                         var foreign = other.createFence()) {
                        device.upscale(command, source, destination, fence);
                        device.clearUpscaler();
                        try { device.upscale(command, source, destination, foreign); throw new AssertionError("Foreign fence accepted"); }
                        catch (IllegalArgumentException expected) { }
                        source.close();
                        try { device.upscale(command, source, destination, fence); throw new AssertionError("Closed upscale source accepted"); }
                        catch (IllegalStateException expected) { }
                    }
                }
                if (device.borrowedDevice().address() == 0) throw new AssertionError("Null borrowed device");
                String shader = "#include <metal_stdlib>\nusing namespace metal;\nkernel void first() {}\nkernel void second() {}";
                try (var first = device.compileFunction(shader, "first"); var second = device.compileFunction(shader, "second")) {
                    device.clearShaderLibraries();
                    if (first.borrowedHandle().address() == 0 || second.borrowedHandle().address() == 0) throw new AssertionError("Function lost after cache clear");
                }
                try { device.compileFunction(shader, "missing"); throw new AssertionError("Missing entry accepted"); }
                catch (IllegalStateException expected) {
                    if (expected.getCause() == null || !expected.getCause().getMessage().contains("MSL compilation failed")) throw new AssertionError(expected);
                }
                try { device.compileFunction("invalid", "first"); throw new AssertionError("Invalid source accepted"); }
                catch (IllegalStateException expected) { }
                String renderShader = "#include <metal_stdlib>\nusing namespace metal; vertex float4 vs(uint id [[vertex_id]]) { return float4(0,0,0,1); } fragment float4 fs() { return float4(1); }";
                var vertex = device.compileFunction(renderShader, "vs");
                var fragment = device.compileFunction(renderShader, "fs");
                var description = new NativePipelineDescriptor(70, 15);
                try (var other = new NativeMetalDevice(library); var foreign = other.compileFunction(renderShader, "vs")) {
                    try { device.createPipeline(foreign, fragment, description); throw new AssertionError("Cross-device function accepted"); }
                    catch (IllegalArgumentException expected) { }
                }
                var pipeline = device.createPipeline(vertex, fragment, description);
                vertex.close(); fragment.close(); device.clearShaderLibraries();
                if (pipeline.borrowedHandle().address() == 0) throw new AssertionError("Pipeline lost after function close");
                try { device.createPipeline(vertex, fragment, description); throw new AssertionError("Closed function accepted"); }
                catch (IllegalStateException expected) { }
                pipeline.close(); pipeline.close();
                NativeMetalDevice.Resource texture = device.createTexture(70, 8, 8, 2, 4, false, true, "Texture test é");
                try (var renderCommand = device.createCommandBuffer("Render pass lifecycle")) {
                    var pass = device.createRenderPass(renderCommand, texture, null, 2, 0, new double[]{0.1,0.2,0.3,1,1});
                    if (pass.borrowedHandle().address() == 0) throw new AssertionError("Null encoder");
                    device.renderCommand(pass, 15, 0, 0,
                            0, 0, Double.doubleToRawLongBits(8), Double.doubleToRawLongBits(8), 0, Double.doubleToRawLongBits(1), 0, 0);
                    pass.close(); pass.close();
                    try { device.createRenderPass(renderCommand, null, null, 1, 1, new double[]{0,0,0,0,1}); throw new AssertionError("Empty pass accepted"); }
                    catch (IllegalStateException expected) { }
                    try (var submitted = device.submit(renderCommand)) {
                        if (!device.waitSubmission(submitted, Long.MAX_VALUE)) throw new AssertionError("Render submission timed out");
                    }
                }
                try (var layer = device.createLayer(2)) {
                    device.configureLayer(layer, 1708, 960, false);
                    device.configureLayer(layer, 2940, 1912, true);
                }
                try (var fullView = texture.createView(0, 4); var partialView = texture.createView(1, 2);
                     var sampler = device.createSampler(true, false, true, false, 16, 8.5)) {
                    if (texture.borrowedHandle().address() == 0 || sampler.borrowedHandle().address() == 0) throw new AssertionError("Null resource");
                    try { texture.createView(3, 2); throw new AssertionError("Out-of-bounds mip range accepted"); }
                    catch (IllegalStateException expected) { }
                    try { sampler.createView(0, 1); throw new AssertionError("Sampler accepted as a texture"); }
                    catch (IllegalStateException expected) { }
                    AtomicReference<Throwable> result = new AtomicReference<>();
                    Thread thread = new Thread(() -> {
                        try { sampler.close(); result.set(new AssertionError("Cross-thread resource close accepted")); }
                        catch (IllegalStateException expected) { }
                        catch (Throwable failure) { result.set(failure); }
                    });
                    thread.start();
                    try { thread.join(); } catch (InterruptedException e) { throw new AssertionError(e); }
                    if (result.get() != null) throw new AssertionError(result.get());
                    texture.close();
                    if (fullView.borrowedHandle().address() == 0 || partialView.borrowedHandle().address() == 0) throw new AssertionError("View lost after parent closed");
                }
                texture.close();
                try { texture.borrowedHandle(); throw new AssertionError("Closed texture accepted"); }
                catch (IllegalStateException expected) { }
                try { device.createTexture(70, 8, 4, 6, 1, true, false, null); throw new AssertionError("Invalid cube accepted"); }
                catch (IllegalArgumentException expected) { }
                try { device.createSampler(false, false, false, false, 17, 0); throw new AssertionError("Invalid anisotropy accepted"); }
                catch (IllegalArgumentException expected) { }
                var beforeState = device.memoryStats();
                try (var depth = device.createDepthState(3, true); var sampler = device.createPresentSampler(true)) {
                    if (depth.borrowedHandle().address() == 0 || sampler.borrowedHandle().address() == 0) throw new AssertionError("Missing state");
                }
                var afterState = device.memoryStats();
                if (beforeState.resources() != afterState.resources() || beforeState.buffers() != afterState.buffers()) throw new AssertionError("State ownership leak");
                try { device.createDepthState(8, false); throw new AssertionError("Invalid depth function accepted"); }
                catch (IllegalStateException expected) { }
                var texelBuffer = device.createBuffer(1024, true);
                try (var texel = texelBuffer.createTexture(70, 0, 16, 64)) {
                    try { texelBuffer.createTexture(70, 1020, 16, 64); throw new AssertionError("Invalid texel range accepted"); }
                    catch (IllegalArgumentException expected) { }
                    texelBuffer.close();
                    if (texel.borrowedHandle().address() == 0) throw new AssertionError("Texel view lost");
                    try { texelBuffer.createTexture(70, 0, 16, 64); throw new AssertionError("Closed buffer accepted"); }
                    catch (IllegalStateException expected) { }
                }
                NativeMetalDevice.Buffer shared = device.createBuffer(64, true);
                try (shared; var gpuOnly = device.createBuffer(64, false)) {
                    if (shared.length() != 64 || shared.borrowedBuffer().address() == 0) throw new AssertionError("Invalid shared buffer");
                    var memory = shared.contents().reinterpret(64);
                    memory.set(ValueLayout.JAVA_LONG, 0, 0x123456789L);
                    if (memory.get(ValueLayout.JAVA_LONG, 0) != 0x123456789L) throw new AssertionError("Mapping corrupted");
                    if (gpuOnly.contents().address() != 0) throw new AssertionError("Private memory exposed");
                    AtomicReference<Throwable> outcome = new AtomicReference<>();
                    Thread worker = new Thread(() -> {
                        try { shared.close(); outcome.set(new AssertionError("Cross-thread close accepted")); }
                        catch (IllegalStateException expected) { }
                        catch (Throwable failure) { outcome.set(failure); }
                    });
                    worker.start();
                    try { worker.join(); } catch (InterruptedException e) { throw new AssertionError(e); }
                    if (outcome.get() != null) throw new AssertionError(outcome.get());
                }
                shared.close();
                try { shared.contents(); throw new AssertionError("Closed buffer accepted"); }
                catch (IllegalStateException expected) { }
                try { device.createBuffer(0, true); throw new AssertionError("Zero allocation accepted"); }
                catch (IllegalArgumentException expected) { }
                {
                    var command = device.createCommandBuffer("Submission lifecycle test");
                    var submission = device.submit(command);
                    try { device.submit(command); throw new AssertionError("Double submit accepted"); }
                    catch (IllegalStateException expected) { }
                    command.close();
                    if (!device.waitSubmission(submission, 5000) || !device.waitSubmission(submission, 0)) throw new AssertionError("Submission wait not repeatable");
                    submission.close(); submission.close();
                    try { device.waitSubmission(submission, 0); throw new AssertionError("Closed submission accepted"); }
                    catch (IllegalStateException expected) { }
                }
                try (var copyCommand = device.createCommandBuffer(null); var fence = device.createFence();
                     var src = device.createBuffer(64, true); var dst = device.createBuffer(64, true)) {
                    src.contents().reinterpret(64).set(ValueLayout.JAVA_LONG, 8, 12345L);
                    long[] words = {0, src.id(device), dst.id(device), 8, 0, 0, 0, 0, 0, 16, 0, 0, 0, 0, 0, 8};
                    device.copyPass(copyCommand, fence, words);
                    var invalid = words.clone(); invalid[3] = 65;
                    try { device.copyPass(copyCommand, fence, invalid); throw new AssertionError("Invalid copy range accepted"); }
                    catch (IllegalStateException expected) { }
                    try (var submission = device.submit(copyCommand)) {
                        if (!device.waitSubmission(submission, 5000)) throw new AssertionError("Copy timeout");
                    }
                    if (dst.contents().reinterpret(64).get(ValueLayout.JAVA_LONG, 16) != 12345L) throw new AssertionError("Copy offsets/data mismatch");
                }
                var remaining = device.memoryStats();
                if (remaining.buffers() != 0 || remaining.resources() != 0) throw new AssertionError("Owned resources remain after cycle: " + remaining);
            }
            device.close(); // Java close is idempotent; the C destroy operation is not.
            try {
                device.borrowedDevice();
                throw new AssertionError("Use after close accepted");
            } catch (IllegalStateException expected) {
                // Expected.
            }
        }
        NativeMetalDevice owner = new NativeMetalDevice(library);
        var survivor = owner.createBuffer(64, true);
        var resourceSurvivor = owner.createTexture(70, 8, 8, 1, 1, false, false, null);
        owner.close();
        resourceSurvivor.close();
        try { resourceSurvivor.borrowedHandle(); throw new AssertionError("Closed device resource accepted"); }
        catch (IllegalStateException expected) { }
        survivor.close(); // Already destroyed by its device, must not call into an unloaded library.
        try { survivor.borrowedBuffer(); throw new AssertionError("Closed owner accepted"); }
        catch (IllegalStateException expected) { }
        try {
            new NativeMetalDevice(library.resolveSibling("does-not-exist.dylib"));
            throw new AssertionError("Missing library accepted");
        } catch (IllegalStateException expected) {
            // An explicitly selected but broken native path must fail, not silently fall back.
        }
        if (args.length > 1) {
            try { new NativeMetalDevice(Path.of(args[1])); throw new AssertionError("Old ABI accepted"); }
            catch (IllegalStateException expected) {
                if (expected.getCause() == null || !expected.getCause().getMessage().contains("Expected Metallum native ABI 19")) {
                    throw new AssertionError("Unexpected ABI error", expected);
                }
            }
        }
        System.out.println("Java FFM ownership smoke test passed");
    }
}
