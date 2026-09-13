# Native renderer status and remaining work

Reviewed September 12, 2026. Public version **0.0.24**, bundled native ABI **20**.
This is a source audit and implementation order, not a measured ranking of Minecraft bottlenecks.

## Current state

- Swift owns Metal resource creation, render/copy encoding, pipelines, submission, GPU resource lifetime and presentation. Metal 4 queues, command allocators and argument tables are in use.
- Indexed multidraw crosses FFM once per chunk of up to 256 active draws. Indexed and non-indexed indirect draw loops execute in Swift; these are not GPU-generated indirect command buffers.
- Consecutive compatible transfers share a copy encoder. Copy metadata and the Java copy adapter are reused.
- MetalFX spatial upscaling can use the world textures directly when their usage permits it. GUI rendering stays at native resolution. The slider persists across launches.
- Duplicate with-depth/without-depth pipelines and obsolete depth-format descriptor fields were removed. Clear pipelines share depth formats; explicit raster-state reset fixes the independently reproduced incomplete-clear defect.
- Native diagnostics include submission GPU time, wait time, resources and grouped-copy counts. Real GPU checks exist alongside CPU/FFM checks.
- The user reports good gameplay. The brief startup pink bar has no confirmed cause; the clear-state fix does not establish that it caused that particular symptom.

## Latest completed batch

See [Metal 4 resource reuse and compilation](metal4-resource-reuse.md) for the coordinated upload reuse, texel view cache, unchanged-binding suppression, combined native bindings, persistent pipeline archives and conservative store/barrier cleanup. Public version stays 0.0.24. The prior render-pass/MetalFX/wait scratch-allocation cleanup also remains in place.

The table below records remaining work after those changes. Performance priorities still need a representative Minecraft capture.

## Next steps, in order

| Priority | Observed gap or overhead | Concrete next implementation | Acceptance evidence |
| --- | --- | --- | --- |
| 1 | Minecraft query pools currently store `System.nanoTime()` from the CPU; submission diagnostics are genuine GPU timings but do not identify individual pass cost. | Implement Metal 4 GPU timestamp queries with completion-aware readback; distinguish unavailable results from zero. Capture CPU frame time, GPU time and wait time in a repeatable world before ranking larger changes. | Query ordering, reset/reuse and completion tests; compare with a Metal trace without forcing a wait per query. |
| 2 | Dynamic updates now reuse safe backing and preserve only untouched bytes. | Measure remaining replacement/copy volume; adopt upload slices only if the existing bounded pool remains a bottleneck. | Sustained gameplay with bounded memory and snapshot parity. |
| 3 | One texel view per backing is cached; unchanged uniforms skip binding; related bindings cross FFM together. | Profile alternating view ranges and remaining state-call volume before expanding caches or batching. | Lower measured encode time without extra lifetime registries. |
| 4 | Both non-indexed `MetalRenderPass.multiDraw` overloads still throw `UnsupportedOperationException`. | Add native batching after verifying Minecraft's packed-array contract. Preserve NIO positions, zero-draw handling and triangle-fan semantics. | Both layouts, chunk boundaries, invalid ranges and GPU pixel checks. |
| 5 | Persistent pipeline archives now exist; source shader translation/compilation remains. | Measure cold/warm launches and reloads. Consider source-library persistence only if that stage remains expensive. | Startup/reload timings and resource-pack invalidation. |
| 6 | Stores are discarded for proven complete replacements; covered duplicate waits were removed. | Profile remaining barriers and attachment consumers before broader discard or memoryless storage. The remaining fence-wait API is retained. | Post-processing/readback/MetalFX checks and GPU bandwidth/time measurements. |
| 8 | Native indirect loops still encode draws on the CPU. | Investigate GPU culling and indirect command buffers for chunks only if chunk submission is a measured bottleneck. | Visibility parity, world transitions, Sodium compatibility and a measured CPU/GPU benefit. Larger project, not routine cleanup. |

Apple documents [Metal 4 timestamp entries](https://developer.apple.com/documentation/metal/mtl4timestampheapentry) and [Metal 4 compilation and binary archives](https://developer.apple.com/documentation/metal/using-the-metal-4-compilation-api). Persistent archives are now implemented. Per-query GPU timestamps remain outstanding.

## Duplicate, obsolete and compatibility code

- **Removed previously:** duplicate depth/no-depth native pipelines, redundant pipeline depth-format fields, unused stencil helpers, duplicate owner fields, per-copy Java adapters/arenas and redundant Java encoder-ending calls.
- **Removed in recent batches:** per-pass clear arrays/arenas, duplicated temporary error buffers, eight obsolete Java binding setters, obsolete native binding dispatch and two per-pass buffer arrays.
- **Still worth profiling:** remaining state marshaling, alternating texel-view ranges, dynamic replacement volume and optional diagnostic/label allocations. Keep cleanup local to the owning abstraction rather than adding a general cache framework.
- **Keep:** Minecraft/Fabric adapters, Sodium integration, GLSL/SPIR-V translation for game/mod/resource-pack shaders, and the small Cocoa window/layer bridge. These still serve compatibility or platform integration. Moving them to Swift purely to increase Swift line count does not establish a performance benefit.
- **Not next:** temporal MetalFX needs motion vectors, jitter, history and correct resets. Spatial upscaling already works; temporal support would add substantial rendering state and testing scope.
- **Compatibility still to verify:** recent gameplay logs did not include Sodium, so passing current manual testing does not validate Sodium integration. GPU smoke tests also do not establish support across every Apple Silicon generation.

## Verification and one combined manual run

Run `./gradlew build checkGpu --offline` in a GPU-accessible session. This builds the packaged jar, runs CPU/FFM/native ownership checks, then runs real GPU checks with Metal API Validation, including the production Java clear renderer. This batch passed on Apple M4 with Metal API Validation enabled, including the scratch-reuse fixture and production clear renderer. That earlier log is `build/native/scratch-validation.log`; the latest batch uses `build/native/metal4-batch-validation.log`.

Use `build/libs/metallum-0.0.24.jar` for the combined manual run: fresh launch/menu, gameplay, MetalFX Off → 85% → 50% → Off, resize/fullscreen, F3+T reload, leave/re-enter the world, restart and verify slider persistence. Inspect memory after GPU completion. Record any recurring pink bar separately. Keep the existing Java arguments.
