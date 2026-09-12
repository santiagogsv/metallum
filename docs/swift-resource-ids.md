# Resource IDs and native command residency — version 13

Version `0.0.24-swift.13`, native ABI 13. macOS 27 / Apple Silicon, Swift 6.4 (language mode 6), MSL 4.1. The matching native library is bundled in the jar.

## Scope and honest milestone status

This build completes the rendering-resource boundary cleanup and implements the ownership/residency part of the Metal 4 command foundation. It does NOT complete the Metal 4 queue/allocator milestone or switch command encoders to Metal 4. The SDK couples that switch to argument-table bindings and synchronization; those should move together. No unused Metal 4 queue or alternate rendering path has been introduced.

## Rendering boundary

Java now carries NativeMetalDevice.Resource references for textures/views, samplers, pipelines and depth states. Render-pass creation, draws and presentation send device-local IDs. Buffer IDs use their separate namespace. Java checks owner and lifetime before extracting IDs; Swift resolves object types in its tables. Zero represents an omitted/unbound nullable resource. Inline vertex bytes have a dedicated data-pointer entry point.

Metadata queries and command debug groups moved into Swift. Built-in pipelines hold resource owners directly; the old borrowed-address-to-owner deque is gone. Resource/buffer creation no longer performs an extra borrow call or caches Metal object pointers. Texture view identity is now resource identity; different alias views may conservatively start a separate pass.

Deleted the generic Java ObjC.java, Msg.java and AutoreleasePool.java helpers. A small Cocoa.java adapter remains solely for GLFW/AppKit scale and view/layer attachment. The layer pointer is borrowed for that platform operation. Diagnostic borrowing exports remain for native smoke checks; they are not used by Java rendering. CPU buffer contents and inline uniform data necessarily remain memory pointers.

## Native command lifetime and residency

NativeCommand tracks whether a render encoder is open or submission has started. Opening another encoder or submitting while a render encoder is open is rejected. Each draw/copy/pass/presentation records strong references to its resources, deduplicated by native object identity. References retire after the GPU wait joins completion callbacks, including close-without-explicit-wait cleanup.

At submission, referenced Metal allocations populate a residency set attached to the command buffer. Completed submissions clear and recycle their sets. The device caches at most three empty sets, and no in-flight set is cleared or reused. The existing Java GPU retirement schedule and 64 MiB idle buffer cache remain unchanged. Residency does not replace fences; the existing synchronization is preserved.

These are the explicit resource-lifetime foundations needed by Metal 4, already used by the current renderer. Queue/allocator reuse, argument tables, Metal 4 render/copy encoders and updated synchronization remain the next coordinated migration. Residency APIs also work with the current command buffers: [Apple residency guidance](https://developer.apple.com/documentation/metal/simplifying-gpu-resource-management-with-residency-sets).

No FPS gain is claimed. Ownership tables and residency metadata add CPU work; game profiling must establish the net effect. Ownership counts omit additional command-held references, so use Metal allocation/Activity Monitor trends as well when checking memory.

## Validation

- Full Java/Fabric/Swift build and checkNative pass.
- Production adapters pass all 23 operation checks against the recording C fixture, now using IDs and a separate inline-byte operation.
- 100 Java/FFM context/resource lifecycle cycles, existing copy/submit checks, buffer-pool tests and packaged-library extraction/ABI checks pass.
- Real Swift descriptor checks include 1,000 resource-reference cycles: duplicate holds retain once, objects survive until retirement, and clearing releases them.
- The real Metal smoke executable includes actual rendering calls and rejection of submit/second-pass creation while a render encoder is open. It compiles here; GPU execution is unavailable (`Metal unavailable`). GPU residency behavior and visual correctness still require the game test.

## Install and test

Quit Minecraft and replace the previous jar with metallum-0.0.24-swift.13.jar. Keep the native-library-path JVM argument removed.

Test world entry, movement/chunk loading, transparency, menus, several F3+T reloads, resizing/fullscreen, screenshots, world re-entry and shutdown. Include Sodium if used. Compare settled memory after repeated reloads and FPS under the same resolution/power settings.

The remaining work is the coordinated Metal 4 command-system migration, retirement of its replaced path, and full GPU profiling/stress validation. MetalFX remains deferred.
