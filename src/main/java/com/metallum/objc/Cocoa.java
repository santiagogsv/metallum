package com.metallum.objc;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

/** Minimal GLFW/AppKit attachment seam. Metal resources and rendering live in Swift. */
public final class Cocoa {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup RUNTIME = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
    private static final MethodHandle REGISTER = LINKER.downcallHandle(RUNTIME.findOrThrow("sel_registerName"), FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MemorySegment SCALE = selector("backingScaleFactor");
    private static final MemorySegment WANTS_LAYER = selector("setWantsLayer:");
    private static final MemorySegment LAYER = selector("setLayer:");
    private static final MethodHandle READ_SCALE = LINKER.downcallHandle(RUNTIME.findOrThrow("objc_msgSend"), FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, ADDRESS));
    private static final MethodHandle SET_WANTS_LAYER = LINKER.downcallHandle(RUNTIME.findOrThrow("objc_msgSend"), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_BOOLEAN));
    private static final MethodHandle SET_LAYER = LINKER.downcallHandle(RUNTIME.findOrThrow("objc_msgSend"), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    private final MemorySegment window, view;

    private static MemorySegment selector(String name) {
        try (Arena arena = Arena.ofConfined()) { return (MemorySegment) REGISTER.invokeExact(arena.allocateFrom(name)); }
        catch (Throwable failure) { throw new ExceptionInInitializerError(failure); }
    }
    public Cocoa(MemorySegment window, MemorySegment view) {
        if (window == null || window.address() == 0 || view == null || view.address() == 0)
            throw new IllegalStateException("GLFW Cocoa window/view is null");
        this.window = window; this.view = view;
    }
    public double backingScaleFactor() {
        try { double scale = (double) READ_SCALE.invokeExact(window, SCALE); return scale > 0 ? scale : 1; }
        catch (Throwable failure) { throw new IllegalStateException("Cannot read window scale", failure); }
    }
    public void setViewLayer(MemorySegment layer) {
        try {
            SET_WANTS_LAYER.invokeExact(view, WANTS_LAYER, true);
            SET_LAYER.invokeExact(view, LAYER, layer);
        } catch (Throwable failure) { throw new IllegalStateException("Cannot attach Metal layer", failure); }
    }
    public void clearViewLayer() {
        try {
            SET_LAYER.invokeExact(view, LAYER, MemorySegment.NULL);
            SET_WANTS_LAYER.invokeExact(view, WANTS_LAYER, false);
        } catch (Throwable failure) { throw new IllegalStateException("Cannot detach Metal layer", failure); }
    }
}
