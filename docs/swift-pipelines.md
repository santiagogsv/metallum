# Swift render pipelines — milestone 5

Version: `0.0.24-swift.5`, native ABI 5. Target: Apple Silicon, macOS 27, Swift 6.4 in Swift 6 language mode, MSL 4.1.

## What changed

Swift now creates and owns render pipeline states for Minecraft and the built-in clear/presentation passes. Java translates Blaze3D vertex layouts, attachment formats, color masks and blending into a compact value-only descriptor. One C call receives that descriptor and vertex/fragment resource IDs; Swift constructs the Metal descriptors and compiles the state. No Objective-C descriptor objects cross this new boundary.

The existing depth-enabled and depth-disabled variants, vertex buffer slots, attribute offsets, instance rates and blending mappings are preserved. Pipeline compilation errors return bounded UTF-8 diagnostics. A failure compiling the second variant releases the first. Close is idempotent, and the built-in ownership bookkeeping handles Metal returning a shared state object for identical compilations.

Shader functions remain cached by the device. Pipeline resource ownership is independent of the function handles used to create them. Minecraft/Fabric/Sodium integration, SPIR-V and SPIRV-Cross remain in place.

## Boundary and remaining work

`native/MetalPipelines.swift` implements the native descriptor conversion and pipeline creation. `NativePipelineDescriptor.java` serializes 13 header words followed by four words per attribute/layout. `native/include/metallum.h` documents the ABI. Swift checks entry counts, vertex slot limits, duplicate entries and layout references before compilation.

Command encoding still uses borrowed pipeline pointers through the existing Java Metal encoder. This milestone adds no native calls to the per-draw path. Depth-stencil states, command queues/encoding, frame retirement and CAMetalLayer still need migration. Metal 4 command submission is not implemented in this version; its synchronization and resource-lifetime changes should be migrated together.

The next bounded step is native depth-stencil state ownership and removal of remaining resource-factory calls from Java. Then move pass encoding and submission/retirement together behind a coarse-grained boundary, followed by presentation/frame lifecycle.

## Install and test

1. Quit Minecraft fully.
2. Replace the previous Metallum jar with `metallum-0.0.24-swift.5.jar`; keep only one Metallum jar installed.
3. Keep the existing `-Dmetallum.nativeLibrary=.../build/native/libmetallum_native.dylib` JVM argument. That library has already been rebuilt at the existing path.
4. Start Minecraft. The backend log should include `Swift resources + render pipelines, ABI 5`.
5. Load the same world, reload resources with F3+T, inspect translucent water/leaves and UI, resize/fullscreen, and leave/re-enter the world.

The jar and native library must both be ABI 5. The library is still external to the jar. Removing the native-library argument selects the original Java path and does not test the Swift migration.

## Validation

- `build checkNative` passed: full Java/Fabric build, Swift native library, production Swift descriptor checks, and Java FFM ownership tests against a CPU-only C fixture.
- Descriptor checks cover attachment/blend state, attribute offsets, buffer slots, instance stride/rate and malformed descriptors.
- FFM checks cover cross-device and closed-function rejection, pipeline ownership after function release/cache clearing, idempotent close and ABI mismatch rejection.
- The real Metal smoke program compiled, but execution stopped at device creation with `Metal unavailable` in this tool environment. The new pipeline GPU creation and gameplay are not validated here. The user's successful previous-version resource reload is the baseline, not evidence that this new version passes.
- Existing Gradle deprecation and FSEvents warnings remain; neither prevents the build.

## API references

- [Apple: makeRenderPipelineState(descriptor:)](https://developer.apple.com/documentation/metal/mtldevice/makerenderpipelinestate(descriptor:))
- [Apple: Metal feature tables and vertex limits](https://developer.apple.com/metal/feature-sets/)
