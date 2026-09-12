# Swift buffer milestone — 0.0.24-swift.2

## Target and result

This development build targets **Apple Silicon, macOS 27, Swift 6.4, and MSL 4.1**. The native compiler uses Swift 6 language mode and an explicit `arm64-apple-macosx27.0` deployment target. It uses the supported `@c` export attribute in place of `@_cdecl`. The installed SDK is macOS 27.0; future work should use the newest APIs supported by the selected macOS 27 SDK/toolchain, checking availability rather than guessing version numbers.

With `metallum.nativeLibrary` set, Swift now owns **the device and all buffers allocated by the renderer**, including shared/private buffers, transient GPU blocks, and dynamic uniform backings. The C boundary accepts lengths and a CPU-access flag; storage mode and hazard options are chosen inside Swift. No Vulkan flags or Metal enum values cross the buffer ABI.

## Ownership and performance

- Swift's device context stores buffers by monotonically increasing, device-local 64-bit IDs. Zero signals allocation failure. Missing IDs return null; destruction of a missing ID is harmless.
- `NativeMetalDevice.Buffer` enforces render-thread access, tracks closure, and caches a borrowed Metal object pointer once at allocation. Draw calls do not incur a new FFM lookup merely to obtain that pointer.
- `MTLBuffer` dispatches ownership and memory access to the Swift owner on the native path. On the existing Java path it retains the original Objective-C behavior.
- `MetalGpuBuffer` queues the complete buffer owner's close action instead of releasing a raw Objective-C pointer. The existing submission-completion and deferred-retirement mechanism remains responsible for GPU-safe timing.
- Dynamic backings retain their owners while pooled and close through the same ownership interface at shutdown. Transient views continue borrowing the block allocation; closing a view never destroys the block.
- Device destruction releases remaining Swift-owned buffers after the existing backend waits for GPU work. Later Java buffer closure does not call an unloaded library. Borrowed memory and object pointers must never be used after retirement/device destruction.
- Invalid sizes are rejected, zero-length logical buffers receive a minimal 16-byte backing, and failed CPU mapping initialization releases its newly allocated buffer.

## Shader version correction

`MetalCrossShaderCompiler` now requests MSL 4.1 using SPIRV-Cross's **decimal `40100`** encoding. The old `0x040000` was Apple's enum encoding and was incorrect for SPIRV-Cross. Actual Metal source compilation now supplies `MTLCompileOptions.languageVersion = 4.1`, using Apple's separate `(4 << 16) | 1` representation.

GLSL preprocessing, SPIR-V, SPIRV-Cross reflection, and the Minecraft/Sodium binding contracts remain in place. This changes the selected language target; it does not replace shader compilation or move pipeline ownership to Swift yet.

## Upgrade and test

1. Quit Minecraft before swapping development artifacts.
2. Replace the instance's old Metallum jar with `build/libs/metallum-0.0.24-swift.2.jar`. Do not install both.
3. Keep your existing `metallum.nativeLibrary` JVM argument. The native library has been rebuilt at the same `build/native/libmetallum_native.dylib` path.
4. Confirm the startup log contains `ownership: Swift device + buffers, ABI 2`.
5. Repeat world loading, moving through chunks, resource reload (F3+T), resize/fullscreen, world unload/reopen, and quitting. Test both with and without the matching Sodium version.

**The new jar and native library must be used together.** ABI 2 intentionally rejects a stale ABI 1 library, and the old jar rejects ABI 2. The opt-in argument is unchanged; F3 still reports the backend name as Metal because Sodium relies on that name.

With Java 25 and Xcode's Swift toolchain available:

```sh
./gradlew build buildNative
sh native/tests/ffi_contract.sh
sh native/tests/smoke.sh
```

The first script is a CPU-only Java FFM contract test against a C fixture. It checks shared-memory access, private-memory rejection, repeated lifecycle operations, duplicate close, use-after-close rejection, cross-thread close rejection, device-before-buffer closure, missing library handling, and old-ABI rejection. It is not evidence that Metal executed successfully.

The second script tests the actual Swift library and Metal device, including native buffer allocation, contents access, invalid arguments, and SDK indirect-layout assertions. It needs an accessible GPU.

## Validation

- Full offline Gradle mod build, access-widener validation, and Swift library build passed with Java 25.0.1 / Swift 6.4.
- CPU-only Java FFM contract tests passed.
- The real Metal smoke program compiled, but stopped with `Metal unavailable` in this execution environment. The new buffer path and MSL 4.1 shaders still need in-game validation on your Mac.
- Milestone 1 had user-reported successful rendering, resource reload, and resizing; that evidence does not automatically validate these new buffer and shader changes.
- Existing Gradle deprecation and filesystem watcher warnings remain non-fatal.

## Remaining migration

Texture/view/sampler ownership and pipeline compilation are the next resource slice. Then move command encoding, residency/synchronization, and submission together into Swift using the macOS 27 Metal 4 APIs. Layer attachment, drawable acquisition, presentation, and frame lifecycle follow with explicit AppKit threading. Current command submission still uses the existing Metal command queue/encoder path; this build does not claim to implement Metal 4 command submission.

The borrowed Objective-C pointer exports are temporary adapters for Java's existing command encoders. Remove them only once their consumers have moved to native resource IDs. Keep native-library packaging and removal of the opt-in flag for a later validated milestone. No performance improvement is claimed from this change alone.

## References

- [Swift C-compatible functions (SE-0495)](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0495-cdecl.md)
- [Apple MSL 4.1 language version](https://developer.apple.com/documentation/metal/mtllanguageversion/version4_1)
- [SPIRV-Cross version encoding](https://github.com/KhronosGroup/SPIRV-Cross/blob/main/spirv_msl.hpp)
