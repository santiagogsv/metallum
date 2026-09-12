# Swift Metal 4 backend — version 14

Version `0.0.24-swift.14.1`, native ABI 14. Built with the installed Swift 6.4 compiler (language mode 6), macOS 27 SDK, arm64 deployment target macOS 27, and MSL 4.1. The matching native library is bundled in the jar.

## Startup crash correction in 14.1

The reported version 14 crash was a native `SIGTRAP` during `metallum_submit`, with libdispatch reporting `API MISUSE: Resurrection of an object`. An isolated GPU-independent reproduction showed that destroying a populated macOS 27 `MTL4CommandQueueDescriptor` invalidated the serial feedback queue despite its separately held Swift owner.

Queue creation now uses a scoped descriptor helper which clears the borrowed `feedbackQueue` property with `defer`, on success and on thrown errors, before the descriptor is destroyed. Device and submission ownership, serial feedback delivery, and callback joining remain intact. No manual retain or permanent queue is introduced.

The regression test runs 100 success and 100 error-path cycles, drains the descriptor's autorelease pool, uses the surviving queue, and verifies that the queue is released after its owner leaves scope. The full `build checkNative` suite passes. Gameplay remains to be retested.

The supplied Prism log still contained the development override `-Dmetallum.nativeLibrary=...`. Remove that argument so the jar always loads its own matching native library. ABI 14 is unchanged in this corrective release.

## What changed

This combines the command infrastructure and encoder migration. The runtime uses Metal 4 throughout the native command path, without a second legacy implementation:

- `MetalSubmission.swift`: Metal 4 command queue, reusable command buffers/allocators, argument tables, commit feedback, GPU-completion-based retirement and drawable wait/commit/signal/present ordering.
- `MetalDraws.swift` / `MetalRenderPass.swift`: Metal 4 render encoders, GPU addresses and resource IDs in per-stage argument tables, direct and indirect draws, explicit synchronization and range checks.
- `MetalCopies.swift`: copies run through Metal 4 compute encoders using the blit stage, preserving the four existing copy operations.
- `MetalPipelines.swift` / `MetalShaders.swift`: one native Metal 4 compiler per device and library-backed function descriptors. Live functions retain their libraries independently of the reloadable source cache. Depth/stencil formats come from the render pass under Metal 4; existing Java payload fields remain accepted.
- `MetalPresentation.swift`: Metal 4 presentation pass; drawable presentation is scheduled at submission with explicit queue synchronization.
- Samplers support argument-table resource IDs. The replaced Swift command-buffer, render/blit encoder and pipeline-creation calls are removed.

Java still supplies Minecraft/Blaze3D adaptation and frame scheduling. Fabric/Sodium integration and SPIR-V/SPIRV-Cross translation are preserved. The C payload layouts remain unchanged; ABI 14 ensures the jar and native library match. The remaining small Cocoa view-attachment bridge is still required.

## Ownership and memory

Every command retains its referenced resources and submits their residency set. Slots and residency sets return to the idle pool only after GPU completion and after the feedback callback has returned. Each idle pool retains at most three entries.

Inline shader bytes are copied before returning to Java into 256-byte-aligned slices of shared 64 KiB buffers. They remain alive until command completion. At retirement, extra staging chunks are released; each idle slot retains at most one 64 KiB chunk (192 KiB across three idle slots). These limits do not cap active GPU work, Metal's allocator storage, compiler caches, or total process RAM. The previous 64 MiB Java idle buffer-cache limit remains.

Argument tables are reused, with resets limited to the ranges written in the previous pass. Metal snapshots table bindings at each draw, so later bindings cannot change already encoded draws. Explicit strong references supply the lifetime that raw GPU addresses/resource IDs do not provide.

The first migration uses conservative queue barriers with device memory visibility between passes, alongside the existing explicit fences. This preserves ordering across copies, draws and submissions. Narrower barriers should be considered only with GPU captures/profiling; no performance improvement or complete absence of leaks is claimed from build tests.

## Validation

Passed `build checkNative` offline with JDK 25:

- Real Swift/Metal SDK descriptor checks, now using Metal 4 descriptors.
- Inline staging alignment, chunk transitions, bounds and overflow rejection.
- Completion timeout/repeated-wait/error publication and reference release checks.
- Java FFM ownership, bounded buffer-cache tests, all 23 production render adapter operations against a C fixture, and packaged native-library loading/ABI checks.

The real GPU smoke test compiles. Its execution reports `Metal unavailable` in this tool environment, so GPU rendering has NOT been validated here. The expanded test checks red/green pixel readback across reused argument tables, inline-data copies spanning staging chunks, resource-ID destruction before submission, render/copy ordering and repeated command reuse. It can be run with `JAVA_HOME` set to JDK 25 using `sh native/tests/smoke.sh` from the repository on a GPU-accessible session.

## Install and one combined gameplay test

Quit Minecraft and replace the old mod jar with `metallum-0.0.24-swift.14.1.jar`. No native-library JVM argument is needed. Startup logging identifies `Swift Metal 4 compiler, commands and presentation, ABI 14`.

Load a world, move through chunks, inspect terrain/water/transparency and inventory/UI, reload resources several times, resize/toggle fullscreen, then leave and re-enter the world. Watch memory for a sustained rise over 10–15 minutes, especially after reloads. Check both vanilla and Sodium configurations when applicable. Keep the previous working jar for comparison under the same resolution, settings and power mode.

## Remaining before MetalFX

The core Metal 4 implementation is now present. Next is real GPU correctness/stress validation, frame-time and memory profiling, then evidence-based synchronization/encoding tuning. Java scheduling and adaptation can be simplified further if profiling or ownership clarity justifies it. MetalFX remains disabled until this backend is validated.

Apple references: [Metal 4 core API](https://developer.apple.com/documentation/metal/understanding-the-metal-4-core-api), [argument-table snapshots](https://developer.apple.com/documentation/metal/mtl4rendercommandencoder/setargumenttable(_:stages:)), [Metal 4 compilation](https://developer.apple.com/documentation/metal/using-the-metal-4-compilation-api). API signatures and synchronization contracts were also checked against the installed macOS 27 Metal SDK headers.
