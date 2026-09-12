# Swift-only resources and bundled library — milestone 6

Version `0.0.24-swift.6`, ABI 6. Apple Silicon, macOS 27 or newer, Swift 6.4 / language mode 6, MSL 4.1. The build requires the macOS 27 SDK.

## Install

1. Quit Minecraft fully and replace the old Metallum jar with `metallum-0.0.24-swift.6.jar`.
2. Remove the `-Dmetallum.nativeLibrary=...` argument from Prism Launcher. The matching native library is now inside the jar.
3. Launch, load the world, reload resources with F3+T, check water/transparency and UI, resize/fullscreen, then leave and re-enter the world.

The log should report `Swift resources + render pipelines, ABI 6`. The explicit native-library property still exists solely as a development override; an invalid override fails rather than silently selecting another implementation. A separate native-access argument, if already configured, can remain.

## Changes and rationale

- Swift is now required. Removed Java fallback device/resource/shader/pipeline creation, so there is only one resource implementation to maintain.
- The build packages its matching dylib at `native/macos-arm64/libmetallum_native.dylib`. Startup extracts it into a private, process-specific temporary directory and reuses the path. Normal JVM shutdown removes the extracted file/directory. Abrupt termination can leave a temporary directory for OS cleanup.
- Swift now creates Minecraft and built-in depth states, presentation samplers, and buffer-backed texel textures. The existing depth comparison/write behavior, non-mipmapped presentation sampling, texel alignment policy and deferred GPU-safe resource retirement are preserved.
- Texel views cross the ABI using buffer IDs and values, with bounds/alignment checks before creating a Metal texture. They receive independent resource IDs and are retired after submitted work.
- Deleted 12 unused Java descriptor/enum/helper classes: MTLDepthStencilDescriptor, MTLRenderPipelineDescriptor, MTLResourceOptions, MTLSamplerDescriptor, MTLTextureDescriptor, MTLVertexDescriptor, MTLHazardTrackingMode, MTLSamplerAddressMode, MTLSamplerMipFilter, MTLStorageMode, MTLTextureType, MTLTextureUsage.
- Removed legacy compiler/allocation methods and selectors from MTLDevice, texture-view creation from MTLTexture, and duplicate ownership branches from the Minecraft adapters.

Minecraft/Fabric/Sodium integration and SPIR-V/SPIRV-Cross remain unchanged. Java still translates Minecraft formats into Metal values. Command encoders, queues, fences, completion callbacks and Cocoa/CAMetalLayer still use the existing bridge and cannot yet be deleted. This is not a Metal 4 command submission migration.

## Build and validation

Run `./gradlew build checkNative` with JDK 25 and the Swift 6.4/macOS 27 toolchain. The jar build now depends on the native build; there is no Java-only build fallback.

Verified in this milestone:

- Full Java/Fabric and Swift build.
- Production Swift descriptor checks for pipeline, texture, sampler and depth-state policy.
- Java FFM tests against a CPU C fixture, including new state/texel-view calls, bounds/closed-owner checks and ABI mismatch rejection.
- Actual assembled-jar extraction, byte equality, loading the real Swift library and resolving ABI 6 exports without GPU access.
- Removed classes are absent from the jar; the bundled dylib matches the build output. The local ad-hoc code signature validates. This is not Developer ID signing/notarization.
- The real Metal smoke program compiles, but the tool environment returns `Metal unavailable` when creating a device. New GPU resource creation and Minecraft gameplay require the user's test; the previous successful version is the baseline only.

Existing Gradle deprecation and FSEvents warnings remain non-blocking.

## Next work

Move pass encoding, command submission, synchronization and deferred retirement into Swift together. Adopt Metal 4 command queues/allocators/residency with that migration, then move CAMetalLayer/frame presentation. Only after those callers move can the Java ObjC/Msg/ObjCBlock layer be removed.

API references: [depth state descriptors](https://developer.apple.com/documentation/metal/mtldepthstencildescriptor), [buffer-backed Metal textures](https://developer.apple.com/documentation/metal/mtlbuffer).
