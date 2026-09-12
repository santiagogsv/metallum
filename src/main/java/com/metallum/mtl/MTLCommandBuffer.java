package com.metallum.mtl;

import com.metallum.objc.AutoreleasePool;
import com.metallum.nativebridge.NativeMetalDevice;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public final class MTLCommandBuffer {

    private static final Msg PUSH_DEBUG_GROUP = Msg.ofVoid("pushDebugGroup:", ADDRESS);
    private static final Msg POP_DEBUG_GROUP = Msg.ofVoid("popDebugGroup");

    private MemorySegment handle;
    private final NativeMetalDevice nativeDevice;
    private final NativeMetalDevice.Resource command;
    private NativeMetalDevice.Resource submission;

    public MTLCommandBuffer(NativeMetalDevice nativeDevice, String label) {
        this.nativeDevice = nativeDevice;
        this.command = nativeDevice.createCommandBuffer(label);
        this.handle = command.borrowedHandle();
    }

    public MTLCopyPass copyPass(MTLFence fence) { return new MTLCopyPass(nativeDevice, command, fence.owner()); }

    // Load actions: discard=0, preserve=1, clear=2. Swift owns all descriptor policy.
    MTLRenderCommandEncoder makeRenderCommandEncoder(MemorySegment color, int colorLoad,
            @Nullable Vector4fc clearColor, MemorySegment depth, int depthLoad, @Nullable Double clearDepth) {
        double[] clear = clearColor == null ? new double[]{0, 0, 0, 0, clearDepth == null ? 1 : clearDepth}
                : new double[]{clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w(), clearDepth == null ? 1 : clearDepth};
        return new MTLRenderCommandEncoder(nativeDevice, nativeDevice.createRenderPass(command, color, depth, colorLoad, depthLoad, clear));
    }

    public MTLRenderCommandEncoder makeRenderCommandEncoder(MemorySegment color, @Nullable Vector4fc clearColor,
            MemorySegment depth, @Nullable Double clearDepth, double viewportWidth, double viewportHeight) {
        MTLRenderCommandEncoder encoder = makeRenderCommandEncoder(color, clearColor == null ? 1 : 2,
                clearColor, depth, clearDepth == null ? 1 : 2, clearDepth);
        encoder.setViewport(0, 0, viewportWidth, viewportHeight, 0, 1);
        return encoder;
    }

    public void clearColorDepthTexturesRegion(
            final MemorySegment colorTexture,
            final Vector4fc clearColor,
            final MemorySegment depthTexture,
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

    public void encodePresentTextureToDrawable(final CAMetalLayer layer, final MemorySegment sourceTexture, final MTLFence globalFence) {
        MTLBuiltinPipelines.encodePresentTextureToDrawable(this, layer, sourceTexture, globalFence);
    }

    void present(CAMetalLayer layer, MemorySegment source, MTLFence fence,
                 MemorySegment pipeline, MemorySegment nearest, MemorySegment linear) {
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

    public void pushDebugGroup(final String label) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MemorySegment nsLabel = ObjC.nsString(label == null ? "" : label);
            PUSH_DEBUG_GROUP.send(handle(), nsLabel);
            ObjC.release(nsLabel);
        }
    }

    public void popDebugGroup() {
        POP_DEBUG_GROUP.send(handle());
    }

    public void close() {
        if (ObjC.isNil(handle)) {
            return;
        }
        if (submission != null) { submission.close(); submission = null; }
        command.close();
        handle = MemorySegment.NULL;
    }

    public MemorySegment handle() {
        if (ObjC.isNil(handle)) {
            throw new IllegalStateException("MTLCommandBuffer is closed");
        }
        return handle;
    }
}
