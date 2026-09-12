# Incremental Swift backend

## Current architecture

- Fabric entry point: `Metallum`, the mixin configuration/plugin, and the preferred-graphics-API mixin select `MetalBackend`. Access wideners expose Minecraft integration points.
- Window/device adaptation: `MetalBackend` sets GLFW's no-client-API hint, obtains Cocoa window/view pointers, creates the device and attaches `CAMetalLayer`. `MetalDevice` implements Blaze3D's `GpuDeviceBackend` and owns factories, pipeline/shader caches, and a command encoder.
- Resources: `MetalGpuBuffer`, `MetalGpuTexture`, texture views and samplers adapt Minecraft resources to `mtl` wrappers. `MetalTransientMemory` and `MetalDestructionQueue` handle transient allocations and delayed destruction.
- Commands: `MetalRenderPass` implements draw state and bindings. `MetalCommandEncoder` coordinates render/blit encoders, fences, completion blocks, three submissions in flight, deferred destruction, transient rotation, and presentation copies.
- Frames: `MetalSurface` configures the layer and delegates presentation to `MetalCommandEncoder`; `present()` submits pending work. Cocoa and layer access still run through Java.
- Native calls: `mtl/*` and `objc/*` bind Metal and Objective-C through Java FFM, selectors, `objc_msgSend`, and manual retain/release. There was no compiled native target.
- Shaders: Minecraft GLSL/preprocessing -> Mojang `GlslCompiler` / `IntermediaryShaderModule` -> SPIR-V -> LWJGL SPIRV-Cross -> MSL -> Metal pipeline compilation. The compiler's `rebind` contract consumes `VulkanBindGroupLayout.Entry`. Runtime pipeline bindings already use Metallum's own `ResourceBinding`.
- Sodium: mixins select `VK_INDIRECT` and instantiate `MetalDrawContext`, which extends Sodium's `VKIndirectContext`. This supplies compatible draw data; it does not mean the renderer submits through Vulkan. Preserve this adapter until a verified replacement exists.
- `telemetry/` is a separate service; it is unrelated to this renderer milestone.

## Build

The checked-in wrapper selects Gradle 9.4.1. The project requests Fabric Loom 1.16-SNAPSHOT (resolved to 1.16.3 during validation), Minecraft 26.2, Fabric Loader 0.19.3, Sodium mc26.2-0.9.1-fabric, and Java release 25. Dependencies come from Fabric/Mojang, Maven Central/plugin repositories, and Modrinth. The build expands the mod version, validates the access widener, and creates the mod and sources jars.

`buildNative` is a separate opt-in task using the installed `xcrun swiftc`. It produces `build/native/libmetallum_native.dylib` for the host architecture and SDK. The ordinary Java build does not require Swift. Native packaging, signing, a minimum macOS deployment target, and reproducible toolchain pinning are deliberately not implemented yet; this is a local development library, not a distributable native backend.

## Milestone 1 implemented

Swift owns the default Metal device in an ARC-managed `DeviceContext`. Four C exports provide ABI version, context creation, a temporary borrowed Metal pointer, and destruction. The authoritative contract is `native/include/metallum.h`. No Swift layouts or exceptions cross the ABI.

`NativeMetalDevice` loads a user-selected absolute library path through FFM, checks the ABI, owns the context, rejects use after close, and closes once. Its confined arena keeps the library loaded until context destruction. All calls belong on the render thread. Swift uses `@_cdecl` for these C exports; this remains an underscored compiler feature and is another reason to pin the release toolchain before shipping.

`MetalBackend` selects this path only when the JVM property `metallum.nativeLibrary` is present. Missing/broken explicitly selected libraries fail initialization; they do not silently select the old backend. `MetalDevice` runs the selected owner's release action after its existing GPU/resource teardown. Failed initialization releases the Swift owner; failure in the critical shader loader also attempts backend cleanup. Existing partial-constructor cleanup and layer retain/release behavior still need a broader lifecycle audit before promotion to default.

