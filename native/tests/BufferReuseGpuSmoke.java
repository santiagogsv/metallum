package com.metallum.render;

import com.metallum.mtl.CAMetalLayer;
import com.metallum.mtl.MTLDevice;
import com.metallum.nativebridge.NativeMetalDevice;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import org.joml.Vector4f;
import java.nio.file.Path;

/** Production upload path: earlier GPU reads must retain their original snapshot. */
public final class BufferReuseGpuSmoke {
    private static ByteBuffer tint(float red, float green) {
        return ByteBuffer.allocate(16).order(ByteOrder.nativeOrder()).putFloat(red).putFloat(green).putFloat(0).putFloat(1).flip();
    }

    @SuppressWarnings("unchecked")
    private static void checkBindings(MetalDevice device, boolean texel) throws Exception {
        String msl = """
                #include <metal_stdlib>
                using namespace metal;
                struct V { float4 position [[position]]; float4 color; };
                vertex V vs(uint id [[vertex_id]], constant float4& tint [[buffer(0)]]) {
                    float2 p[3] = {float2(-1,-1),float2(3,-1),float2(-1,3)};
                    return V{float4(p[id],0,1),tint};
                }
                fragment float4 fs(V v [[stage_in]], constant float4& tint [[buffer(0)]]) { return (v.color + tint) * 0.5; }
                vertex float4 tex_vs(uint id [[vertex_id]]) {
                    float2 p[3] = {float2(-1,-1),float2(3,-1),float2(-1,3)};
                    return float4(p[id],0,1);
                }
                fragment float4 tex_fs(texture_buffer<float> tint [[texture(0)]]) { return tint.read(0u); }
                """;
        var info = RenderPipeline.builder().withLocation(texel ? "metallum/texel_test" : "metallum/uniform_test")
                .withVertexShader("test").withFragmentShader("test").withCull(false)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withDepthStencilState(Optional.empty())
                .withColorTargetState(ColorTargetState.DEFAULT).build();
        var resource = new MetalCompiledRenderPipeline.ResourceBinding(texel
                ? MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER : MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER,
                "Tint", 0, texel ? 2 : 3, texel ? GpuFormat.RGBA32_FLOAT : null);
        var compiled = new MetalCompiledRenderPipeline(device, info, msl, msl,
                texel ? "tex_vs" : "vs", texel ? "tex_fs" : "fs", List.of(resource));
        // Supply an already compiled MSL pipeline to isolate the production binding/upload path.
        var cache = MetalDevice.class.getDeclaredField("compiledPipelines"); cache.setAccessible(true);
        ((Map<RenderPipeline, MetalCompiledRenderPipeline>) cache.get(device)).put(info, compiled);
        var encoder = device.createCommandEncoder();
        try (var color = device.createTexture("Binding test", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                     GpuFormat.RGBA8_UNORM, 8, 8, 1, 1);
             var view = device.createTextureView(color);
             var uniform = (MetalGpuBuffer) device.createBuffer(null, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
             var pixels = (MetalGpuBuffer) device.createBuffer(null, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 256)) {
            encoder.writeToBuffer(uniform.slice(), tint(1, 0));
            var pass = encoder.createRenderPass(RenderPassDescriptor.create(() -> "Binding snapshots")
                    .withColorAttachment(view, Optional.of(new Vector4f(0,0,0,1)))
                    .withRenderArea(new com.mojang.blaze3d.systems.RenderPass.RenderArea(0, 0, 8, 8)));
            {
                pass.setPipeline(info); pass.setUniform("Tint", uniform.slice());
                pass.enableScissor(0, 0, 4, 8); pass.draw(3, 1, 0, 0);
                device.nativeOwner().diagnostics();
                pass.setUniform("Tint", uniform.slice()); pass.draw(3, 1, 0, 0);
                var repeated = device.nativeOwner().diagnostics();
                if (repeated.bindingWrites() != 0 || repeated.bindingSkips() != 0)
                    throw new AssertionError("Unchanged bindings crossed into native tables");
                encoder.writeToBuffer(uniform.slice(), tint(0, 1));
                // No setUniform: changing backing must invalidate the previous address/view itself.
                pass.enableScissor(4, 0, 4, 8); pass.draw(3, 1, 0, 0);
            }
            encoder.copyTextureToBuffer(color, pixels, 0, () -> {}, 0);
            device.waitForSubmittedGpuWork();
            ByteBuffer data = pixels.currentStorage();
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                int offset = (y * 8 + x) * 4;
                if (Byte.toUnsignedInt(data.get(offset)) != (x < 4 ? 255 : 0)
                        || Byte.toUnsignedInt(data.get(offset + 1)) != (x < 4 ? 0 : 255))
                    throw new AssertionError((texel ? "Texel" : "Uniform") + " binding snapshot mismatch at " + x + "," + y);
            }
        }
    }

