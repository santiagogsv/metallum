package com.metallum.mixin.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** SkyRenderer otherwise caches the target from its construction frame. */
@Mixin(SkyRenderer.class)
abstract class MetalSkyTargetMixin {
    @Redirect(method = "*", at = @At(value = "FIELD", opcode = 180,
            target = "Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget metallum$currentTarget(SkyRenderer sky) {
        return Minecraft.getInstance().gameRenderer.mainRenderTarget();
    }
}
