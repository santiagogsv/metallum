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

/** Render-thread-confined owner of the Swift context and its loaded library. */
public final class NativeMetalDevice implements AutoCloseable {
    private final Arena arena = Arena.ofConfined();
    private final MethodHandle destroy;
    private final MemorySegment borrowedDevice;
    private MemorySegment context = MemorySegment.NULL;
    private boolean closed;

    public NativeMetalDevice(Path library) {
        try {
            SymbolLookup symbols = SymbolLookup.libraryLookup(library.toAbsolutePath(), arena);
            Linker linker = Linker.nativeLinker();
            MethodHandle version = linker.downcallHandle(symbols.findOrThrow("metallum_abi_version"), FunctionDescriptor.of(JAVA_INT));
            if ((int) version.invokeExact() != 1) throw new IllegalStateException("Unsupported Metallum native ABI");
            MethodHandle create = linker.downcallHandle(symbols.findOrThrow("metallum_device_create"), FunctionDescriptor.of(ADDRESS));
            MethodHandle borrow = linker.downcallHandle(symbols.findOrThrow("metallum_device_borrow_mtl"), FunctionDescriptor.of(ADDRESS, ADDRESS));
            destroy = linker.downcallHandle(symbols.findOrThrow("metallum_device_destroy"), FunctionDescriptor.ofVoid(ADDRESS));
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
        if (closed) throw new IllegalStateException("Native device is closed");
        return borrowedDevice;
    }

    @Override
    public void close() {
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
