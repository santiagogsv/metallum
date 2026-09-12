package com.metallum.mtl;

import com.metallum.nativebridge.NativeMetalDevice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;



@Environment(EnvType.CLIENT)
public final class MTLCommandBuffer {


    private boolean closed;
    private final NativeMetalDevice nativeDevice;
    private final NativeMetalDevice.Resource command;
    private NativeMetalDevice.Resource submission;
    private MTLCopyPass copies;
    private MTLFence copyFence;

    public MTLCommandBuffer(NativeMetalDevice nativeDevice, String label) {
        this.nativeDevice = nativeDevice;
        this.command = nativeDevice.createCommandBuffer(label);
    }

    public void upscale(NativeMetalDevice.Resource source, NativeMetalDevice.Resource destination, MTLFence fence) {
        nativeDevice.upscale(command, source, destination, fence.owner());
    }

    public MTLCopyPass copyPass(MTLFence fence) {
        if (copies == null || copyFence != fence) {
            copies = new MTLCopyPass(nativeDevice, command, fence.owner());
            copyFence = fence;
        }
        return copies;
    }

    // Load actions: discard=0, preserve=1, clear=2. Swift owns all descriptor policy.
    MTLRenderCommandEncoder makeRenderCommandEncoder(NativeMetalDevice.Resource color, int colorLoad,
            @Nullable Vector4fc clearColor, NativeMetalDevice.Resource depth, int depthLoad, @Nullable Double clearDepth) {
        double[] clear = clearColor == null ? new double[]{0, 0, 0, 0, clearDepth == null ? 1 : clearDepth}
                : new double[]{clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w(), clearDepth == null ? 1 : clearDepth};
        return new MTLRenderCommandEncoder(nativeDevice, nativeDevice.createRenderPass(command, color, depth, colorLoad, depthLoad, clear));
    }

    public MTLRenderCommandEncoder makeRenderCommandEncoder(NativeMetalDevice.Resource color, @Nullable Vector4fc clearColor,
            NativeMetalDevice.Resource depth, @Nullable Double clearDepth, double viewportWidth, double viewportHeight) {
        MTLRenderCommandEncoder encoder = makeRenderCommandEncoder(color, clearColor == null ? 1 : 2,
                clearColor, depth, clearDepth == null ? 1 : 2, clearDepth);
        encoder.setViewport(0, 0, viewportWidth, viewportHeight, 0, 1);
        return encoder;
    }

    public void clearColorDepthTexturesRegion(
            final NativeMetalDevice.Resource colorTexture,
            final Vector4fc clearColor,
            final NativeMetalDevice.Resource depthTexture,
            final double clearDepth,
            final int regionX,
            final int regionY,
            final int regionWidth,
            final int regionHeight,
            final MTLFence globalFence
    ) {
        MTLBuiltinPipelines.clearColorDepthTexturesRegion(
                this,
                colorTexture,
                clearColor,
                depthTexture,
                clearDepth,
                regionX,
                regionY,
                regionWidth,
                regionHeight,
                globalFence
        );
    }

    public void encodePresentTextureToDrawable(final CAMetalLayer layer, final NativeMetalDevice.Resource sourceTexture, final MTLFence globalFence) {
        MTLBuiltinPipelines.encodePresentTextureToDrawable(this, layer, sourceTexture, globalFence);
    }

    void present(CAMetalLayer layer, NativeMetalDevice.Resource source, MTLFence fence,
                 NativeMetalDevice.Resource pipeline, NativeMetalDevice.Resource nearest, NativeMetalDevice.Resource linear) {
        nativeDevice.present(command, layer.owner(), source, fence == null ? null : fence.owner(), pipeline, nearest, linear);
    }

    public void commit() {
        if (submission != null) throw new IllegalStateException("Command buffer already submitted");
        submission = nativeDevice.submit(command);
    }

    public boolean waitUntilCompleted(final long timeoutMs) {
        if (submission == null) throw new IllegalStateException("Command buffer is not submitted");
        return nativeDevice.waitSubmission(submission, timeoutMs);
    }

    public void pushDebugGroup(String label) { nativeDevice.commandDebug(command, label == null ? "" : label); }
    public void popDebugGroup() { nativeDevice.commandDebug(command, null); }
    public void close() {
        if (closed) return;
        if (submission != null) { submission.close(); submission = null; }
        command.close(); closed = true;
    }
}
