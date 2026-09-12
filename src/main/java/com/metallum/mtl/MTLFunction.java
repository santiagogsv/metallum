package com.metallum.mtl;

import com.metallum.nativebridge.NativeMetalDevice;
import com.metallum.objc.ObjC;
import java.lang.foreign.MemorySegment;

/** Owns a shader function while pipelines are being created or cached. */
public final class MTLFunction implements AutoCloseable {
    private final MemorySegment handle;
    private final NativeMetalDevice.Resource nativeOwner;
    private boolean closed;

    MTLFunction(MemorySegment handle) { this.handle = handle; this.nativeOwner = null; }
    MTLFunction(NativeMetalDevice.Resource owner) { this.nativeOwner = owner; this.handle = owner.borrowedHandle(); }

    public MemorySegment handle() {
        if (closed) throw new IllegalStateException("Metal function is closed");
        return nativeOwner == null ? handle : nativeOwner.borrowedHandle();
    }

    @Override
    public void close() {
        if (closed) return;
        if (nativeOwner != null) nativeOwner.close();
        else if (!ObjC.isNil(handle)) ObjC.release(handle);
        closed = true;
    }
}
