package com.metallum.render;

import com.metallum.mtl.MTLTexture;
import com.metallum.nativebridge.NativeMetalDevice;
import com.metallum.objc.ObjC;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
final class MetalGpuTextureView extends GpuTextureView {
    private boolean closed;
    private NativeMetalDevice.Resource nativeOwner;
    @Nullable
    private MemorySegment nativeHandle;

    MetalGpuTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
        if (baseMipLevel < 0 || mipLevels <= 0 || baseMipLevel >= texture.getMipLevels()
                || mipLevels > texture.getMipLevels() - baseMipLevel) throw new IllegalArgumentException("Invalid texture view mip range");
        ((MetalGpuTexture) texture).addView();
    }

    MemorySegment nativeHandle() {
        if (this.closed) throw new IllegalStateException("Texture view is closed");
        if (this.nativeHandle == null) {
            MetalGpuTexture texture = (MetalGpuTexture) this.texture();
            if (texture.nativeOwner() != null) {
                this.nativeOwner = texture.nativeOwner().createView(this.baseMipLevel(), this.mipLevels());
                this.nativeHandle = this.nativeOwner.borrowedHandle();
            } else if (this.baseMipLevel() == 0 && this.mipLevels() >= texture.getMipLevels()) {
                this.nativeHandle = ObjC.retain(texture.nativeHandle());
            } else {
                MemorySegment viewHandle = MTLTexture.newTextureView(
                        texture.nativeHandle(),
                        this.baseMipLevel(),
                        this.mipLevels()
                );
                if (ObjC.isNil(viewHandle)) {
                    throw new IllegalStateException(
                            "Failed to create Metal texture view for mip range " + this.baseMipLevel() + "+" + this.mipLevels()
                    );
                }
                this.nativeHandle = viewHandle;
            }
        }
        return this.nativeHandle;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        MemorySegment handle = this.nativeHandle;
        this.closed = true;
        MetalGpuTexture texture = (MetalGpuTexture) this.texture();
        if (this.nativeOwner != null) texture.queueNativeRelease(this.nativeOwner::close);
        else if (handle != null) texture.queueNativeRelease(() -> ObjC.release(handle));
        this.nativeOwner = null;
        this.nativeHandle = null;
        texture.removeView();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }
}
