package com.metallum.mtl;

import com.metallum.nativebridge.NativeMetalDevice;

/** Value adapter for a complete Swift copy pass. No Objective-C encoder exists in Java. */
public final class MTLCopyPass {
    private final NativeMetalDevice device;
    private final NativeMetalDevice.Resource command, fence;
    public MTLCopyPass(NativeMetalDevice device, NativeMetalDevice.Resource command, NativeMetalDevice.Resource fence) {
        this.device = device; this.command = command; this.fence = fence;
    }
    private void copy(long... words) { device.copyPass(command, fence, words); }
    public void copyFromBufferToBuffer(MTLBuffer src, long offset, MTLBuffer dst, long target, long size) {
        copy(0, src.nativeOwner().id(device), dst.nativeOwner().id(device), offset, 0, 0, 0, 0, 0, target, 0, 0, 0, 0, 0, size);
    }
    public void copyFromBufferToTexture(MTLBuffer src, long offset, long row, long image, long width, long height,
            NativeMetalDevice.Resource dst, long slice, long level, long x, long y) {
        copy(1, src.nativeOwner().id(device), dst.id(device), offset, 0, 0, 0, width, height, slice, level, x, y, row, image, 0);
    }
    public void copyFromTextureToBuffer(NativeMetalDevice.Resource src, long slice, long level, long x, long y,
            long width, long height, MTLBuffer dst, long offset, long row, long image) {
        copy(2, src.id(device), dst.nativeOwner().id(device), slice, level, x, y, width, height, offset, 0, 0, 0, row, image, 0);
    }
    public void copyFromTextureToTexture(NativeMetalDevice.Resource src, long slice, long level, long x, long y,
            long width, long height, NativeMetalDevice.Resource dst, long dstSlice, long dstLevel, long dstX, long dstY) {
        copy(3, src.id(device), dst.id(device), slice, level, x, y, width, height, dstSlice, dstLevel, dstX, dstY, 0, 0, 0);
    }
}
