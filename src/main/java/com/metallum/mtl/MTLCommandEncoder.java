package com.metallum.mtl;

import com.metallum.nativebridge.NativeMetalDevice;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public abstract class MTLCommandEncoder {
    private final NativeMetalDevice.Resource owner;

    MemorySegment handle;

    MTLCommandEncoder(final NativeMetalDevice.Resource owner) {
        this.owner = owner;
        this.handle = owner.borrowedHandle();
    }

    public MemorySegment handle() {
        if (ObjC.isNil(this.handle)) {
            throw new IllegalStateException(getClass().getSimpleName() + " is closed");
        }
        return this.handle;
    }

    public void endEncoding() {
        if (ObjC.isNil(this.handle)) {
            return;
        }
        owner.close(); // Swift ends encoding and releases the encoder together.
        this.handle = MemorySegment.NULL;
    }
}
