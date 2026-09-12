package com.metallum.mtl;

import com.metallum.nativebridge.NativeMetalDevice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.lang.foreign.MemorySegment;

/** Minecraft surface adapter. Swift owns and configures the actual layer. */
@Environment(EnvType.CLIENT)
public final class CAMetalLayer implements AutoCloseable {
    private final NativeMetalDevice device;
    private final NativeMetalDevice.Resource owner;

    public CAMetalLayer(MTLDevice device, double contentsScale) {
        this.device = device.nativeOwner();
        this.owner = this.device.createLayer(contentsScale);
    }
    public MemorySegment handle() { return owner.borrowedHandle(); }
    NativeMetalDevice.Resource owner() { return owner; }
    public void configure(double width, double height, boolean immediatePresentMode) {
        device.configureLayer(owner, width, height, immediatePresentMode);
    }
    @Override public void close() { owner.close(); }
}