The borrowed Objective-C pointer is an explicit migration seam, not the final ABI. Existing Java resource, pipeline and command wrappers use it without owning or releasing the Swift-owned device reference. Moving all those callers at once would make this first milestone much harder to validate.

`MetalIndirectArguments` replaces two Vulkan struct-size dependencies with the Metal 16-byte and 20-byte indirect argument layouts. It preserves the bytes expected from Sodium. The native smoke test checks those sizes against the installed Metal SDK. SPIR-V, SPIRV-Cross, shader bindings and mixins are unchanged.

## Run locally

With JDK 25 and the Swift toolchain available:

```sh
./gradlew build buildNative
sh native/tests/smoke.sh
```

Add this JVM argument to a Minecraft development launch (replace the path):

```text
-Dmetallum.nativeLibrary=/absolute/path/to/metallum/build/native/libmetallum_native.dylib
--enable-native-access=ALL-UNNAMED
```

The property belongs to the Minecraft JVM, not just the Gradle daemon. Removing it selects the existing Java device creation path. The library is not included in the mod jar. Paths containing spaces must be quoted according to the launcher's argument format.

The smoke tests exercise C linkage, real Metal allocation through the borrowed object, repeated create/destroy, FFM signatures, Java close idempotence, use-after-close rejection, and missing-library rejection. They require an accessible Metal device and fail rather than claim success when none is available.

## Next milestones

1. Move buffer creation, mapping and destruction into the Swift context using opaque resource handles and small backend-owned descriptors. Preserve Blaze3D usage semantics and deferred GPU-safe destruction; test shared/private buffers and copies. Then migrate textures/views/samplers and pipeline ownership.
2. Isolate Mojang's shader rebinding behind an adapter that translates a backend-neutral binding list at that boundary. Preserve shared vertex/fragment indices, texel-buffer formats, push constants, and Sodium shader behavior. Keep SPIR-V and SPIRV-Cross; replacing their working preprocessing/rebinding merely because a package is named `vulkan` adds risk. Move native-facing MSL and binding descriptors into the native pipeline API once those contracts are tested.
3. Move command queue, render/blit pass encoding, fences, submission completion and deferred resource retirement together. Use pass-level operations or batches; avoid reproducing every Metal method in a new C API. Keep Java's Blaze3D validation/adaptation.
4. Move `CAMetalLayer` attachment/configuration, drawable acquisition, presentation and frame lifecycle to Swift with explicit AppKit/main-thread ownership. Preserve resizing, scale, present modes, and shutdown behavior.
5. Remove the borrowed-pointer export and unused Java Metal/Objective-C wrappers after all callers migrate. Package the native library, establish deployment/signing/toolchain policy, and only then make the Swift path default.

Each milestone needs vanilla and Sodium runs covering world load/unload, resize/fullscreen, resource reload, chunk rendering, transparency, UI, and repeated shutdown under Metal validation. No performance benefit is claimed from device ownership alone.

## Validation in this task

- Full `build buildNative` succeeded with Prism Launcher's Microsoft OpenJDK 25.0.1 and Apple Swift 6.4 on arm64. Gradle's cache was placed in `build/gradle-home` to stay within granted filesystem access.
- The new FFM class and Java smoke program compile with `--release 25`.
- The Objective-C smoke program compiles, including Metal SDK stride assertions. C ABI version/null handling executes, but real device creation returns NULL in this execution environment.
- Java loads the actual Swift library and reaches the expected no-device failure. Successful device lifetime tests and Minecraft/Fabric/Sodium gameplay remain unverified; the GPU smoke tests did not pass here.
- Gradle reports deprecated features for Gradle 10 and an FSEvents watcher warning; neither prevented this build.

## API references

- [Java 25 SymbolLookup lifetime and library loading](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/SymbolLookup.html)
- [Swift C-compatible export discussion](https://forums.swift.org/t/how-can-my-c-main-function-call-swift/40244)
- [Metal non-indexed indirect arguments](https://developer.apple.com/documentation/metal/mtldrawprimitivesindirectarguments)
- [Metal indexed indirect arguments](https://developer.apple.com/documentation/metal/mtldrawindexedprimitivesindirectarguments)
