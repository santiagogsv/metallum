# Swift texture and sampler milestone — 0.0.24-swift.3

## Scope

This build continues the macOS 27-only migration with the installed Swift 6.4 / macOS 27 SDK, supported `@c` exports, and the existing explicit MSL 4.1 target. The prior device and buffer milestone remains intact.

When the Swift path is selected, `MetalGpuTexture`, `MetalGpuTextureView`, and `MetalGpuSampler` now allocate and own their native objects through Swift. Native resource IDs are device-local and distinct from buffer IDs. Five C exports cover texture creation, texture-view creation, sampler creation, borrowed-pointer lookup, and resource destruction. ABI version is now **3**.

`native/MetalResources.swift` contains Metal descriptor policy and resource operations. Java supplies Minecraft dimensions, format adaptation, cube/render-target booleans, sampling intent, and labels. Swift selects texture type, array length, usage, storage, hazard mode, mip filtering and LOD settings, and creates the Metal objects. The existing pixel-format mapping stays in Java because pipeline attachment descriptors also use it; that numeric ABI field is explicitly an `MTLPixelFormat` value, not an unlabelled backend-neutral enum.

## Ownership and cleanup

- Swift ARC owns textures and samplers in the device's resource table.
- Full-range views gain an independent resource ID retaining the existing texture; partial views use `MTLTexture.makeTextureView` with the correct mip and slice ranges for 2D, arrays, cubes, and cube arrays.
- The Java parent/view count remains in place so a texture closed by Minecraft stays alive while its existing views need it.
- All native destruction remains scheduled through the existing GPU-completion-based deferred queue. This milestone does not alter synchronization or submission timing.
- Cached borrowed object pointers let the current Java command encoders continue using the resources without an extra native lookup per draw.
- Closing an unused texture view no longer allocates a Metal view just to release it. Closed views and samplers reject further handle access; creating a view of a closed parent is rejected.
- Sampler anisotropy is bounded to Metal's 1–16 range. Existing min/mag, repeat/clamp, mip filtering, and LOD behavior is preserved for valid settings. Extreme finite LOD values saturate at Float's maximum instead of overflowing to infinity.

The default Java path remains available during migration, with the same closed-view fixes. New and old jar/native-library pairs are intentionally incompatible at the ABI check.

## Build and verification

With JDK 25 and the installed Swift/macOS 27 toolchain:

```sh
./gradlew build checkNative
```

`checkNative` depends on `buildNative` and runs two checks that do not require a GPU:

1. Actual Swift descriptor construction against the Metal SDK: 2D/array/cube/cube-array mapping, dimensions and mip limits, usage/storage/hazard settings, invalid cube inputs, sampler addressing/filtering/anisotropy and LOD edge cases.
2. Java FFM ownership against a C fixture: signatures, parent closure with surviving views, out-of-range mip rejection, incorrect resource type rejection, repeated/duplicate closure, thread ownership, device-before-resource shutdown, missing libraries, and old ABI rejection.

For real Swift/Metal allocation and native view-lifetime checks:

```sh
sh native/tests/smoke.sh
```

The C fixture is not a Metal implementation; its success does not establish GPU correctness. The actual Swift descriptor tests exercise the production descriptor policy but do not submit GPU work.

## Validation in this task

- Full Java mod build, access-widener validation, and Swift library build passed.
- Swift descriptor tests and Java/C ownership tests passed.
- Real native smoke program compiled, but could not create a Metal device in this execution environment (`Metal unavailable`). Successful texture allocation, GPU rendering and gameplay on this new version remain for the in-game check.
- Prior version `swift.2` was reported working by the user; that is the baseline, not proof of this version's correctness.

## Install and test

Quit Minecraft and replace the instance's old Metallum jar with `metallum-0.0.24-swift.3.jar`. Keep the existing `metallum.nativeLibrary` JVM argument: the library has been rebuilt at the same repository path, `build/native/libmetallum_native.dylib`.

The log should identify `ownership: Swift device + buffers + textures + samplers, ABI 3`. F3 continues to call the backend `Metal`, preserving Sodium's backend-name checks.

Test world loading/chunk traversal, water and transparent blocks, inventories and text, resource reload (F3+T), mipmap/anisotropic-filtering settings, resize/fullscreen, world exit/reopen, and shutdown. Repeat with the matching Sodium build. The native library is still separate from the jar; moving the jar to another Mac also requires the corresponding library and path setup.

## Remaining work

The pipeline subsystem still owns Java-created built-in presentation samplers and buffer-backed texel texture views; those are not covered by the Minecraft resource-adapter migration in this milestone. MSL compilation, pipeline caches, descriptor rebinding, and those remaining pipeline resources are the next contained step. Keep SPIR-V and SPIRV-Cross.

After that, move command encoding, Metal 4 residency/synchronization, and submission together; then move CAMetalLayer/drawable/frame presentation. Current Metal resource interfaces remain applicable to Metal 4. This milestone does not claim to use Metal 4 command queues or texture-view pools. Native packaging and removal of migration-only borrowed pointers come after those consumers are migrated and validated.

## References

- [Swift 6.4](https://developer.apple.com/swift/whats-new/)
- [Metal texture views and backing storage](https://developer.apple.com/documentation/metal/mtltexture/maketextureview%28pixelformat%3Atexturetype%3Alevels%3Aslices%3A%29)
- [Metal texture interfaces](https://developer.apple.com/documentation/Metal/MTLTexture)
