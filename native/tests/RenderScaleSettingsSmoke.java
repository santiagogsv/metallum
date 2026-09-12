import com.metallum.config.RenderScaleSettings;
import java.nio.file.*;

public class RenderScaleSettingsSmoke {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("metallum-settings-test-");
        Path file = directory.resolve("scale.txt");
        try {
            var settings = new RenderScaleSettings(file, "0.85");
            if (settings.percent() != 85) throw new AssertionError("Initial JVM setting");
            for (int value : new int[]{50, 100, 85, 75, 100, 50}) {
                settings.set(value);
                if (new RenderScaleSettings(file, "0.9").percent() != value) throw new AssertionError("Saved setting must override JVM seed");
            }
            for (int value : new int[]{49, 101}) {
                try { settings.set(value); throw new AssertionError("Invalid scale accepted"); }
                catch (IllegalArgumentException expected) { }
            }
            for (String corrupt : new String[]{"oops", "49", "101", "999999999999999"}) {
                Files.writeString(file, corrupt);
                if (new RenderScaleSettings(file, "0.85").percent() != 100) throw new AssertionError("Corrupt file must safely disable scaling");
            }
            try (var entries = Files.list(directory)) {
                if (entries.count() != 1) throw new AssertionError("Temporary settings files leaked");
            }
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory); }
        System.out.println("Render scale persistence, bounds and cleanup checks passed");
    }
}
