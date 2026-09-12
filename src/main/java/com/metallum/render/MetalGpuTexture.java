package com.metallum.render;

import com.metallum.mtl.*;
import com.metallum.nativebridge.NativeMetalDevice;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;


@Environment(EnvType.CLIENT)
final class MetalGpuTexture extends GpuTexture {

    private final MetalDevice device;
    private final NativeMetalDevice.Resource nativeOwner;
    private final MTLPixelFormat mtlPixelFormat;
    private boolean closed;
    @Nullable
    private Vector4fc materializedColorClear;
    @Nullable
    private Double materializedDepthClear;
    private int views = 1;
    @Nullable
    private NativeMetalDevice.Resource nativeResource;

    MetalGpuTexture(
            final MetalDevice device,
            @GpuTexture.Usage final int usage,
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        this.mtlPixelFormat = MTLPixelFormat.from(format);

        this.nativeOwner = device.nativeOwner().createTexture(this.mtlPixelFormat.value, width, height, depthOrLayers,
                Math.max(mipLevels, 1), (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0,
                (usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0, label);
        this.nativeResource = this.nativeOwner;
    }

    MetalDevice device() { return device; }

    int pixelSize() {
        return this.getFormat().blockSize();
    }

    void recordMaterializedClear(@Nullable final Vector4fc color, @Nullable final Double depth) {
        if (color != null) {
            this.materializedColorClear = color;
        }
        if (depth != null) {
            this.materializedDepthClear = depth;
        }
    }

    boolean clearIsRedundant(@Nullable final Vector4fc color, @Nullable final Double depth) {
        return (color == null || color.equals(this.materializedColorClear))
                && (depth == null || depth.equals(this.materializedDepthClear));
    }

    void markContentsDirty() {
        this.materializedColorClear = null;
        this.materializedDepthClear = null;
    }

    NativeMetalDevice.Resource nativeResource() {
        if (this.nativeResource == null) {
            throw new IllegalStateException("Native Metal texture is closed");
        }
        return this.nativeResource;
    }

    NativeMetalDevice.Resource nativeOwner() { return this.nativeOwner; }

    void queueNativeRelease(Runnable release) { this.device.queueNativeRelease(release); }

    void addView() {
        if (this.closed) throw new IllegalStateException("Cannot create a view of a closed texture");
        this.views++;
    }

    void removeView() {
        this.views--;
        if (this.views < 0) {
            throw new IllegalStateException("Too many views removed from texture");
        }
        if (this.closed && this.views == 0 && this.nativeResource != null) {
            this.device.forgetTexture(this);
            this.nativeResource = null;
            this.device.queueNativeRelease(this.nativeOwner::close);
        }
    }

    MTLPixelFormat mtlPixelFormat() {
        return this.mtlPixelFormat;
    }

    MTLPixelFormat mtlStencilPixelFormat() {
        return this.mtlPixelFormat.hasStencil() ? this.mtlPixelFormat : MTLPixelFormat.Invalid;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.removeView();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

}
