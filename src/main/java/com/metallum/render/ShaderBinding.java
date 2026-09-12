package com.metallum.render;

import com.mojang.blaze3d.GpuFormat;
import org.jspecify.annotations.Nullable;

/** Shared ordered shader layout before SPIR-V rebinding and Metal reflection. */
record ShaderBinding(Kind type, String name, @Nullable GpuFormat texelBufferFormat) {
    enum Kind { UNIFORM_BUFFER, SAMPLED_IMAGE, TEXEL_BUFFER }
}
