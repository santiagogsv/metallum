package com.metallum.mtl;

import com.metallum.objc.Msg;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public final class MTLTexture {
    private static final Msg PIXEL_FORMAT = Msg.of("pixelFormat", JAVA_LONG);
    private static final Msg WIDTH = Msg.of("width", JAVA_LONG);
    private static final Msg HEIGHT = Msg.of("height", JAVA_LONG);
    private MTLTexture() {
    }

    public static long pixelFormat(final MemorySegment texture) {
        return PIXEL_FORMAT.sendLong(texture);
    }

    public static long width(final MemorySegment texture) {
        return WIDTH.sendLong(texture);
    }

    public static long height(final MemorySegment texture) {
        return HEIGHT.sendLong(texture);
    }

}
