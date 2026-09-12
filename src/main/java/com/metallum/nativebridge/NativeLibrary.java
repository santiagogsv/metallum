package com.metallum.nativebridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Loads the library shipped with this jar; an explicit path remains a developer override. */
public final class NativeLibrary {
    private NativeLibrary() {}
    private static Path bundled;
    public static synchronized Path resolve() {
        String override = System.getProperty("metallum.nativeLibrary");
        if (override != null) return Path.of(override);
        if (bundled == null) bundled = extract();
        return bundled;
    }
    private static Path extract() {
        if (!System.getProperty("os.name").equals("Mac OS X") || !System.getProperty("os.arch").equals("aarch64")
                || Integer.parseInt(System.getProperty("os.version").split("\\.")[0]) < 27) {
            throw new IllegalStateException("Metallum requires Apple Silicon and macOS 27 or newer");
        }
        Path directory = null;
        Path library = null;
        try (var input = NativeLibrary.class.getResourceAsStream("/native/macos-arm64/libmetallum_native.dylib")) {
            if (input == null) throw new IOException("Bundled Swift Metal library is missing");
            directory = Files.createTempDirectory("metallum-native-");
            library = directory.resolve("libmetallum_native.dylib");
            Files.copy(input, library, StandardCopyOption.REPLACE_EXISTING);
            directory.toFile().deleteOnExit();
            library.toFile().deleteOnExit();
            return library;
        } catch (IOException failure) {
            try { if (library != null) Files.deleteIfExists(library); if (directory != null) Files.deleteIfExists(directory); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("Cannot extract bundled Swift Metal library", failure);
        }
    }
}
