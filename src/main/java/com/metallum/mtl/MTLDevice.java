package com.metallum.mtl;
import com.metallum.nativebridge.NativeMetalDevice;
public record MTLDevice(NativeMetalDevice nativeOwner) {
    public String name() { return nativeOwner.deviceName(); }
    public long maxBufferLength() { return nativeOwner.deviceInfo(0); }
    public long recommendedMaxWorkingSetSize() { return nativeOwner.deviceInfo(1); }
    public MTLFence newFence() { return new MTLFence(nativeOwner.createFence()); }
    public MTLFunction newFunction(String source, String entry) { return new MTLFunction(nativeOwner.compileFunction(source, entry)); }
}
