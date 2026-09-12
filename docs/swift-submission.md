# Swift submission completion — milestone 8

Version `0.0.24-swift.8`, native ABI 8. Targets macOS 27, Swift 6.4 and MSL 4.1; native library remains bundled.

## What changed

Swift now registers completion handlers and commits the existing Metal command buffers. A Swift submission owner holds the command buffer until its work and callbacks are finished. Java retains its encoding wrapper and passes its borrowed command-buffer pointer once on submission; the returned opaque resource ID is used for completion waits and retirement.

Removed `ObjCBlock.java`, its process-lifetime callback/upcall allocations, and the per-slot Java semaphores. The Swift callback captures only a completion group, avoiding a command-buffer/owner capture cycle. Repeatable timed waits use DispatchGroup. After a completion signal, `waitUntilCompleted()` joins all Metal handlers before allowing destruction or native-library teardown. Closing a submission without an earlier wait also joins completion. Timed waits bound the GPU-signal wait; the final callback join can extend past that deadline.

GPU errors return a bounded UTF-8 diagnostic and propagate to Java instead of being indistinguishable from successful completion. The existing three-submission schedule, fences, deferred resource destruction, bounded idle-buffer cache and optional memory diagnostics remain in place.

This is a migration of commit/completion ownership. Command queues, command-buffer creation, render/blit encoding, frame scheduling and presentation still have Java callers. It does not yet implement Metal 4 command queues/allocators/residency. Those changes remain a coordinated next milestone.

## Install and test

Quit Minecraft and replace the previous jar with `metallum-0.0.24-swift.8.jar`. No native-library-path argument is needed. Keep `-Dmetallum.memoryDiagnostics=true` if monitoring memory.

Load the same world, move through chunks, reload resources several times, resize/fullscreen, and leave/re-enter the world. Also quit normally; this version changes completion handling during shutdown. Compare settled memory with version 7. Native submission owners appear in the generic resource count, so a small bounded increase in that count is expected while submissions are in flight.

## Validation

- `build checkNative` passed: Java/Fabric build, Swift build, real Swift descriptor/completion tests, CPU C fixture FFM ownership checks, bounded-pool regression tests and real packaged-library extraction/loading.
- Swift checks cover zero/finite timeouts, asynchronous signaling, repeatable waits, indefinite completed waits and 1,000 completion-object lifetime cycles.
- Java fixture checks cover submission FFM calls, repeatable waits, closed-submission rejection and returning to zero owned buffers/resources across 100 cycles. The fixture does not execute Metal commands.
- Real Metal smoke code exercises empty command submission, repeated waits and close-without-wait. It compiles, but this tool environment still returns `Metal unavailable`; GPU execution/gameplay is unverified here.
- No performance improvement or universal absence of leaks is claimed. The user-tested memory behavior from version 7 remains the baseline.

Reference: [Apple documents that waitUntilCompleted joins both GPU work and completion handlers](https://developer.apple.com/documentation/metal/mtlcommandbuffer/waituntilcompleted()).
