package com.metallum.mixin.render;

import com.metallum.config.MetalOptions;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoSettingsScreen.class)
abstract class MetalVideoSettingsMixin extends OptionsSubScreen {
    protected MetalVideoSettingsMixin(Screen parent, Options options, Component title) { super(parent, options, title); }
    @Inject(method = "addOptions", at = @At("TAIL"))
    private void metallum$addScale(CallbackInfo ci) { list.addBig(MetalOptions.scaleOption()); }
}
