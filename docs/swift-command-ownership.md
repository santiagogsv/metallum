# Swift command ownership — milestone 9

Version `0.0.24-swift.9`, native ABI 9. Targets macOS 27, Swift 6.4 and MSL 4.1, with the matching native library bundled.

## Change

Swift now creates and owns the command queue and command buffers. The device context lazily creates one queue. Command creation returns a resource ID, and submission now takes that ID rather than a borrowed Objective-C pointer. Java validates resource ownership and Swift validates command type/status before committing. NativeSubmission retains the command even if its public resource ID is released first.

Deleted MTLCommandQueue.java and its selectors, label plumbing and Java retain/release path. Java's remaining MTLCommandBuffer wrapper borrows its encoding handle from the native owner; it closes the submission before the command resource. Render/blit encoding and presentation still use that transitional borrowed handle. No per-draw FFM operations were added.

The three-submission schedule, completion join, bounded memory cache and diagnostics remain unchanged. Resource diagnostics now include command-buffer ownership entries as well as submission entries; a small bounded count increase is expected while work is in flight.

## Install and test

Quit Minecraft and replace the previous jar with metallum-0.0.24-swift.9.jar. Keep the old native-library-path argument removed. Keep memoryDiagnostics enabled if comparing memory.

Test world load, movement/chunk loading, repeated F3+T reloads, resizing, world re-entry and normal shutdown. Compare settled memory and responsiveness against version 8.

## Validation

Full build and checkNative passed. The Java/C fixture tests now exercise real resource IDs, double-submit rejection, command-handle release before submission completion, repeated waits, closed-submission rejection and zero remaining owned resources across 100 cycles. Swift completion/descriptor tests, bounded-pool tests and packaged-library extraction/loading passed.

The real Metal smoke program compiles against the updated ABI but still exits with Metal unavailable in this tool environment. New GPU command creation and gameplay require the user's test. No performance improvement or absence of all leaks is asserted from CPU tests.

## Metal 4 and MetalFX path

The Swift boundary makes migration easier because command ownership, submission and resources are now in one native implementation. We still need to migrate encoders/bindings and synchronize allocator reuse/residency before switching to Metal 4 command infrastructure. This build still uses the existing Metal command API.

MetalFX is a separate renderer feature and need not wait for every Metal 4 change. Spatial upscaling is the simplest experiment: render the world into a smaller target, upscale its color image, and compose UI at native resolution. Color-space correctness, output size and presentation ordering must be tested. It is most promising when pixel rendering is the bottleneck, and may add overhead when it is not.

Temporal upscaling needs the correct color/depth/motion inputs, jitter, exposure conventions and history resets for cuts, resize and reload. Entity animation and transparent surfaces make correct motion/history integration more involved. Frame interpolation is a later option with capability checks and additional frame pacing/latency work; interpolated frames are not extra Minecraft simulation ticks. Neither feature is enabled in this milestone.

References: [Metal 4 core API](https://developer.apple.com/documentation/metal/understanding-the-metal-4-core-api), [MetalFX spatial scaler](https://developer.apple.com/documentation/metalfx/mtlfxspatialscaler), [MetalFX temporal scaler](https://developer.apple.com/documentation/metalfx/mtlfxtemporalscalerbase), [MetalFX frame interpolator](https://developer.apple.com/documentation/metalfx/mtlfxframeinterpolatordescriptor).
