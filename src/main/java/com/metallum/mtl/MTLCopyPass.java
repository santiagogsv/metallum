package com.metallum.mtl;

import com.metallum.nativebridge.NativeMetalDevice;

/** Value adapter for copies grouped into Swift-owned passes. No Objective-C encoder exists in Java. */
public final class MTLCopyPass {
    private final NativeMetalDevice device;
    private final NativeMetalDevice.Resource command, fence;
    public MTLCopyPass(NativeMetalDevice device, NativeMetalDevice.Resource command, NativeMetalDevice.Resource fence) {
        this.device = device; this.command = command; this.fence = fence;
    }
    private final long[] words = new long[16];
    private void copy(long op, long src, long dst, long srcOffset, long srcLevel, long srcX, long srcY,
                      long width, long height, long dstOffset, long dstLevel, long dstX, long dstY,
                      long row, long image, long size) {
        words[0] = op; words[1] = src; words[2] = dst; words[3] = srcOffset;
        words[4] = srcLevel; words[5] = srcX; words[6] = srcY; words[7] = width;
        words[8] = height; words[9] = dstOffset; words[10] = dstLevel; words[11] = dstX;
        words[12] = dstY; words[13] = row; words[14] = image; words[15] = size;
        device.copyPass(command, fence, words);
    }
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
