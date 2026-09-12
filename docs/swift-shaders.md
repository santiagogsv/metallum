# Swift shader compilation — 0.0.24-swift.4

## Implemented

The native path now compiles MSL into Metal libraries and functions in Swift, including Minecraft pipeline shaders and Metallum's built-in presentation/clear shaders. The target remains Apple Silicon, macOS 27, Swift 6.4, and MSL 4.1. Swift uses supported `@c` exports and `MTLDevice.makeLibrary(source:options:)` with explicit MSL 4.1 compile options. Math and optimization defaults remain unchanged.

`MetalShaders.swift` owns the compile policy and error boundary. Each device caches successfully compiled libraries by their complete MSL source string, so multiple entry points from the same source can reuse the library. Failed compilations are not cached. This cache retains source strings and library objects until resource reload or device shutdown; there is no disk cache or asynchronous compilation in this milestone.

Functions use the existing Swift resource-ID table. `MTLFunction` provides one ownership wrapper for both the native migration path and the legacy Java path. Minecraft's function cache owns these wrappers. Built-in pipeline creation closes temporary function wrappers with try-with-resources, including partial failure. Java no longer manually releases Swift-owned shader function pointers.

Resource reload keeps the existing order: wait for submitted GPU work, close compiled pipelines and intermediary shaders, close cached functions, and clear Swift's library cache. Clearing libraries does not destroy separately owned function resources. Device shutdown also releases its native cache.

## Shader abstraction cleanup

`MetalCrossShaderCompiler` now builds an ordered list of backend-owned `ShaderBinding` records. `MojangShaderRebinding` is the single conversion point to Mojang's `VulkanBindGroupLayout.Entry` contract. Binding order, resource kinds, names, texel formats, stage reflection, and push-constant indexing remain unchanged.

GLSL preprocessing, Mojang intermediary shader modules, SPIR-V and SPIRV-Cross stay in use. Their existing package names may contain `vulkan`; they remain Java adaptation dependencies and are not exposed to the native shader API. Sodium integration and the indirect-draw producer contract are unchanged.

## Error handling and ABI

ABI version is **4**. Two new C functions create a function and clear cached libraries. Creation accepts UTF-8 source and entry-point strings and returns an owning resource ID, or zero on failure. A caller-owned error buffer receives a terminated, capacity-bounded UTF-8 diagnostic; truncation preserves complete Unicode scalars. Swift errors do not unwind through C/Java.

Java includes the native diagnostic in its exception message, allowing errors to remain visible through Minecraft's initialization error reporting. Borrowed function pointers are cached and guarded by their owner; shader lookup at draw time does not add a native compile call.

## Validation

- `./gradlew build checkNative` passes: complete Java build, access-widener check, Swift native build and GPU-independent native checks.
- Actual Swift tests verify MSL 4.1 selection, UTF-8 error truncation, null-context failure reporting, and zero/one-byte error-buffer handling, alongside prior resource descriptor checks.
- Java/C-fixture tests cover successful function ownership, invalid source/missing-entry diagnostic propagation, cache-clear with live functions, stale ABI rejection, and prior resource lifetime cases. The C fixture does not compile MSL.
- Real C/Swift Metal smoke tests now include two entries from one source, cache clearing, surviving function access, invalid source, and missing entry. The test program compiles but exits with `Metal unavailable` in this execution environment. Actual MSL compilation/rendering of this version still needs the in-game test.
- Version swift.3 was reported working by the user; the new shader changes are not covered by that prior validation.

## Upgrade

Quit Minecraft. Replace the old jar with `metallum-0.0.24-swift.4.jar` and keep your existing `metallum.nativeLibrary` JVM argument. The matching native library has been rebuilt at the same `build/native/libmetallum_native.dylib` path. Jar and library must match ABI 4.

Look for `ownership: Swift resources + MSL compiler, ABI 4` in the startup log. Test world load, chunk traversal, UI/transparency, F3+T resource reload, window resizing, world reopen and shutdown, with and without the matching Sodium build. Resource reload is especially relevant to this version's function/library teardown.

## Remaining work

This is the shader-compilation portion of the pipeline migration. Render-pipeline state descriptors/creation, depth-stencil states, pipeline caches, built-in samplers, and buffer-backed texel views still need native ownership. They should move behind a small descriptor-based native API next. Then migrate Metal 4 command encoding, residency/synchronization and submission together, followed by CAMetalLayer/drawables/presentation.

The old Java path remains a temporary comparison path. Native-library packaging and removal of borrowed Objective-C handles follow migration of their consumers. No runtime performance improvement is claimed from this milestone.

## References

- [Apple Metal compile options](https://developer.apple.com/documentation/metal/mtlcompileoptions)
- [Metal library creation APIs](https://developer.apple.com/documentation/metal/mtldevice/makelibrary%28source%3Aoptions%3Acompletionhandler%3A%29)
