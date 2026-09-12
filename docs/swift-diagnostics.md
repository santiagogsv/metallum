# Version 15 — GPU diagnostics and safe command cleanup

`0.0.24-swift.15`, native ABI 15. macOS 27 / Apple Silicon, Swift 6.4 language mode 6, MSL 4.1. MetalFX remains disabled.

## Changes

Metal 4 commit feedback now supplies real GPU execution durations. Completed submissions are counted once when retired, with valid sample count, total and maximum GPU duration. Invalid/unavailable timestamps and failed GPU work are excluded from the timed sample count. Logs show unavailable when there are no valid samples. These are submission execution durations, not frame time, presentation latency or per-pass GPU timestamps.

CPU time spent waiting for completion and joining callbacks is measured separately; resource-retirement work is excluded. Already-retired submissions return immediately on repeated waits/close, avoiding another callback-queue join and preventing duplicate samples. The first retirement still waits for the entire callback to finish before recycling storage.

Argument tables cache GPU addresses and resource IDs, skipping identical writes. Pass reset clears only nonzero bindings. Existing draw snapshots and strong resource ownership remain intact. This replaces the previous highest-used-slot reset bookkeeping and exposes actual/skipped binding-write counts for measurement.

A new native snapshot reports active/idle command slots, shared staging bytes, command-allocator bytes, command-held reference counts and active/idle residency sets. Commands reachable through both their own ID and a submission are counted only once. Existing buffer/resource/library counts, Metal allocation size and Java idle-buffer cache reporting remain available.

The snapshot does not wait for GPU work or retain a growing sample history. Seven interval counters drain on each read; memory gauges report current values. Argument-cache counters and completion aggregates occupy fixed storage. Resource counts may include aliases; held references are summed per command. Memory categories overlap and must not be summed into total process RAM. Metal allocation figures are not Activity Monitor's Java process footprint.

## Synchronization decision

GPU barriers and fences remain unchanged. There is no measured evidence yet that weakening them is correct or useful. This build removes demonstrably redundant binding operations and repeated CPU completion joins. It does not claim a measured FPS improvement or prove the absence of all leaks. Shader compilation and Java's existing timestamp API are unchanged; the new GPU diagnostics are explicitly separate from that API.

The version 14.1 queue-descriptor lifetime correction and its success/error-path regression tests are preserved. The small Cocoa window-attachment bridge remains required.

## Enable and test

Quit Minecraft, replace the previous Metallum jar with `metallum-0.0.24-swift.15.jar`, and use these Java arguments for this measurement run:

```
--enable-native-access=ALL-UNNAMED -Dmetallum.performanceDiagnostics=true
```

Do not add a native-library path override. The matching native library is bundled in the jar. Keep Minecraft's graphics preference set to Prefer Metal.

Every approximately 30 seconds, latest.log receives `[metallum-memory]`, `[metallum-performance]` and `[metallum-command-memory]` entries. The existing `metallum.memoryDiagnostics=true` flag also enables these reports. The first report covers a shorter startup interval. Diagnostics logging is disabled by default; fixed-size counters are still maintained.

Play for 10–15 minutes, including repeated resource reloads, world exit/re-entry, chunk movement and window/fullscreen changes. Compare steady-scene intervals at the same resolution, settings and power mode. Look for sustained allocator/staging/resource growth after reloads, and compare GPU submission durations with total CPU waiting. Save latest.log after the run before another launch replaces it. A single FPS screenshot is not sufficient to attribute a speed change.

## Verification

`build checkNative` passes: Swift descriptor/ownership tests, GPU duration validation, counter draining, redundant-binding decisions, Java FFM mapping of every diagnostics field, bounded Java buffer caching, packaged library ABI/loading, and all 23 render adapter operations against a C fixture.

The real GPU smoke test includes checks for single retirement accounting and zero added wait cost on an already-retired submission. This environment cannot access Metal hardware, so GPU execution and gameplay remain unverified here.

Next: use the gameplay logs and GPU profiling to choose any narrower synchronization changes. Begin optional MetalFX spatial upscaling after this backend's timing and memory behavior is validated.
