## Metallum
Metallum is an experimental rendering backend for Minecraft on macOS that uses Apple’s Metal API instead of OpenGL/Vulkan. It provides a more native rendering path and aims to improve performance and efficiency on Apple Silicon.

This project is still experimental. Performance, stability, and compatibility may vary depending on your system and installed mods. If you encounter bugs, please report them on GitHub.

Compatible with Sodium.

vibecoded as hell

## Requirements
- macOS 27 or newer
- Apple Silicon (M1 or newer)


## Swift backend development

An opt-in Swift device ownership bridge is available as the first incremental migration milestone. See [the architecture, build instructions, migration plan, and validation limits](docs/swift-backend.md). The existing Java renderer remains the default.

The current migration build is `0.0.24-swift.5`: Swift 6.4, macOS 27, MSL 4.1, Swift resource ownership, native shader compilation/library caching, and Swift render-pipeline ownership. See [milestone 5](docs/swift-pipelines.md) for scope, upgrade and test instructions. Run `./gradlew build checkNative` to build and run GPU-independent native checks.
