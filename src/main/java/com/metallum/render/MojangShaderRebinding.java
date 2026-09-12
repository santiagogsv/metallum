package com.metallum.render;

import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import java.util.List;
import java.util.ArrayList;

/** The single adapter to Mojang's Vulkan-typed SPIR-V rebinding contract. */
final class MojangShaderRebinding {
    private MojangShaderRebinding() {}

    static void rebind(IntermediaryShaderModule shader, List<String> inputs, List<ShaderBinding> bindings) throws ShaderCompileException {
        shader.rebind(inputs, toMojang(bindings));
    }

    static List<VulkanBindGroupLayout.Entry> toMojang(List<ShaderBinding> bindings) {
        return new ArrayList<>(bindings.stream().map(binding -> new VulkanBindGroupLayout.Entry(switch (binding.type()) {
            case UNIFORM_BUFFER -> VulkanBindGroupLayout.VulkanBindGroupEntryType.UNIFORM_BUFFER;
            case SAMPLED_IMAGE -> VulkanBindGroupLayout.VulkanBindGroupEntryType.SAMPLED_IMAGE;
            case TEXEL_BUFFER -> VulkanBindGroupLayout.VulkanBindGroupEntryType.TEXEL_BUFFER;
        }, binding.name(), binding.texelBufferFormat())).toList());
    }
}
