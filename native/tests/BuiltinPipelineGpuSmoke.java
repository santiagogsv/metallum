package com.metallum.mtl;

import com.metallum.nativebridge.NativeMetalDevice;
import java.nio.file.Path;
import org.joml.Vector4f;

/** Exercises the production Java clear renderer on a real GPU, including inherited state. */
public final class BuiltinPipelineGpuSmoke {
    public static void main(String[] args) {
        try (var device = new NativeMetalDevice(Path.of(args[0]))) {
            MTLBuiltinPipelines.init(new MTLDevice(device));
            try {
                long cachedResources = -1;
                for (long depthFormat : new long[]{250, 252, 0}) {
                    try (var color = device.createTexture(70, 8, 8, 1, 1, false, true, null);
                         var depth = depthFormat == 0 ? null : device.createTexture(depthFormat, 8, 8, 1, 1, false, true, null);
                         var pixels = new MTLBuffer(device.createBuffer(256, true));
                         var fence = new MTLFence(device.createFence())) {
                        var command = new MTLCommandBuffer(device, "Clear inherited-state regression");
                        try {
                            var encoder = command.makeRenderCommandEncoder(color, new Vector4f(1, 0, 1, 1), depth,
                                    depth == null ? null : 1.0, 8, 8);
                            // State from an earlier draw must not prevent a full clear.
                            encoder.setFrontFacingWinding(MTLWinding.Clockwise);
                            encoder.setCullMode(MTLCullMode.Front);
                            encoder.setTriangleFillMode(MTLTriangleFillMode.Lines);
                            encoder.setDepthBias(4, 4, 0);
                            encoder.clearDraw(color, depth, 8, 8, new Vector4f(0, 1, 0, 1), depth == null ? null : 0.5);
                            encoder.updateFence(fence, MTLRenderStages.VertexAndFragment);
                            encoder.endEncoding();
                            command.copyPass(fence).copyFromTextureToBuffer(color, 0, 0, 0, 0, 8, 8, pixels, 0, 32, 256);
                            command.commit();
                            if (!command.waitUntilCompleted(5000)) throw new AssertionError("Clear timeout");
                            var data = pixels.contents().reinterpret(256).asByteBuffer();
                            for (int pixel = 0; pixel < 64; pixel++) {
                                if (Byte.toUnsignedInt(data.get(pixel * 4)) != 0 || Byte.toUnsignedInt(data.get(pixel * 4 + 1)) != 255
                                        || Byte.toUnsignedInt(data.get(pixel * 4 + 2)) != 0)
                                    throw new AssertionError("Clear left stale pixels with depth format " + depthFormat + " at pixel " + pixel);
                            }
                        } finally { command.close(); }
                    }
                    long resources = device.memoryStats().resources();
                    if (cachedResources >= 0 && resources != cachedResources)
                        throw new AssertionError("Clear cache grew for another depth format");
                    cachedResources = resources;
                }
            } finally { MTLBuiltinPipelines.close(); device.clearShaderLibraries(); }
            if (device.memoryStats().resources() != 0 || device.memoryStats().buffers() != 0)
                throw new AssertionError("Built-in pipeline resources leaked");
        }
        System.out.println("Production clear renderer passed inherited-state and shared depth-format pipeline checks");
    }
}
