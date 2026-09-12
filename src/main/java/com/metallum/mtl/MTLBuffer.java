package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import com.metallum.nativebridge.NativeMetalDevice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public final class MTLBuffer implements AutoCloseable {
    private static final Msg CONTENTS = Msg.of("contents", ADDRESS);
    private static final Msg LENGTH = Msg.of("length", JAVA_LONG);

    private final MemorySegment handle;
    private final NativeMetalDevice.Buffer nativeOwner;
    private boolean closed;

    MTLBuffer(final MemorySegment handle) {
        if (handle == null || handle.address() == 0L) {
            throw new IllegalArgumentException("MTLBuffer handle is null");
        }
        this.handle = handle;
        this.nativeOwner = null;
    }

    public MTLBuffer(NativeMetalDevice.Buffer nativeOwner) {
        this.nativeOwner = nativeOwner;
        this.handle = MemorySegment.NULL;
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Metal buffer is closed");
    }

    public MemorySegment handle() {
        checkOpen();
        return nativeOwner == null ? handle : nativeOwner.borrowedBuffer();
    }

    public MemorySegment contents() {
        checkOpen();
        return nativeOwner == null ? CONTENTS.sendPtr(handle) : nativeOwner.contents();
    }

    public long length() {
        checkOpen();
        return nativeOwner == null ? LENGTH.sendLong(handle) : nativeOwner.length();
    }

    @Override
    public void close() {
        if (closed) return;
        if (nativeOwner == null) ObjC.release(handle);
        else nativeOwner.close();
        closed = true;
    }
}
