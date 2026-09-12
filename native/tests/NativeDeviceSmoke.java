import com.metallum.nativebridge.NativeMetalDevice;
import java.nio.file.Path;

public final class NativeDeviceSmoke {
    public static void main(String[] args) {
        Path library = Path.of(args[0]);
        for (int i = 0; i < 100; i++) {
            NativeMetalDevice device = new NativeMetalDevice(library);
            try (device) {
                if (device.borrowedDevice().address() == 0) throw new AssertionError("Null borrowed device");
            }
            device.close(); // Java close is idempotent; the C destroy operation is not.
            try {
                device.borrowedDevice();
                throw new AssertionError("Use after close accepted");
            } catch (IllegalStateException expected) {
                // Expected.
            }
        }
        try {
            new NativeMetalDevice(library.resolveSibling("does-not-exist.dylib"));
            throw new AssertionError("Missing library accepted");
        } catch (IllegalStateException expected) {
            // An explicitly selected but broken native path must fail, not silently fall back.
        }
        System.out.println("Java FFM ownership smoke test passed");
    }
}
