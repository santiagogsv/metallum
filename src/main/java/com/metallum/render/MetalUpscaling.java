package com.metallum.render;

import com.mojang.blaze3d.textures.GpuTexture;

/** Minecraft adaptation only; Swift owns MetalFX objects and encoding. */
public final class MetalUpscaling {
    private MetalUpscaling() {}
    public static boolean supported(GpuTexture texture) {
        return texture instanceof MetalGpuTexture metal && metal.device().nativeOwner().deviceInfo(3) == 1;
    }
    public static void upscale(GpuTexture source, GpuTexture destination) {
        if (!(source instanceof MetalGpuTexture from) || !(destination instanceof MetalGpuTexture to)
                || from.device() != to.device()) throw new IllegalArgumentException("MetalFX requires one Metal device");
        from.device().createCommandEncoder().upscale(from, to);
    }
    public static void release(GpuTexture texture) {
        if (texture instanceof MetalGpuTexture metal) metal.device().nativeOwner().clearUpscaler();
    }
}
