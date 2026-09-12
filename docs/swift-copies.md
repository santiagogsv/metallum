# Swift copy passes — milestone 10

Version `0.0.24-swift.10`, native ABI 10. macOS 27, Swift 6.4, MSL 4.1; matching native library bundled.

## Changes

Moved all four copy paths (buffer-buffer, buffer-texture, texture-buffer and texture-texture) into Swift. Each call describes one complete pass with a fixed 16-word value payload and device-local IDs. Swift creates the blit encoder, waits on the fence, copies, updates the fence and ends encoding. Java performs Minecraft/Blaze3D adaptation and ends an active render encoder before requesting the pass.

Fence creation/ownership also moved into Swift. The Java render encoder still borrows the fence for existing render-stage synchronization; deferred fence cleanup now releases its native resource owner. Removed MTLBlitCommandEncoder.java, MTLSize.java and MTLOrigin.java. MTLCopyPass is a value adapter, not an Objective-C encoder wrapper.

The copy ABI validates command/fence IDs, command status, payload shape, resource types, buffer-buffer bounds and texture regions before opening an encoder. Java checks resource ownership before extracting IDs. Buffer-texture row/image layouts preserve the existing adaptation; this is not a complete independent validator for every Metal pixel-format/alignment rule.

The number/order of copy passes and GPU retirement schedule are preserved. Several Java Objective-C calls become one native pass operation. No performance claim is made without profiling. This does not switch to Metal 4 command encoders or enable MetalFX.

## Install and test

Quit Minecraft and replace the previous jar with metallum-0.0.24-swift.10.jar. Keep the old native-library-path argument removed. Existing memory diagnostics can remain enabled.

Test chunk loading/movement, several F3+T resource reloads, resizing, an F2 screenshot (texture readback), world re-entry and normal shutdown. Compare settled memory with version 9 and check that screenshots have correct colors/content.

## Validation

- Full Java/Fabric/Swift build and checkNative passed.
- Production Swift copy-payload/range checks passed alongside existing descriptor and completion tests.
- Java/C fixture tests cover buffer data/offset copies, invalid range rejection, completion and returning to zero owned resources across repeated lifetimes. The fixture does not emulate texture copies or GPU synchronization.
- Bounded-buffer-pool regressions and real packaged Swift library extraction/loading passed.
- A real Metal round-trip smoke test covers upload, texture copy, readback and buffer copy, followed by data comparison. It compiles but cannot execute here: the tool environment still reports Metal unavailable.
- Removed wrappers are absent from the jar, which contains the matching native library.

Next: migrate render-pass encoding/bindings while preserving submission and resource lifetime contracts; adopt Metal 4 allocator/residency/barrier changes as a coordinated step. Continue monitoring memory during each gameplay validation.
