import com.metallum.nativebridge.NativeMetalDevice;
import java.nio.file.Path;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicReference;

public final class NativeDeviceSmoke {
    public static void main(String[] args) {
        Path library = Path.of(args[0]);
        for (int i = 0; i < 100; i++) {
            NativeMetalDevice device = new NativeMetalDevice(library);
            try (device) {
                if (device.borrowedDevice().address() == 0) throw new AssertionError("Null borrowed device");
                NativeMetalDevice.Buffer shared = device.createBuffer(64, true);
                try (shared; var gpuOnly = device.createBuffer(64, false)) {
                    if (shared.length() != 64 || shared.borrowedBuffer().address() == 0) throw new AssertionError("Invalid shared buffer");
                    var memory = shared.contents().reinterpret(64);
                    memory.set(ValueLayout.JAVA_LONG, 0, 0x123456789L);
                    if (memory.get(ValueLayout.JAVA_LONG, 0) != 0x123456789L) throw new AssertionError("Mapping corrupted");
                    if (gpuOnly.contents().address() != 0) throw new AssertionError("Private memory exposed");
                    AtomicReference<Throwable> outcome = new AtomicReference<>();
                    Thread worker = new Thread(() -> {
                        try { shared.close(); outcome.set(new AssertionError("Cross-thread close accepted")); }
                        catch (IllegalStateException expected) { }
                        catch (Throwable failure) { outcome.set(failure); }
                    });
                    worker.start();
                    try { worker.join(); } catch (InterruptedException e) { throw new AssertionError(e); }
                    if (outcome.get() != null) throw new AssertionError(outcome.get());
                }
                shared.close();
                try { shared.contents(); throw new AssertionError("Closed buffer accepted"); }
                catch (IllegalStateException expected) { }
                try { device.createBuffer(0, true); throw new AssertionError("Zero allocation accepted"); }
                catch (IllegalArgumentException expected) { }
            }
            device.close(); // Java close is idempotent; the C destroy operation is not.
            try {
                device.borrowedDevice();
                throw new AssertionError("Use after close accepted");
            } catch (IllegalStateException expected) {
                // Expected.
            }
        }
        NativeMetalDevice owner = new NativeMetalDevice(library);
        var survivor = owner.createBuffer(64, true);
        owner.close();
        survivor.close(); // Already destroyed by its device, must not call into an unloaded library.
        try { survivor.borrowedBuffer(); throw new AssertionError("Closed owner accepted"); }
        catch (IllegalStateException expected) { }
        try {
            new NativeMetalDevice(library.resolveSibling("does-not-exist.dylib"));
            throw new AssertionError("Missing library accepted");
        } catch (IllegalStateException expected) {
            // An explicitly selected but broken native path must fail, not silently fall back.
        }
        if (args.length > 1) {
            try { new NativeMetalDevice(Path.of(args[1])); throw new AssertionError("Old ABI accepted"); }
            catch (IllegalStateException expected) {
                if (expected.getCause() == null || !expected.getCause().getMessage().contains("Expected Metallum native ABI 2")) {
                    throw new AssertionError("Unexpected ABI error", expected);
                }
            }
        }
        System.out.println("Java FFM ownership smoke test passed");
    }
}
