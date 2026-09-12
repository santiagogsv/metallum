package com.metallum.mtl;
import com.metallum.nativebridge.NativeMetalDevice;
public abstract class MTLCommandEncoder {
    private final NativeMetalDevice.Resource owner;
    MTLCommandEncoder(NativeMetalDevice.Resource owner) { this.owner = owner; }
    public void endEncoding() { owner.close(); }
}
