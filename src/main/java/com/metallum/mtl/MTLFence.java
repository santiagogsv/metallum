package com.metallum.mtl;
import com.metallum.nativebridge.NativeMetalDevice;
public record MTLFence(NativeMetalDevice.Resource owner) implements AutoCloseable {
    public void close() { owner.close(); }
}
