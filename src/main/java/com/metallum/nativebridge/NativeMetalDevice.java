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

/** Render-thread-confined owner of the Swift context and its loaded library. */
public final class NativeMetalDevice implements AutoCloseable {
    private final Arena arena = Arena.ofConfined();
    private final Thread ownerThread = Thread.currentThread();
    private final MethodHandle destroy;
    private final MethodHandle bufferCreate, bufferBorrow, bufferContents, bufferDestroy;
    private final MemorySegment borrowedDevice;
    private MemorySegment context = MemorySegment.NULL;
    private boolean closed;

    public NativeMetalDevice(Path library) {
        try {
            SymbolLookup symbols = SymbolLookup.libraryLookup(library.toAbsolutePath(), arena);
            Linker linker = Linker.nativeLinker();
            MethodHandle version = linker.downcallHandle(symbols.findOrThrow("metallum_abi_version"), FunctionDescriptor.of(JAVA_INT));
            int abiVersion = (int) version.invokeExact();
            if (abiVersion != 2) throw new IllegalStateException("Expected Metallum native ABI 2, found " + abiVersion);
            MethodHandle create = linker.downcallHandle(symbols.findOrThrow("metallum_device_create"), FunctionDescriptor.of(ADDRESS));
            MethodHandle borrow = linker.downcallHandle(symbols.findOrThrow("metallum_device_borrow_mtl"), FunctionDescriptor.of(ADDRESS, ADDRESS));
            destroy = linker.downcallHandle(symbols.findOrThrow("metallum_device_destroy"), FunctionDescriptor.ofVoid(ADDRESS));
            bufferCreate = linker.downcallHandle(symbols.findOrThrow("metallum_buffer_create"), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));
            bufferBorrow = linker.downcallHandle(symbols.findOrThrow("metallum_buffer_borrow_mtl"), FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
            bufferContents = linker.downcallHandle(symbols.findOrThrow("metallum_buffer_contents"), FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
            bufferDestroy = linker.downcallHandle(symbols.findOrThrow("metallum_buffer_destroy"), FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
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
