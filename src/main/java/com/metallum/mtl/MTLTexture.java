package com.metallum.mtl;
import com.metallum.nativebridge.NativeMetalDevice;
public final class MTLTexture {
    private MTLTexture() {}
    public static long pixelFormat(NativeMetalDevice.Resource texture) { return texture.textureInfo(0); }
    public static long width(NativeMetalDevice.Resource texture) { return texture.textureInfo(1); }
    public static long height(NativeMetalDevice.Resource texture) { return texture.textureInfo(2); }
}
