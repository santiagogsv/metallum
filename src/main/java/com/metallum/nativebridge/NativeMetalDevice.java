package com.metallum.nativebridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;

/** Render-thread-confined owner of the Swift context and its loaded library. */
public final class NativeMetalDevice implements AutoCloseable {
    private final Arena arena = Arena.ofConfined();
    private final Thread ownerThread = Thread.currentThread();
    private final MethodHandle destroy;
    private final MethodHandle bufferCreate, bufferBorrow, bufferContents, bufferDestroy;
    private final MethodHandle textureCreate, textureViewCreate, samplerCreate, resourceBorrow, resourceDestroy;
    private final MethodHandle functionCreate, shaderLibrariesClear, pipelineCreate;
    private final MethodHandle depthCreate, presentSamplerCreate, bufferTextureCreate, memorySnapshot, diagnosticsSnapshot, submit, submissionWait, commandCreate, fenceCreate, copyPass, renderPassCreate, renderCommand, layerCreate, layerConfigure, present, renderBytes, textureInfo, commandDebug, deviceInfo, deviceName;
    // Reused only on the confined render thread; calls consume the words synchronously.
    private final MemorySegment drawWords = arena.allocate(64, 8);
    private final MethodHandle deviceBorrow;
    private MemorySegment context = MemorySegment.NULL;
    private boolean closed;

