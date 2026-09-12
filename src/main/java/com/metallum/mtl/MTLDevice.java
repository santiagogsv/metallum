package com.metallum.mtl;

import com.metallum.nativebridge.NativeMetalDevice;
import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public record MTLDevice(MemorySegment handle, NativeMetalDevice nativeOwner) {
    private static final Msg NEW_COMMAND_QUEUE = Msg.of("newCommandQueue", ADDRESS);
    private static final Msg NEW_FENCE = Msg.of("newFence", ADDRESS);
    private static final Msg NAME = Msg.of("name", ADDRESS);
    private static final Msg MAX_BUFFER_LENGTH = Msg.of("maxBufferLength", JAVA_LONG);
    private static final Msg RECOMMENDED_MAX_WORKING_SET_SIZE = Msg.of("recommendedMaxWorkingSetSize", JAVA_LONG);

    public MTLDevice {
        if (handle == null || handle.address() == 0L) {
            throw new IllegalArgumentException("MTLDevice handle is null");
        }
    }

    public String name() {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            return ObjC.javaString(NAME.sendPtr(handle));
        }
    }

    public long maxBufferLength() {
        return MAX_BUFFER_LENGTH.sendLong(handle);
    }

    public long recommendedMaxWorkingSetSize() {
        return RECOMMENDED_MAX_WORKING_SET_SIZE.sendLong(handle);
    }

    public MTLCommandQueue newCommandQueue() {
        MemorySegment queue = NEW_COMMAND_QUEUE.sendPtr(handle);
        if (ObjC.isNil(queue)) {
            throw new IllegalStateException("newCommandQueue returned nil");
        }
        return new MTLCommandQueue(queue, nativeOwner);
    }

    public MTLFence newFence() {
        MemorySegment fence = NEW_FENCE.sendPtr(handle);
        if (ObjC.isNil(fence)) {
            throw new IllegalStateException("newFence returned nil");
        }
        return new MTLFence(fence);
    }

    public MTLFunction newFunction(final String mslSource, final String entryPoint) {
        return new MTLFunction(nativeOwner.compileFunction(mslSource, entryPoint));
    }
}
