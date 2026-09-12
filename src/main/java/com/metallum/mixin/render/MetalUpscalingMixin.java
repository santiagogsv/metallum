package com.metallum.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallum.Metallum;
import com.metallum.render.MetalUpscaling;
import com.metallum.render.RenderScale;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class MetalUpscalingMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private GameRenderState gameRenderState;
    @Shadow @Final private GlobalSettingsUniform globalSettingsUniform;
    @Shadow @Final @Mutable private RenderTarget mainRenderTarget;
    @Unique private RenderTarget metallum$worldTarget;
    @Unique private static final double metallum$scale = RenderScale.parse(System.getProperty("metallum.renderScale", "1"));

    @WrapMethod(method = "renderLevel")
    private void metallum$renderScaledWorld(DeltaTracker delta, Operation<Void> original) {
        var window = gameRenderState.windowRenderState;
        int width = window.width, height = window.height;
        if (metallum$scale == 1 || width < 2 || height < 2 || !MetalUpscaling.supported(mainRenderTarget.getColorTexture())) {
            original.call(delta);
            return;
        }
        int scaledWidth = RenderScale.dimension(width, metallum$scale);
        int scaledHeight = RenderScale.dimension(height, metallum$scale);
        if (metallum$worldTarget == null || metallum$worldTarget.width != scaledWidth || metallum$worldTarget.height != scaledHeight) {
            if (metallum$worldTarget == null) {
                metallum$worldTarget = new TextureTarget("Metallum scaled world", scaledWidth, scaledHeight, true, mainRenderTarget.getColorTexture().getFormat());
            } else {
                // SkyRenderer caches this RenderTarget. Keep its identity across resizes.
                MetalUpscaling.release(metallum$worldTarget.getColorTexture());
                metallum$worldTarget.resize(scaledWidth, scaledHeight);
            }
            minecraft.levelRenderer.resize(scaledWidth, scaledHeight);
            Metallum.LOGGER.info("[metallum-metalfx] spatial world {}x{} -> {}x{}; UI remains native resolution", scaledWidth, scaledHeight, width, height);
        }
        RenderTarget full = mainRenderTarget;
        try {
            mainRenderTarget = metallum$worldTarget;
            window.width = scaledWidth; window.height = scaledHeight;
            metallum$updateUniforms(delta);
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                    mainRenderTarget.getColorTexture(), gameRenderState.guiRenderState.clearColorOverride, mainRenderTarget.getDepthTexture(), 0);
            original.call(delta);
        } finally {
            mainRenderTarget = full;
            window.width = width; window.height = height;
            metallum$updateUniforms(delta);
        }
        MetalUpscaling.upscale(metallum$worldTarget.getColorTexture(), full.getColorTexture());
    }

    @Unique private void metallum$updateUniforms(DeltaTracker delta) {
        var window = gameRenderState.windowRenderState;
        var options = gameRenderState.optionsRenderState;
        globalSettingsUniform.update(window.width, window.height, options.glintStrength,
                minecraft.level == null ? 0 : minecraft.level.getGameTime(), delta, options.menuBackgroundBlurriness,
                gameRenderState.levelRenderState.cameraRenderState.pos, options.textureFiltering == TextureFilteringMethod.RGSS);
    }
    @Unique private void metallum$releaseTarget() {
        if (metallum$worldTarget != null) {
            MetalUpscaling.release(metallum$worldTarget.getColorTexture());
            metallum$worldTarget.destroyBuffers();
            metallum$worldTarget = null;
        }
    }
    // Cached world renderers can survive a level change, so retain the target until close.
    @Inject(method = "close", at = @At("HEAD"))
    private void metallum$releaseWorldTarget(CallbackInfo ci) { metallum$releaseTarget(); }
}
