import com.metallum.nativebridge.NativeLibrary;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Loads the real library from the assembled jar without requiring GPU access. */
public class PackagedLibrarySmoke {
    public static void main(String[] args) throws Throwable {
        System.clearProperty("metallum.nativeLibrary");
        Path extracted = NativeLibrary.resolve();
        if (!Files.isRegularFile(extracted) || !extracted.equals(NativeLibrary.resolve())) throw new AssertionError("Unstable extraction");
        try (var bytes = NativeLibrary.class.getResourceAsStream("/native/macos-arm64/libmetallum_native.dylib")) {
            if (bytes == null || !Arrays.equals(bytes.readAllBytes(), Files.readAllBytes(extracted))) throw new AssertionError("Extracted library differs");
        }
        try (Arena arena = Arena.ofConfined()) {
            var symbols = SymbolLookup.libraryLookup(extracted, arena);
            var abi = Linker.nativeLinker().downcallHandle(symbols.findOrThrow("metallum_abi_version"), FunctionDescriptor.of(ValueLayout.JAVA_INT));
            if ((int) abi.invokeExact() != 20) throw new AssertionError("Wrong packaged ABI");
            symbols.findOrThrow("metallum_command_buffer_create");
            symbols.findOrThrow("metallum_copy_pass");
            symbols.findOrThrow("metallum_render_pass_create");
            symbols.findOrThrow("metallum_render_command");
            symbols.findOrThrow("metallum_render_indexed_batch");
            symbols.findOrThrow("metallum_render_bytes");
            symbols.findOrThrow("metallum_texture_info");
            symbols.findOrThrow("metallum_command_debug");
            symbols.findOrThrow("metallum_device_info");
            symbols.findOrThrow("metallum_device_name");

            symbols.findOrThrow("metallum_layer_create");
            symbols.findOrThrow("metallum_layer_configure");
            symbols.findOrThrow("metallum_present");
            symbols.findOrThrow("metallum_fence_create");
            symbols.findOrThrow("metallum_submit");
            symbols.findOrThrow("metallum_submission_wait");
            symbols.findOrThrow("metallum_memory_snapshot");
            symbols.findOrThrow("metallum_diagnostics_snapshot");
            symbols.findOrThrow("metallum_upscale");
            symbols.findOrThrow("metallum_upscale_clear");
            symbols.findOrThrow("metallum_depth_state_create");
            symbols.findOrThrow("metallum_present_sampler_create");
            symbols.findOrThrow("metallum_buffer_texture_create");
        }
        System.setProperty("metallum.nativeLibrary", "/nonexistent/developer-override.dylib");
        if (!NativeLibrary.resolve().equals(Path.of("/nonexistent/developer-override.dylib"))) throw new AssertionError("Override ignored");
        System.clearProperty("metallum.nativeLibrary");
        if (!NativeLibrary.resolve().equals(extracted)) throw new AssertionError("Bundled path lost");
        System.out.println("Packaged Swift library extraction and ABI smoke test passed");
    }
}
