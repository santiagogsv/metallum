## Metallum
Metallum is an experimental rendering backend for Minecraft on macOS that uses Apple’s Metal API instead of OpenGL/Vulkan. It provides a more native rendering path and aims to improve performance and efficiency on Apple Silicon.

This project is still experimental. Performance, stability, and compatibility may vary depending on your system and installed mods. If you encounter bugs, please report them on GitHub.

Compatible with Sodium.

vibecoded as hell

## Requirements
- macOS 27 or newer
- Apple Silicon (M1 or newer)


## Swift backend

The current build is `0.0.24-swift.10`. Swift owns resource creation, shader compilation and render pipelines. The matching native library is bundled in the jar and loaded automatically. No `metallum.nativeLibrary` JVM argument is needed; that property remains a development override only. The Java fallback has been removed.

See [Swift copy passes and validation](docs/swift-copies.md), [memory diagnostics](docs/swift-memory.md), and [bundled-library installation](docs/swift-packaging.md). Run `./gradlew build checkNative` with JDK 25 and the Swift 6.4/macOS 27 SDK toolchain to build the jar and run GPU-independent checks.

Java still adapts Minecraft/Blaze3D and controls command encoding and presentation during the incremental migration. See [the original architecture and migration plan](docs/swift-backend.md) for background.
