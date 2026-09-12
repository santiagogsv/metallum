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
    private final MethodHandle functionCreate, shaderLibrariesClear;
    private final MemorySegment borrowedDevice;
    private MemorySegment context = MemorySegment.NULL;
    private boolean closed;

    public NativeMetalDevice(Path library) {
        try {
            SymbolLookup symbols = SymbolLookup.libraryLookup(library.toAbsolutePath(), arena);
            Linker linker = Linker.nativeLinker();
            MethodHandle version = linker.downcallHandle(symbols.findOrThrow("metallum_abi_version"), FunctionDescriptor.of(JAVA_INT));
            int abiVersion = (int) version.invokeExact();
            if (abiVersion != 4) throw new IllegalStateException("Expected Metallum native ABI 4, found " + abiVersion);
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
