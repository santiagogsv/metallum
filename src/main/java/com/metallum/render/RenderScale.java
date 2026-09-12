package com.metallum.render;

public final class RenderScale {
    private RenderScale() {}
    public static double parse(String value) {
        try {
            double scale = Double.parseDouble(value);
            return Double.isFinite(scale) && scale >= 0.67 && scale < 1 ? scale : 1;
        } catch (NumberFormatException ignored) { return 1; }
    }
    public static int dimension(int pixels, double scale) { return Math.max(1, (int) Math.floor(pixels * scale)); }
}
