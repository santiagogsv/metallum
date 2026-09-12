package com.metallum.nativebridge;

import java.util.ArrayList;
import java.util.List;

/** Value-only descriptor; enum fields use the documented Metal raw values. */
public final class NativePipelineDescriptor {
    private final long[] header = new long[11];
    private final List<long[]> attributes = new ArrayList<>();
    private final List<long[]> layouts = new ArrayList<>();

    public NativePipelineDescriptor(long color, long writeMask) {
        header[0] = color; header[1] = writeMask;
    }

    public void blend(long sourceRGB, long destRGB, long rgbOperation, long sourceAlpha, long destAlpha, long alphaOperation) {
        header[2] = 1;
        header[3] = sourceRGB; header[4] = destRGB; header[5] = rgbOperation;
        header[6] = sourceAlpha; header[7] = destAlpha; header[8] = alphaOperation;
    }

    public void attribute(long index, long format, long offset, long buffer) { attributes.add(new long[]{index, format, offset, buffer}); }
    public void layout(long buffer, long stride, long stepFunction, long stepRate) { layouts.add(new long[]{buffer, stride, stepFunction, stepRate}); }

    public long[] words() {
        if (attributes.size() > 31 || layouts.size() > 31) throw new IllegalArgumentException("Too many vertex entries");
        long[] result = new long[11 + 4 * (attributes.size() + layouts.size())];
        System.arraycopy(header, 0, result, 0, 11);
        result[9] = attributes.size(); result[10] = layouts.size();
        int offset = 11;
        for (long[] attribute : attributes) { System.arraycopy(attribute, 0, result, offset, 4); offset += 4; }
        for (long[] layout : layouts) { System.arraycopy(layout, 0, result, offset, 4); offset += 4; }
        return result;
    }
}
