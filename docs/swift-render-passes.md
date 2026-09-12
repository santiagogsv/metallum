# Swift render-pass ownership — milestone 11

Version `0.0.24-swift.11`, native ABI 11. Targets macOS 27 and Apple Silicon with Swift 6.4, Swift language mode 6 and MSL 4.1. The matching native library is bundled in the jar.

## What changed

Swift now builds attachment descriptors and opens render encoders for normal rendering, full/regional clears, and presentation. A small C call carries the command ID, borrowed attachment pointers, two load actions and five clear values (RGBA/depth). Java retains the Minecraft adaptation and existing draw/binding commands.

`NativeRenderPass` owns the encoder and keeps its command buffer alive. Java closes the pass's resource ID to end encoding and release both references through Swift ARC. Closing twice is harmless. The temporary descriptor is scoped to creation inside an autorelease pool; no descriptor cache is introduced. Removed the Objective-C `MTLRenderPassDescriptor.java` wrapper and Java's manual encoder retain/release/endEncoding calls.

Color and depth are stored as before; ordinary passes preserve or clear attachments, and the full-screen presentation pass discards old drawable contents. Combined depth/stencil attachment setup uses the Apple Silicon depth32-float/stencil8 format; it does not add the deprecated Intel depth24 format. Stencil contents remain discarded. See [Apple's load/store guidance](https://developer.apple.com/documentation/metal/setting-load-and-store-actions/).

Borrowed texture/encoder pointers remain a deliberate migration boundary. Inputs must be valid Metal objects belonging to the device and used on the render thread. The caller must end a pass before submitting its command. This milestone preserves the existing fence ordering and GPU retirement rules. It does not implement Metal 4 command submission or MetalFX, nor does it establish a performance gain or prove the absence of all memory leaks.

## Validation

The Java/Fabric/Swift build and checkNative pass. Tests cover the real Swift attachment policy without a GPU, 100 Java/C-fixture ownership cycles including render passes, buffer-pool lifetime behavior, and extraction/loading of the bundled ABI 11 library. The fixture checks the boundary, not GPU behavior.

The real Metal smoke executable includes render-pass creation, close-before-submit, repeated close and completion checks. It compiles here, but cannot execute GPU work because this environment reports `Metal unavailable`. In-game testing is still required.

## Install and test

Quit Minecraft and replace the previous jar with `metallum-0.0.24-swift.11.jar`. Leave the old native-library-path JVM argument removed. Existing memory diagnostics can remain enabled.

Check world loading, several F3+T reloads, resizing/fullscreen, an F2 screenshot, world re-entry and shutdown. Watch settled memory after repeat reloads rather than a single peak. Include vanilla and Sodium if you use both configurations.

## Next

Move render state/binding/draw batches to Swift, then migrate CAMetalLayer/drawable ownership and presentation. These remaining boundaries should be completed and measured before a broader Metal 4 command-system change. MetalFX remains a separate rendering feature that needs its own quality and performance validation.