    private static void checkAttachmentStores(MetalDevice device) {
        var encoder = device.createCommandEncoder();
        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC;
        try (var color = device.createTexture("Store test", usage, GpuFormat.RGBA8_UNORM, 8, 8, 1, 1);
             var depth = device.createTexture("Depth preservation", usage, GpuFormat.D16_UNORM, 8, 8, 1, 1);
             var colorView = (MetalGpuTextureView) device.createTextureView(color);
             var depthView = (MetalGpuTextureView) device.createTextureView(depth);
             var colors = (MetalGpuBuffer) device.createBuffer(null, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 256);
             var depths = (MetalGpuBuffer) device.createBuffer(null, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 128)) {
            encoder.renderCommandEncoder(colorView, depthView, 8, 8, new Vector4f(1,0,0,1), 1.0);
            // Same color is fully replaced, but the removed depth attachment must keep its contents.
            encoder.renderCommandEncoder(colorView, null, 8, 8, new Vector4f(0,1,0,1), null);
            encoder.copyTextureToBuffer(color, colors, 0, () -> {}, 0);
            encoder.copyTextureToBuffer(depth, depths, 0, () -> {}, 0);
            device.waitForSubmittedGpuWork();
            var c = colors.currentStorage(); var d = depths.currentStorage();
            for (int i = 0; i < 64; i++) {
                if (c.get(i * 4) != 0 || Byte.toUnsignedInt(c.get(i * 4 + 1)) != 255
                        || Short.toUnsignedInt(d.getShort(i * 2)) != 65535)
                    throw new AssertionError("Attachment discard lost color clear or preserved depth at " + i);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        try (var nativeDevice = new NativeMetalDevice(Path.of(args[0]))) {
            var layer = new CAMetalLayer(new MTLDevice(nativeDevice), 1);
            var device = new MetalDevice((id, type) -> null, new GpuDebugOptions(0, false, false, false),
                    layer, "Buffer regression", null, () -> {}, nativeDevice);
            try {
                var encoder = device.createCommandEncoder();
                int usage = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC;
                try (var source = (MetalGpuBuffer) device.createBuffer(null, usage, 32);
                     var first = (MetalGpuBuffer) device.createBuffer(null, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 32);
                     var second = (MetalGpuBuffer) device.createBuffer(null, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 32)) {
                    var initial = source.backing();
                    byte[] bytes = new byte[32];
                    for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
                    encoder.writeToBuffer(source.slice(), ByteBuffer.wrap(bytes));
                    encoder.writeToBuffer(source.slice(4, 4), ByteBuffer.wrap(new byte[]{44,45,46,47}));
                    if (source.backing() != initial) throw new AssertionError("Unused backing was replaced");
                    encoder.copyToBuffer(source.slice(), first.slice());
                    encoder.writeToBuffer(source.slice(12, 4), ByteBuffer.wrap(new byte[]{90,91,92,93}));
                    var replacement = source.backing();
                    if (replacement == initial) throw new AssertionError("Pending GPU snapshot was overwritten");
                    encoder.writeToBuffer(source.slice(20, 4), ByteBuffer.wrap(new byte[]{70,71,72,73}));
                    if (source.backing() != replacement) throw new AssertionError("Unused replacement was replaced again");
                    encoder.copyToBuffer(source.slice(), second.slice());
                    device.waitForSubmittedGpuWork();
                    ByteBuffer before = first.currentStorage(), after = second.currentStorage();
                    for (int i = 0; i < 32; i++) {
                        int oldValue = i >= 4 && i < 8 ? 44 + i - 4 : i;
                        int newValue = i >= 12 && i < 16 ? 90 + i - 12 : i >= 20 && i < 24 ? 70 + i - 20 : oldValue;
                        if (Byte.toUnsignedInt(before.get(i)) != oldValue || Byte.toUnsignedInt(after.get(i)) != newValue)
                            throw new AssertionError("Upload snapshot mismatch at " + i);
                    }
                    encoder.writeToBuffer(source.slice(0, 1), ByteBuffer.wrap(new byte[]{9}));
                    if (source.backing() != replacement) throw new AssertionError("Completed backing was not reused");
                    long generation = encoder.backingGeneration();
                    encoder.writeToBuffer(source.slice(0, 0), ByteBuffer.allocate(0));
                    if (encoder.backingGeneration() != generation) throw new AssertionError("Empty upload replaced backing");
                }
                checkBindings(device, false);
                checkBindings(device, true);
                checkAttachmentStores(device);
            } finally { device.close(); }
            if (nativeDevice.memoryStats().resources() != 0 || nativeDevice.memoryStats().buffers() != 0)
                throw new AssertionError("Upload resources leaked");
        }
        System.out.println("Production uploads and bindings passed GPU snapshots, partial preservation, completed-backing reuse, uniform/texel invalidation and unchanged-binding checks");
    }
}
