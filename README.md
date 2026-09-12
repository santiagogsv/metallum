## Metallum
Metallum is an experimental rendering backend for Minecraft on macOS that uses Apple’s Metal API instead of OpenGL/Vulkan. It provides a more native rendering path and aims to improve performance and efficiency on Apple Silicon.

This project is still experimental. Performance, stability, and compatibility may vary depending on your system and installed mods. If you encounter bugs, please report them on GitHub.

Compatible with Sodium.

vibecoded as hell

## Requirements
- macOS 27 or newer
- Apple Silicon (M1 or newer)


## Swift backend

The current build is `0.0.24-swift.16.1`. Swift owns resource creation, shader compilation, render pipelines, copy passes, draw encoding, render-pass lifetime and presentation. The matching native library is bundled in the jar and loaded automatically. No `metallum.nativeLibrary` JVM argument is needed; that property remains a development override only. The Java fallback has been removed.

See [GPU diagnostics and command cleanup](docs/swift-diagnostics.md), [Metal 4 commands and compilation](docs/swift-metal4.md), [memory diagnostics](docs/swift-memory.md), and [bundled-library installation](docs/swift-packaging.md). Run `./gradlew build checkNative` with JDK 25 and the Swift 6.4/macOS 27 SDK toolchain to build the jar and run GPU-independent checks.

Java still adapts Minecraft/Blaze3D and schedules work during the incremental migration. Rendering now uses owned resource IDs and native command residency/lifetime tracking. The native renderer uses the Metal 4 compiler, queue, reusable command allocators, render/copy encoders and argument tables. Optional MetalFX spatial upscaling is available; see [setup and validation](docs/swift-metalfx.md). GPU validation and profiling remain necessary. See [the original architecture and migration plan](docs/swift-backend.md) for background.
