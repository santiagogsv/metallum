package com.metallum.mtl;

import com.metallum.nativebridge.NativeMetalDevice;

/** Owns a shader function while pipelines are being created or cached. */
public final class MTLFunction implements AutoCloseable {
    private final NativeMetalDevice.Resource nativeOwner;
    private boolean closed;

    MTLFunction(NativeMetalDevice.Resource owner) { this.nativeOwner = owner; }

    public NativeMetalDevice.Resource nativeResource() {
        if (closed) throw new IllegalStateException("Metal function is closed");
        return nativeOwner;
    }

    @Override
    public void close() {
        if (closed) return;
        nativeOwner.close();
        closed = true;
    }
}
