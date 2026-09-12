# Swift drawing and presentation — version 12

`0.0.24-swift.12`, native ABI 12. This build combines the first two milestones of the six-step completion plan: Swift draw encoding and Swift presentation. It targets macOS 27 / Apple Silicon, Swift 6.4 (language mode 6), and MSL 4.1. The matching library is bundled in the jar.

## Drawing

All 23 render operations now call Swift: pipeline/depth state, depth bias, winding/culling/fill, vertex/fragment buffers and offsets, textures/samplers, scissors/viewports, inline vertex bytes, direct/instanced/indexed/indirect drawing, and fence updates/waits. Java retains the existing Minecraft-facing methods but no longer builds Objective-C messages or ABI-specific viewport/scissor structs for these operations.

The C boundary uses a pass ID, opcode, two borrowed pointers and eight signed 64-bit value slots. Floating-point values use double bit patterns; base vertex remains signed. A single 64-byte buffer is reused on the confined render thread, and Swift consumes it synchronously. No per-operation arena or Java argument array is allocated. Operations remain ordered and immediate; this is not deferred whole-pass batching. Presentation is batched into one native call.

The native switch rejects invalid pass IDs/opcodes and basic enum/negative argument errors. This is an internal trusted API, not a comprehensive Metal validation layer: borrowed pointers must be live objects from the correct device, and Minecraft adaptation remains responsible for complete resource/binding bounds.

## Presentation and ownership

Swift now creates/configures CAMetalLayer, acquires its drawable, encodes the full-screen presentation draw, and schedules presentation on the command buffer. Java no longer sees or retains drawables. Swift's autorelease pool drains temporary references after each call; Metal retains scheduled presentation resources as needed.

The existing BGRA8 output, framebuffer-only setting, scale, present-mode behavior, nearest/linear selection, fence stages and draw order are preserved. A missing drawable skips presentation as before. The layer is an owned resource ID, with a borrowed handle only for attaching to the existing Cocoa view. Initialization failure now detaches that view before cleanup as well.

Removed CAMetalDrawable.java and MTLScissorRect.java. CAMetalLayer.java and MTLRenderCommandEncoder.java are adaptation classes; their Objective-C selectors are gone. Cocoa view attachment, some metadata/debug helpers, Java scheduling and borrowed resource pointers remain for the next cleanup milestone.

## Validation

- Full Java/Fabric/Swift build and checkNative pass.
- All 23 production Java draw adapters are exercised against a recording C fixture, checking opcode, both pointer arguments and all eight value slots. Includes viewport/depth-bias bit patterns, signed base vertex, indirect-buffer offsets, fence stages and closed-pass rejection.
- Repeated Java/FFM ownership tests include 100 contexts with render operations, layer creation/configuration, resource cleanup and existing buffer/copy/submission checks.
- Real Swift descriptor-policy checks and bundled-library extraction/ABI checks pass.
- The real Metal smoke executable compiles with direct draw dispatch, completion and layer configuration checks. GPU execution is unavailable here (`Metal unavailable`); fixture success is not a GPU rendering test.

No measured FPS improvement or universal leak-free claim is made. The existing bounded buffer cache and GPU retirement schedule are preserved.

## Install and test once for both milestones

Quit Minecraft and replace the previous mod with metallum-0.0.24-swift.12.jar. Do not restore the native-library-path JVM argument.

Check normal gameplay and chunk loading, water/transparency, menus, repeated F3+T reloads, resizing/fullscreen, F2 screenshots, world re-entry and shutdown. Check Sodium too if used. Compare settled Activity Monitor memory after repeated reloads, with the same resolution and power settings.

## Four milestones remain before MetalFX

1. Complete the native boundary: replace borrowed pointers with owned IDs and remove remaining unused runtime helpers.
2. Introduce Metal 4 command infrastructure, allocators and resource residency.
3. Migrate render/copy encoding, bindings and synchronization to Metal 4 and retire the replaced path.
4. Profile and stress-test the completed backend across vanilla/Sodium and resource lifetimes.

The current build still uses MTLCommandBuffer/MTLRenderCommandEncoder. Using Swift and MSL 4.1 does not mean Metal 4 command encoding is complete. MetalFX stays deferred until the backend is ready.

API references: [Metal render encoders](https://developer.apple.com/documentation/metal/mtlrendercommandencoder) and [CAMetalLayer](https://developer.apple.com/documentation/quartzcore/cametallayer).
