package com.metallum.config;

import com.metallum.render.RenderScale;
import java.io.IOException;
import java.nio.file.*;

/** One small per-instance setting; the JVM argument seeds an unsaved configuration. */
public final class RenderScaleSettings {
    private final Path file;
    private int percent;
    public RenderScaleSettings(Path file, String initialScale) {
        this.file = file;
        percent = (int) Math.round(RenderScale.parse(initialScale) * 100);
        try {
            if (Files.exists(file)) {
                int saved = Integer.parseInt(Files.readString(file).trim());
                percent = saved >= 50 && saved <= 100 ? saved : 100;
            }
        } catch (IOException | NumberFormatException ignored) { percent = 100; }
    }
    public int percent() { return percent; }
    public double scale() { return percent / 100.0; }
    public void set(int value) throws IOException {
        if (value < 50 || value > 100) throw new IllegalArgumentException("Render scale must be 50–100");
        percent = value;
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "metallum-scale-", ".tmp");
        try {
            Files.writeString(temporary, Integer.toString(value) + "\n");
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
