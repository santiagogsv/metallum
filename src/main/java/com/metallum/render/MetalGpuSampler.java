package com.metallum.render;

import com.metallum.nativebridge.NativeMetalDevice;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;

import java.util.OptionalDouble;

@Environment(EnvType.CLIENT)
final class MetalGpuSampler extends GpuSampler {
    private final MetalDevice device;
    private final NativeMetalDevice.Resource nativeOwner;
    private final NativeMetalDevice.Resource nativeResource;
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    private boolean closed;

    MetalGpuSampler(
            final MetalDevice device,
            final AddressMode addressModeU,
            final AddressMode addressModeV,
            final FilterMode minFilter,
            final FilterMode magFilter,
            final int maxAnisotropy,
            final OptionalDouble maxLod
    ) {
        this.device = device;
        this.nativeOwner = device.nativeOwner().createSampler(addressModeU == AddressMode.REPEAT, addressModeV == AddressMode.REPEAT,
                minFilter == FilterMode.LINEAR, magFilter == FilterMode.LINEAR, Math.clamp(maxAnisotropy, 1, 16), maxLod.orElse(1000.0));
        this.nativeResource = this.nativeOwner;
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
    }

    @Override
    public @NonNull AddressMode getAddressModeU() {
        return this.addressModeU;
    }

    @Override
    public @NonNull AddressMode getAddressModeV() {
        return this.addressModeV;
    }

    @Override
    public @NonNull FilterMode getMinFilter() {
        return this.minFilter;
    }

    @Override
    public @NonNull FilterMode getMagFilter() {
        return this.magFilter;
    }

    @Override
    public int getMaxAnisotropy() {
        return this.maxAnisotropy;
    }

    @Override
    public @NonNull OptionalDouble getMaxLod() {
        return this.maxLod;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.device.queueNativeRelease(this.nativeOwner::close);
    }

    boolean isClosed() {
        return this.closed;
    }

    NativeMetalDevice.Resource nativeResource() {
        if (this.closed) throw new IllegalStateException("Sampler is closed");
        return this.nativeResource;
    }

}
