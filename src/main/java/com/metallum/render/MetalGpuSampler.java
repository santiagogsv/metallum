package com.metallum.render;

import com.metallum.mtl.MTLSamplerAddressMode;
import com.metallum.nativebridge.NativeMetalDevice;
import com.metallum.mtl.MTLSamplerDescriptor;
import com.metallum.mtl.MTLSamplerMinMagFilter;
import com.metallum.mtl.MTLSamplerMipFilter;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;

import java.lang.foreign.MemorySegment;
import java.util.OptionalDouble;

@Environment(EnvType.CLIENT)
final class MetalGpuSampler extends GpuSampler {
    private final MetalDevice device;
    private final NativeMetalDevice.Resource nativeOwner;
    private final MemorySegment nativeHandle;
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
        if (device.nativeOwner() != null) {
            this.nativeOwner = device.nativeOwner().createSampler(addressModeU == AddressMode.REPEAT, addressModeV == AddressMode.REPEAT,
                    minFilter == FilterMode.LINEAR, magFilter == FilterMode.LINEAR, Math.clamp(maxAnisotropy, 1, 16), maxLod.orElse(1000.0));
            this.nativeHandle = this.nativeOwner.borrowedHandle();
        } else {
            this.nativeOwner = null;
            try (MTLSamplerDescriptor descriptor = MTLSamplerDescriptor.create()) {
                descriptor.minFilter(MTLSamplerMinMagFilter.from(minFilter));
                descriptor.magFilter(MTLSamplerMinMagFilter.from(magFilter));
                descriptor.mipFilter(toMtlMipFilter(maxLod));
                descriptor.sAddressMode(MTLSamplerAddressMode.from(addressModeU));
                descriptor.tAddressMode(MTLSamplerAddressMode.from(addressModeV));
                descriptor.maxAnisotropy(Math.clamp(maxAnisotropy, 1, 16));
                descriptor.lodMinClamp(0.0f);
                double lodMaxClamp = toMtlMaxLodClamp(maxLod);
                descriptor.lodMaxClamp(lodMaxClamp >= 0.0 && Double.isFinite(lodMaxClamp) ? (float) lodMaxClamp : Float.MAX_VALUE);
                this.nativeHandle = device.metalDevice().newSamplerState(descriptor);
            }
        }
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
        if (this.nativeOwner != null) this.device.queueNativeRelease(this.nativeOwner::close);
        else this.device.queueResourceRelease(this.nativeHandle);
    }

    boolean isClosed() {
        return this.closed;
    }

    MemorySegment nativeHandle() {
        if (this.closed) throw new IllegalStateException("Sampler is closed");
        return this.nativeHandle;
    }

    private static MTLSamplerMipFilter toMtlMipFilter(final OptionalDouble maxLod) {
        return maxLod.orElse(1000.0) > 0.25 ? MTLSamplerMipFilter.Linear : MTLSamplerMipFilter.Nearest;
    }

    private static double toMtlMaxLodClamp(final OptionalDouble maxLod) {
        return Math.max(0.25, maxLod.orElse(1000.0));
    }
}
