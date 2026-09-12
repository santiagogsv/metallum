package com.metallum.mtl;
import com.metallum.nativebridge.NativeMetalDevice;
import java.lang.foreign.MemorySegment;
public record MTLFence(NativeMetalDevice.Resource owner) implements AutoCloseable {
    public MemorySegment handle() { return owner.borrowedHandle(); }
    public void close() { owner.close(); }
}
