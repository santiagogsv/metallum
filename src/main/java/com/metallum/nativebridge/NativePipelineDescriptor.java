package com.metallum.nativebridge;

import java.util.ArrayList;
import java.util.List;

/** Value-only descriptor; enum fields use the documented Metal raw values. */
public final class NativePipelineDescriptor {
    private final long[] header = new long[13];
    private final List<long[]> attributes = new ArrayList<>();
    private final List<long[]> layouts = new ArrayList<>();

    public NativePipelineDescriptor(long color, long depth, long stencil, long writeMask) {
        header[0] = color; header[1] = depth; header[2] = stencil; header[3] = writeMask;
    }

    public void blend(long sourceRGB, long destRGB, long rgbOperation, long sourceAlpha, long destAlpha, long alphaOperation) {
        header[4] = 1;
        header[5] = sourceRGB; header[6] = destRGB; header[7] = rgbOperation;
        header[8] = sourceAlpha; header[9] = destAlpha; header[10] = alphaOperation;
    }

    public void attribute(long index, long format, long offset, long buffer) { attributes.add(new long[]{index, format, offset, buffer}); }
    public void layout(long buffer, long stride, long stepFunction, long stepRate) { layouts.add(new long[]{buffer, stride, stepFunction, stepRate}); }

    public long[] words() {
        if (attributes.size() > 31 || layouts.size() > 31) throw new IllegalArgumentException("Too many vertex entries");
        long[] result = new long[13 + 4 * (attributes.size() + layouts.size())];
        System.arraycopy(header, 0, result, 0, 13);
        result[11] = attributes.size(); result[12] = layouts.size();
        int offset = 13;
        for (long[] attribute : attributes) { System.arraycopy(attribute, 0, result, offset, 4); offset += 4; }
        for (long[] layout : layouts) { System.arraycopy(layout, 0, result, offset, 4); offset += 4; }
        return result;
    }
}
