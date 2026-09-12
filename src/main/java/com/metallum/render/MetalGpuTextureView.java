package com.metallum.render;

import com.metallum.nativebridge.NativeMetalDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;


@Environment(EnvType.CLIENT)
final class MetalGpuTextureView extends GpuTextureView {
    private boolean closed;
    private NativeMetalDevice.Resource nativeOwner;
    @Nullable
    private NativeMetalDevice.Resource nativeResource;

    MetalGpuTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
        if (baseMipLevel < 0 || mipLevels <= 0 || baseMipLevel >= texture.getMipLevels()
                || mipLevels > texture.getMipLevels() - baseMipLevel) throw new IllegalArgumentException("Invalid texture view mip range");
        ((MetalGpuTexture) texture).addView();
    }

    NativeMetalDevice.Resource nativeResource() {
        if (this.closed) throw new IllegalStateException("Texture view is closed");
        if (this.nativeResource == null) {
            MetalGpuTexture texture = (MetalGpuTexture) this.texture();
            this.nativeOwner = texture.nativeOwner().createView(this.baseMipLevel(), this.mipLevels());
            this.nativeResource = this.nativeOwner;
        }
        return this.nativeResource;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        MetalGpuTexture texture = (MetalGpuTexture) this.texture();
        if (this.nativeOwner != null) texture.queueNativeRelease(this.nativeOwner::close);
        this.nativeOwner = null;
        this.nativeResource = null;
        texture.removeView();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }
}