    public NativeMetalDevice(Path library) {
        try {
            SymbolLookup symbols = SymbolLookup.libraryLookup(library.toAbsolutePath(), arena);
            Linker linker = Linker.nativeLinker();
            MethodHandle version = linker.downcallHandle(symbols.findOrThrow("metallum_abi_version"), FunctionDescriptor.of(JAVA_INT));
            int abiVersion = (int) version.invokeExact();
            if (abiVersion != 15) throw new IllegalStateException("Expected Metallum native ABI 15, found " + abiVersion);
            MethodHandle create = linker.downcallHandle(symbols.findOrThrow("metallum_device_create"), FunctionDescriptor.of(ADDRESS));
            deviceBorrow = linker.downcallHandle(symbols.findOrThrow("metallum_device_borrow_mtl"), FunctionDescriptor.of(ADDRESS, ADDRESS));
            destroy = linker.downcallHandle(symbols.findOrThrow("metallum_device_destroy"), FunctionDescriptor.ofVoid(ADDRESS));
            bufferCreate = linker.downcallHandle(symbols.findOrThrow("metallum_buffer_create"), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));
            bufferBorrow = linker.downcallHandle(symbols.findOrThrow("metallum_buffer_borrow_mtl"), FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
            bufferContents = linker.downcallHandle(symbols.findOrThrow("metallum_buffer_contents"), FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
            bufferDestroy = linker.downcallHandle(symbols.findOrThrow("metallum_buffer_destroy"), FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
            textureCreate = linker.downcallHandle(symbols.findOrThrow("metallum_texture_create"), FunctionDescriptor.of(JAVA_LONG,
                    ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
            textureViewCreate = linker.downcallHandle(symbols.findOrThrow("metallum_texture_view_create"), FunctionDescriptor.of(JAVA_LONG,
                    ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT));
            samplerCreate = linker.downcallHandle(symbols.findOrThrow("metallum_sampler_create"), FunctionDescriptor.of(JAVA_LONG,
                    ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_DOUBLE));
            resourceBorrow = linker.downcallHandle(symbols.findOrThrow("metallum_resource_borrow_mtl"), FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
            resourceDestroy = linker.downcallHandle(symbols.findOrThrow("metallum_resource_destroy"), FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
            functionCreate = linker.downcallHandle(symbols.findOrThrow("metallum_function_create"), FunctionDescriptor.of(JAVA_LONG,
                    ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
            shaderLibrariesClear = linker.downcallHandle(symbols.findOrThrow("metallum_shader_libraries_clear"), FunctionDescriptor.ofVoid(ADDRESS));
            pipelineCreate = linker.downcallHandle(symbols.findOrThrow("metallum_pipeline_create"), FunctionDescriptor.of(JAVA_LONG,
                    ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
            depthCreate = linker.downcallHandle(symbols.findOrThrow("metallum_depth_state_create"), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));
            presentSamplerCreate = linker.downcallHandle(symbols.findOrThrow("metallum_present_sampler_create"), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
            bufferTextureCreate = linker.downcallHandle(symbols.findOrThrow("metallum_buffer_texture_create"), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
            diagnosticsSnapshot = linker.downcallHandle(symbols.findOrThrow("metallum_diagnostics_snapshot"), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
            memorySnapshot = linker.downcallHandle(symbols.findOrThrow("metallum_memory_snapshot"), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
            submit = linker.downcallHandle(symbols.findOrThrow("metallum_submit"), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
            submissionWait = linker.downcallHandle(symbols.findOrThrow("metallum_submission_wait"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));
            commandCreate = linker.downcallHandle(symbols.findOrThrow("metallum_command_buffer_create"), FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));
            fenceCreate = linker.downcallHandle(symbols.findOrThrow("metallum_fence_create"), FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            layerCreate = linker.downcallHandle(symbols.findOrThrow("metallum_layer_create"), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_DOUBLE));
            layerConfigure = linker.downcallHandle(symbols.findOrThrow("metallum_layer_configure"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT));
            present = linker.downcallHandle(symbols.findOrThrow("metallum_present"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
            deviceInfo = linker.downcallHandle(symbols.findOrThrow("metallum_device_info"), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
            deviceName = linker.downcallHandle(symbols.findOrThrow("metallum_device_name"), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
            renderBytes = linker.downcallHandle(symbols.findOrThrow("metallum_render_bytes"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG));
            textureInfo = linker.downcallHandle(symbols.findOrThrow("metallum_texture_info"), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));
            commandDebug = linker.downcallHandle(symbols.findOrThrow("metallum_command_debug"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
            renderCommand = linker.downcallHandle(symbols.findOrThrow("metallum_render_command"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS));
            renderPassCreate = linker.downcallHandle(symbols.findOrThrow("metallum_render_pass_create"), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS));
            copyPass = linker.downcallHandle(symbols.findOrThrow("metallum_copy_pass"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
            context = (MemorySegment) create.invokeExact();
            if (context.address() == 0) throw new IllegalStateException("Swift could not create a Metal device");
        } catch (Throwable failure) {
            arena.close();
            throw new IllegalStateException("Cannot initialize Swift Metal bridge: " + library, failure);
        }
    }

    /** Diagnostic-only borrowed pointer. Rendering uses IDs. Do not release it. */
    public MemorySegment borrowedDevice() {
        checkOpen();
        try { return (MemorySegment) deviceBorrow.invokeExact(context); }
        catch (Throwable failure) { throw new IllegalStateException("Cannot inspect native device", failure); }
    }

    private void checkThread() {
        if (Thread.currentThread() != ownerThread) throw new IllegalStateException("Native device belongs to the render thread");
    }

    private void checkOpen() {
        checkThread();
        if (closed) throw new IllegalStateException("Native device is closed");
    }

    public Buffer createBuffer(long length, boolean cpuAccessible) {
        checkOpen();
        if (length <= 0) throw new IllegalArgumentException("Buffer length must be positive");
        try {
            long id = (long) bufferCreate.invokeExact(context, length, cpuAccessible ? 1 : 0);
            if (id == 0) throw new IllegalStateException("Swift Metal buffer allocation failed: " + length + " bytes");
            return new Buffer(id, length);
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot create Swift Metal buffer", failure);
        }
    }

    public Resource createTexture(long pixelFormat, int width, int height, int layers, int mips,
                                  boolean cube, boolean renderTarget, String label) {
        checkOpen();
        if (width <= 0 || height <= 0 || layers <= 0 || mips <= 0) throw new IllegalArgumentException("Invalid texture dimensions");
        if (cube && (layers % 6 != 0 || width != height)) throw new IllegalArgumentException("Invalid cube texture dimensions");
        try (Arena strings = Arena.ofConfined()) {
            MemorySegment name = label == null ? MemorySegment.NULL : strings.allocateFrom(label);
            long id = (long) textureCreate.invokeExact(context, pixelFormat, width, height, layers, mips,
                    cube ? 1 : 0, renderTarget ? 1 : 0, name);
            return ownResource(id);
        } catch (Throwable failure) { throw new IllegalStateException("Cannot create Swift Metal texture", failure); }
    }

    public Resource createSampler(boolean repeatU, boolean repeatV, boolean linearMin, boolean linearMag, int anisotropy, double maxLod) {
        checkOpen();
        if (anisotropy < 1 || anisotropy > 16) throw new IllegalArgumentException("Anisotropy must be between 1 and 16");
        try {
            long id = (long) samplerCreate.invokeExact(context, repeatU ? 1 : 0, repeatV ? 1 : 0,
                    linearMin ? 1 : 0, linearMag ? 1 : 0, anisotropy, maxLod);
            return ownResource(id);
        } catch (Throwable failure) { throw new IllegalStateException("Cannot create Swift Metal sampler", failure); }
    }

    public Resource compileFunction(String source, String entryPoint) {
        checkOpen();
        if (source == null || source.isEmpty() || entryPoint == null || entryPoint.isEmpty()) {
            throw new IllegalArgumentException("Shader source and entry point must be nonempty");
        }
        try (Arena strings = Arena.ofConfined()) {
            MemorySegment msl = strings.allocateFrom(source);
            MemorySegment name = strings.allocateFrom(entryPoint);
            MemorySegment error = strings.allocate(4096);
            long id = (long) functionCreate.invokeExact(context, msl, name, error, 4096);
            if (id == 0) throw new IllegalStateException("MSL compilation failed for " + entryPoint + ": " + error.getString(0));
            return ownResource(id);
        } catch (Throwable failure) { throw new IllegalStateException("Cannot compile Swift Metal function: " + entryPoint + ": " + failure.getMessage(), failure); }
    }

    public void clearShaderLibraries() {
        checkOpen();
        try { shaderLibrariesClear.invokeExact(context); }
        catch (Throwable failure) { throw new IllegalStateException("Cannot clear Swift shader libraries", failure); }
    }

    public record MemoryStats(long buffers, long resources, long libraries, long bufferBytes, long metalBytes) {}

    public MemoryStats memoryStats() {
        checkOpen();
        try (Arena call = Arena.ofConfined()) {
            var output = call.allocate(JAVA_LONG, 5);
            memorySnapshot.invokeExact(context, output);
            return new MemoryStats(output.getAtIndex(JAVA_LONG, 0), output.getAtIndex(JAVA_LONG, 1),
                    output.getAtIndex(JAVA_LONG, 2), output.getAtIndex(JAVA_LONG, 3), output.getAtIndex(JAVA_LONG, 4));
        } catch (Throwable failure) { throw new IllegalStateException("Cannot inspect Metal memory", failure); }
    }

    public record Diagnostics(long completed, long timed, long gpuTotalNs, long gpuMaxNs, long cpuWaitNs,
                              long bindingWrites, long bindingSkips, long activeSlots, long idleSlots,
                              long stagingBytes, long allocatorBytes, long heldReferences,
                              long activeResidency, long idleResidency) {}

    /** Drains interval counters; memory values are current snapshots. Does not wait for GPU work. */
    public Diagnostics diagnostics() {
        checkOpen();
        try (Arena call = Arena.ofConfined()) {
            var out = call.allocate(JAVA_LONG, 14);
            diagnosticsSnapshot.invokeExact(context, out);
            return new Diagnostics(out.getAtIndex(JAVA_LONG, 0), out.getAtIndex(JAVA_LONG, 1),
                    out.getAtIndex(JAVA_LONG, 2), out.getAtIndex(JAVA_LONG, 3), out.getAtIndex(JAVA_LONG, 4),
                    out.getAtIndex(JAVA_LONG, 5), out.getAtIndex(JAVA_LONG, 6), out.getAtIndex(JAVA_LONG, 7),
                    out.getAtIndex(JAVA_LONG, 8), out.getAtIndex(JAVA_LONG, 9), out.getAtIndex(JAVA_LONG, 10),
                    out.getAtIndex(JAVA_LONG, 11), out.getAtIndex(JAVA_LONG, 12), out.getAtIndex(JAVA_LONG, 13));
        } catch (Throwable failure) { throw new IllegalStateException("Cannot inspect Metal diagnostics", failure); }
    }

    public long deviceInfo(int field) {
        checkOpen();
        try { return (long) deviceInfo.invokeExact(context, field); }
        catch (Throwable failure) { throw new IllegalStateException("Cannot inspect device", failure); }
    }
    public String deviceName() {
        checkOpen();
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment name = scratch.allocate(1024);
            deviceName.invokeExact(context, name, 1024);
            return name.getString(0);
        } catch (Throwable failure) { throw new IllegalStateException("Cannot read device name", failure); }
    }
    public void renderBytes(Resource pass, MemorySegment bytes, long length, long index) {
        long id = pass.id(this);
        if (length < 0 || length > 4096 || index < 0) throw new IllegalArgumentException("Invalid inline data");
        try {
            if ((int) renderBytes.invokeExact(context, id, bytes, length, index) != 1) throw new IllegalArgumentException("Invalid render pass");
        } catch (Throwable failure) { throw new IllegalStateException("Cannot encode inline data", failure); }
    }
    public void commandDebug(Resource command, String label) {
        long id = command.id(this);
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment text = label == null ? MemorySegment.NULL : scratch.allocateFrom(label);
            if ((int) commandDebug.invokeExact(context, id, text) != 1) throw new IllegalArgumentException("Invalid command");
        } catch (Throwable failure) { throw new IllegalStateException("Cannot label Metal command", failure); }
    }
    public Resource createLayer(double scale) {
        checkOpen();
        try { return ownResource((long) layerCreate.invokeExact(context, scale)); }
        catch (Throwable failure) { throw new IllegalStateException("Cannot create Metal layer", failure); }
    }

    public void configureLayer(Resource layer, double width, double height, boolean immediate) {
        long id = layer.id(this);
        try {
            if ((int) layerConfigure.invokeExact(context, id, width, height, immediate ? 1 : 0) != 1)
                throw new IllegalArgumentException("Invalid Metal layer configuration");
        } catch (Throwable failure) { throw new IllegalStateException("Cannot configure Metal layer", failure); }
    }

    public void present(Resource command, Resource layer, Resource source, Resource fence,
                        Resource pipeline, Resource nearest, Resource linear) {
        long commandID = command.id(this), layerID = layer.id(this), fenceID = fence == null ? 0 : fence.id(this);
        try {
            if ((int) present.invokeExact(context, commandID, layerID, source.id(this), fenceID, pipeline.id(this), nearest.id(this), linear.id(this)) != 1)
                throw new IllegalStateException("Metal presentation rejected");
        } catch (Throwable failure) { throw new IllegalStateException("Cannot present Metal frame", failure); }
    }

    public void renderCommand(Resource pass, int op, long p0, long p1,
                              long a, long b, long c, long d, long e, long f, long g, long h) {
        long id = pass.id(this);
        drawWords.setAtIndex(JAVA_LONG, 0, a); drawWords.setAtIndex(JAVA_LONG, 1, b);
        drawWords.setAtIndex(JAVA_LONG, 2, c); drawWords.setAtIndex(JAVA_LONG, 3, d);
        drawWords.setAtIndex(JAVA_LONG, 4, e); drawWords.setAtIndex(JAVA_LONG, 5, f);
        drawWords.setAtIndex(JAVA_LONG, 6, g); drawWords.setAtIndex(JAVA_LONG, 7, h);
        try {
            if ((int) renderCommand.invokeExact(context, id, op, p0, p1, drawWords) != 1)
                throw new IllegalArgumentException("Rejected Metal render operation " + op);
        } catch (Throwable failure) { throw new IllegalStateException("Cannot encode Metal render operation " + op, failure); }
    }

    public Resource createRenderPass(Resource command, Resource color, Resource depth,
                                     int colorLoad, int depthLoad, double[] clear) {
        checkOpen();
        long commandID = command.id(this);
        if (clear.length != 5) throw new IllegalArgumentException("Expected five clear values");
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment values = scratch.allocateFrom(JAVA_DOUBLE, clear);
            return ownResource((long) renderPassCreate.invokeExact(context, commandID, color == null ? 0L : color.id(this), depth == null ? 0L : depth.id(this), colorLoad, depthLoad, values));
        } catch (Throwable failure) { throw new IllegalStateException("Cannot create Metal render pass", failure); }
    }

    public Resource createFence() {
        checkOpen();
        try { return ownResource((long) fenceCreate.invokeExact(context)); }
        catch (Throwable failure) { throw new IllegalStateException("Cannot create Metal fence", failure); }
    }
    public void copyPass(Resource command, Resource fence, long[] words) {
        checkOpen(); command.checkResource(); fence.checkResource();
        if (command.owner() != this || fence.owner() != this || words.length != 16) throw new IllegalArgumentException("Invalid copy owner or payload");
        try (Arena call = Arena.ofConfined()) {
            var payload = call.allocateFrom(JAVA_LONG, words);
            var error = call.allocate(4096);
            int success = (int) copyPass.invokeExact(context, command.id, fence.id, payload, words.length, error, 4096);
            if (success == 0) throw new IllegalStateException(error.getString(0));
        } catch (Throwable failure) { throw new IllegalStateException("Metal copy failed", failure); }
    }

    public Resource createCommandBuffer(String label) {
        checkOpen();
        try (Arena call = Arena.ofConfined()) {
            var name = label == null ? MemorySegment.NULL : call.allocateFrom(label);
            return ownResource((long) commandCreate.invokeExact(context, name));
        } catch (Throwable failure) { throw new IllegalStateException("Cannot create native command buffer", failure); }
    }

    public Resource submit(Resource command) {
        command.checkResource();
        if (command.owner() != this) throw new IllegalArgumentException("Command belongs to another device");
        try { return ownResource((long) submit.invokeExact(context, command.id)); }
        catch (Throwable failure) { throw new IllegalStateException("Cannot submit Metal command buffer", failure); }
    }

    public boolean waitSubmission(Resource submission, long timeoutMs) {
        submission.checkResource();
        if (submission.owner() != this) throw new IllegalArgumentException("Submission belongs to another device");
        try (Arena call = Arena.ofConfined()) {
            var error = call.allocate(4096);
            int result = (int) submissionWait.invokeExact(context, submission.id, timeoutMs, error, 4096);
            if (result < 0) throw new IllegalStateException(error.getString(0));
            return result == 1;
        } catch (Throwable failure) { throw new IllegalStateException("Metal submission wait failed", failure); }
    }

    public Resource createDepthState(long compare, boolean write) {
        checkOpen();
        try { return ownResource((long) depthCreate.invokeExact(context, compare, write ? 1 : 0)); }
        catch (Throwable failure) { throw new IllegalStateException("Cannot create Metal depth state", failure); }
    }

    public Resource createPresentSampler(boolean linear) {
        checkOpen();
        try { return ownResource((long) presentSamplerCreate.invokeExact(context, linear ? 1 : 0)); }
        catch (Throwable failure) { throw new IllegalStateException("Cannot create Metal presentation sampler", failure); }
    }

    public Resource createPipeline(Resource vertex, Resource fragment, NativePipelineDescriptor descriptor) {
        checkOpen();
        vertex.checkResource(); fragment.checkResource();
        if (vertex.owner() != this || fragment.owner() != this) throw new IllegalArgumentException("Shader belongs to another device");
        long[] words = descriptor.words();
        try (Arena call = Arena.ofConfined()) {
            MemorySegment payload = call.allocateFrom(JAVA_LONG, words);
            MemorySegment error = call.allocate(4096);
            long id = (long) pipelineCreate.invokeExact(context, vertex.id, fragment.id, payload, words.length, error, 4096);
            if (id == 0) throw new IllegalStateException("Metal pipeline compilation failed: " + error.getString(0));
            return ownResource(id);
        } catch (Throwable failure) { throw new IllegalStateException("Cannot create Swift Metal pipeline: " + failure.getMessage(), failure); }
    }

    private Resource ownResource(long id) {
        if (id == 0) throw new IllegalStateException("Swift Metal resource creation failed");
        return new Resource(id);
    }

    /** An owning device-local resource ID. Rendering never exposes a Metal object pointer. */
    public final class Resource implements AutoCloseable {
        private final long id;
        private boolean released;

        private NativeMetalDevice owner() { return NativeMetalDevice.this; }

        public long id(NativeMetalDevice expectedOwner) { checkResource(); if (owner() != expectedOwner) throw new IllegalArgumentException("Resource belongs to another device"); return id; }

        private Resource(long id) { this.id = id; }

        private void checkResource() {
            checkOpen();
            if (released) throw new IllegalStateException("Native resource is closed");
        }

        public MemorySegment borrowedHandle() {
            checkResource();
            try { return (MemorySegment) resourceBorrow.invokeExact(context, id); }
            catch (Throwable failure) { throw new IllegalStateException("Cannot borrow platform object", failure); }
        }

        public long textureInfo(int field) {
            checkResource();
            try { return (long) textureInfo.invokeExact(context, id, field); }
            catch (Throwable failure) { throw new IllegalStateException("Cannot inspect texture", failure); }
        }
        public Resource createView(int baseMip, int mipCount) {
            checkResource();
            if (baseMip < 0 || mipCount <= 0) throw new IllegalArgumentException("Invalid mip range");
            try {
                long view = (long) textureViewCreate.invokeExact(context, id, baseMip, mipCount);
                return ownResource(view);
            } catch (Throwable failure) { throw new IllegalStateException("Cannot create Swift Metal texture view", failure); }
        }

        @Override
        public void close() {
            checkThread();
            if (released) return;
            if (!closed) {
                try { resourceDestroy.invokeExact(context, id); }
                catch (Throwable failure) { throw new IllegalStateException("Cannot destroy Swift Metal resource", failure); }
            }
            released = true;
        }
    }

    public final class Buffer implements AutoCloseable {
        private final long id;
        private final long length;
        private boolean released;

        private Buffer(long id, long length) {
            this.id = id;
            this.length = length;
        }

        private void checkBuffer() {
            checkOpen();
            if (released) throw new IllegalStateException("Native buffer is closed");
        }

        public Resource createTexture(long format, long offset, long width, long byteLength) {
            checkBuffer();
            if (offset < 0 || byteLength <= 0 || offset > length || byteLength > length - offset || width <= 0)
                throw new IllegalArgumentException("Invalid texel buffer range");
            try { return ownResource((long) bufferTextureCreate.invokeExact(context, id, format, offset, width, byteLength)); }
            catch (Throwable failure) { throw new IllegalStateException("Cannot create Metal texel buffer view", failure); }
        }

        public long id(NativeMetalDevice expectedOwner) { checkBuffer(); if (NativeMetalDevice.this != expectedOwner) throw new IllegalArgumentException("Buffer belongs to another device"); return id; }

        public long length() { checkBuffer(); return length; }

        public MemorySegment borrowedBuffer() {
            checkBuffer();
            try { return (MemorySegment) bufferBorrow.invokeExact(context, id); }
            catch (Throwable failure) { throw new IllegalStateException("Cannot inspect native buffer", failure); }
        }

        public MemorySegment contents() {
            checkBuffer();
            try { return (MemorySegment) bufferContents.invokeExact(context, id); }
            catch (Throwable failure) { throw new IllegalStateException("Cannot access Metal buffer contents", failure); }
        }

        @Override
        public void close() {
            checkThread();
            if (released) return;
            if (!closed) {
                try { bufferDestroy.invokeExact(context, id); }
                catch (Throwable failure) { throw new IllegalStateException("Cannot destroy Metal buffer", failure); }
            }
            released = true;
        }
    }

    @Override
    public void close() {
        checkThread();
        if (closed) return;
        try {
            destroy.invokeExact(context);
            closed = true;
            context = MemorySegment.NULL;
            arena.close();
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot destroy Swift Metal device", failure);
        }
    }
}
