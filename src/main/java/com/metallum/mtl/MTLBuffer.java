package com.metallum.mtl;

import com.metallum.nativebridge.NativeMetalDevice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MTLBuffer implements AutoCloseable {

    private final NativeMetalDevice.Buffer nativeOwner;
    private boolean closed;

    public MTLBuffer(NativeMetalDevice.Buffer nativeOwner) {
        this.nativeOwner = nativeOwner;
    }

    public NativeMetalDevice.Buffer nativeOwner() { checkOpen(); return nativeOwner; }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Metal buffer is closed");
    }

    public MemorySegment handle() {
        checkOpen();
        return nativeOwner.borrowedBuffer();
    }

    public MemorySegment contents() {
        checkOpen();
        return nativeOwner.contents();
    }

    public long length() {
        checkOpen();
        return nativeOwner.length();
    }

    @Override
    public void close() {
        if (closed) return;
        nativeOwner.close();
        closed = true;
    }
}
