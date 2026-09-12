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
    private final MethodHandle depthCreate, presentSamplerCreate, bufferTextureCreate, memorySnapshot, submit, submissionWait, commandCreate, fenceCreate, copyPass;
    private final MemorySegment borrowedDevice;
    private MemorySegment context = MemorySegment.NULL;
    private boolean closed;

    public NativeMetalDevice(Path library) {
        try {
            SymbolLookup symbols = SymbolLookup.libraryLookup(library.toAbsolutePath(), arena);
            Linker linker = Linker.nativeLinker();
            MethodHandle version = linker.downcallHandle(symbols.findOrThrow("metallum_abi_version"), FunctionDescriptor.of(JAVA_INT));
            int abiVersion = (int) version.invokeExact();
            if (abiVersion != 10) throw new IllegalStateException("Expected Metallum native ABI 10, found " + abiVersion);
            MethodHandle create = linker.downcallHandle(symbols.findOrThrow("metallum_device_create"), FunctionDescriptor.of(ADDRESS));
            MethodHandle borrow = linker.downcallHandle(symbols.findOrThrow("metallum_device_borrow_mtl"), FunctionDescriptor.of(ADDRESS, ADDRESS));
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
            memorySnapshot = linker.downcallHandle(symbols.findOrThrow("metallum_memory_snapshot"), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
            submit = linker.downcallHandle(symbols.findOrThrow("metallum_submit"), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
            submissionWait = linker.downcallHandle(symbols.findOrThrow("metallum_submission_wait"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));
            commandCreate = linker.downcallHandle(symbols.findOrThrow("metallum_command_buffer_create"), FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));
            fenceCreate = linker.downcallHandle(symbols.findOrThrow("metallum_fence_create"), FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            copyPass = linker.downcallHandle(symbols.findOrThrow("metallum_copy_pass"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
            context = (MemorySegment) create.invokeExact();
            if (context.address() == 0) throw new IllegalStateException("Swift could not create a Metal device");
            try {
                borrowedDevice = (MemorySegment) borrow.invokeExact(context);
                if (borrowedDevice.address() == 0) throw new IllegalStateException("Swift returned a null Metal device");
            } catch (Throwable failure) {
                destroy.invokeExact(context);
                throw failure;
            }
        } catch (Throwable failure) {
            arena.close();
            throw new IllegalStateException("Cannot initialize Swift Metal bridge: " + library, failure);
        }
    }

    /** Migration-only borrowed pointer. Do not retain ownership or release it. */
    public MemorySegment borrowedDevice() {
        checkOpen();
        return borrowedDevice;
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
            try {
                MemorySegment borrowed = (MemorySegment) bufferBorrow.invokeExact(context, id);
                if (borrowed.address() == 0) throw new IllegalStateException("Native buffer is missing");
                return new Buffer(id, length, borrowed);
            } catch (Throwable failure) {
                bufferDestroy.invokeExact(context, id);
                throw failure;
            }
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

    private Resource ownResource(long id) throws Throwable {
        if (id == 0) throw new IllegalStateException("Swift Metal resource creation failed");
        try {
            MemorySegment borrowed = (MemorySegment) resourceBorrow.invokeExact(context, id);
            if (borrowed.address() == 0) throw new IllegalStateException("Native resource is missing");
            return new Resource(id, borrowed);
        } catch (Throwable failure) {
            resourceDestroy.invokeExact(context, id);
            throw failure;
        }
    }

    /** An owning resource ID with a cached, temporary pointer for the Java command encoder. */
    public final class Resource implements AutoCloseable {
        private final long id;
        private final MemorySegment borrowed;
        private boolean released;

        private NativeMetalDevice owner() { return NativeMetalDevice.this; }

        public long id(NativeMetalDevice expectedOwner) { checkResource(); if (owner() != expectedOwner) throw new IllegalArgumentException("Resource belongs to another device"); return id; }

        private Resource(long id, MemorySegment borrowed) { this.id = id; this.borrowed = borrowed; }

        private void checkResource() {
            checkOpen();
            if (released) throw new IllegalStateException("Native resource is closed");
        }

        public MemorySegment borrowedHandle() { checkResource(); return borrowed; }

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
        private final MemorySegment borrowed;
        private boolean released;

        private Buffer(long id, long length, MemorySegment borrowed) {
            this.id = id;
            this.length = length;
            this.borrowed = borrowed;
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
            return borrowed;
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
