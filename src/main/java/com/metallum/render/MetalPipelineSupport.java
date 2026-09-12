package com.metallum.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
final class MetalPipelineSupport {
    private MetalPipelineSupport() {
    }

    static boolean sameResource(com.metallum.nativebridge.NativeMetalDevice.Resource left, com.metallum.nativebridge.NativeMetalDevice.Resource right) {
        return left == right;
    }

    static List<String> vertexAttributeNames(final RenderPipeline pipeline) {
        List<String> names = new ArrayList<>();
        for (VertexFormat binding : pipeline.getVertexFormatBindings()) {
            if (binding != null) {
                for (VertexFormatElement element : binding.getElements()) {
                    names.add(element.name());
                }
            }
        }
        return names;
    }
}
