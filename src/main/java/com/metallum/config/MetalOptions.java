package com.metallum.config;

import com.metallum.Metallum;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import java.io.IOException;

public final class MetalOptions {
    public static final RenderScaleSettings SCALE = new RenderScaleSettings(
            FabricLoader.getInstance().getConfigDir().resolve("metallum-render-scale.txt"),
            System.getProperty("metallum.renderScale", "1"));
    private MetalOptions() {}
    public static OptionInstance<Integer> scaleOption() {
        return new OptionInstance<>("options.metallum.renderScale",
                OptionInstance.cachedConstantTooltip(Component.literal("World resolution before MetalFX. Off uses native resolution. Try 85–90% first; lower values lose detail. UI stays native.")),
                (caption, value) -> Component.literal("MetalFX: " + (value == 100 ? "Off (native)" : value + "% resolution")),
                new OptionInstance.IntRange(50, 100, false), SCALE.percent(), value -> {
                    try { SCALE.set(value); }
                    catch (IOException error) { Metallum.LOGGER.warn("Could not save MetalFX resolution", error); }
                });
    }
}
