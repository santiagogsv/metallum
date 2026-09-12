# Native renderer status and remaining work

Reviewed September 12, 2026. Public version **0.0.24**, bundled native ABI **19**.
This is a source audit and implementation order, not a measured ranking of Minecraft bottlenecks.

## Current state

- Swift owns Metal resource creation, render/copy encoding, pipelines, submission, GPU resource lifetime and presentation. Metal 4 queues, command allocators and argument tables are in use.
- Indexed multidraw crosses FFM once per chunk of up to 256 active draws. Indexed and non-indexed indirect draw loops execute in Swift; these are not GPU-generated indirect command buffers.
- Consecutive compatible transfers share a copy encoder. Copy metadata and the Java copy adapter are reused.
- MetalFX spatial upscaling can use the world textures directly when their usage permits it. GUI rendering stays at native resolution. The slider persists across launches.
- Duplicate with-depth/without-depth pipelines and obsolete depth-format descriptor fields were removed. Clear pipelines share depth formats; explicit raster-state reset fixes the independently reproduced incomplete-clear defect.
- Native diagnostics include submission GPU time, wait time, resources and grouped-copy counts. Real GPU checks exist alongside CPU/FFM checks.
- The user reports good gameplay. The brief startup pink bar has no confirmed cause; the clear-state fix does not establish that it caused that particular symptom.

## Implemented in this batch

`NativeMetalDevice.createRenderPass` now writes five scalar clear values into the existing device scratch payload. `MTLCommandBuffer` no longer creates a `double[]` per pass. MetalFX calls and submission waits share the existing 4 KB error buffer with copies instead of allocating and closing a native arena every call.

This removes one Java array and one native clear allocation per render pass, and one 4 KB native allocation per upscale/wait call. It adds no persistent allocation, dependency, cache or native ABI change. All affected FFM calls consume their input synchronously, error-producing calls reset their output, and the device enforces render-thread confinement. Remaining arenas for variable-length strings and compilation are separate follow-up candidates.

The FFM fixture interleaves render commands and render-pass creation to check that integer/double scratch reuse preserves every clear component. Existing GPU tests exercise the production clear renderer and native MetalFX/submission paths. Allocation removal alone does not establish an FPS improvement.

## Next steps, in order

| Priority | Observed gap or overhead | Concrete next implementation | Acceptance evidence |
| --- | --- | --- | --- |
| 1 | Minecraft query pools currently store `System.nanoTime()` from the CPU; submission diagnostics are genuine GPU timings but do not identify individual pass cost. | Implement Metal 4 GPU timestamp queries with completion-aware readback; distinguish unavailable results from zero. Capture CPU frame time, GPU time and wait time in a repeatable world before ranking larger changes. | Query ordering, reset/reuse and completion tests; compare with a Metal trace without forcing a wait per query. |
| 2 | `MetalCommandEncoder.orphanWrite` replaces backing for each dynamic write. Partial updates copy the whole allocation on the CPU. | Measure bytes copied/backings acquired, then use aligned upload slices or reuse backing proven unused by pending GPU work. Preserve the snapshot seen by earlier draws. | Multiple writes and draws before completion retain their original values; bounded memory after sustained play and reload. |
| 3 | `MetalRenderPass.pushTexelBufferDescriptor` creates a Metal texture view whenever a texel binding is pushed. | Reuse views by actual backing identity, format, offset and length, with a bounded lifetime. A Java buffer identity alone is insufficient because dynamic writes replace its backing. | No stale views after backing changes; repeated bindings stop creating views; resource counts stabilize after completion. |
| 4 | Both non-indexed `MetalRenderPass.multiDraw` overloads throw `UnsupportedOperationException`. | Add native batching using the existing indexed batching pattern after verifying Minecraft's packed-array contract. Preserve NIO positions, zero-draw handling and triangle-fan semantics. | Both layouts, chunk boundaries, invalid ranges and actual GPU pixel checks. This closes an API gap; current gameplay may not call it. |
| 5 | Shader libraries and pipelines are cached only for the current process; runtime MSL compilation remains. | Add a bounded persistent Metal 4 archive cache, keyed by source/pipeline configuration and relevant device/toolchain compatibility, with cache-miss fallback. Keep resource-pack invalidation explicit. | Cold versus warm startup/reload timings; corrupt/stale archive fallback; resource-pack changes compile correctly. |
| 6 | Many state bindings still cross FFM separately; Swift has duplicate argument-table write suppression. | Profile call counts, then batch frequently co-occurring binding/state updates where the saved crossings outweigh packing complexity. | Identical draw output and binding lifetime; fewer crossings and lower measured CPU encode time. |
| 7 | Render attachments always store, and queue barriers are conservative. | Track attachment consumers before discarding unused results; narrow barriers only with demonstrated dependency coverage. Consider memoryless storage only for truly pass-local attachments. | Metal validation, post-processing/readback/MetalFX checks and bandwidth/GPU-time measurements. |
| 8 | Native indirect loops still encode draws on the CPU. | Investigate GPU culling and indirect command buffers for chunks only if chunk submission is a measured bottleneck. | Visibility parity, world transitions, Sodium compatibility and a measured CPU/GPU benefit. Larger project, not routine cleanup. |

Apple documents [Metal 4 timestamp entries](https://developer.apple.com/documentation/metal/mtl4timestampheapentry) and [Metal 4 compilation and binary archives](https://developer.apple.com/documentation/metal/using-the-metal-4-compilation-api). These are candidate mechanisms; neither per-query GPU timestamps nor persistent archives is implemented here yet.

## Duplicate, obsolete and compatibility code

- **Removed previously:** duplicate depth/no-depth native pipelines, redundant pipeline depth-format fields, unused stencil helpers, duplicate owner fields, per-copy Java adapters/arenas and redundant Java encoder-ending calls.
- **Removed now:** per-pass clear arrays/arenas and duplicated temporary error-buffer allocation in MetalFX/waits.
- **Still worth simplifying:** repeated state/binding marshaling; repeated texel view creation; dynamic-buffer full-copy handling; optional diagnostic/label allocations after profiling. Keep cleanup local to the owning abstraction rather than adding a general cache framework.
- **Keep:** Minecraft/Fabric adapters, Sodium integration, GLSL/SPIR-V translation for game/mod/resource-pack shaders, and the small Cocoa window/layer bridge. These still serve compatibility or platform integration. Moving them to Swift purely to increase Swift line count does not establish a performance benefit.
- **Not next:** temporal MetalFX needs motion vectors, jitter, history and correct resets. Spatial upscaling already works; temporal support would add substantial rendering state and testing scope.
- **Compatibility still to verify:** recent gameplay logs did not include Sodium, so passing current manual testing does not validate Sodium integration. GPU smoke tests also do not establish support across every Apple Silicon generation.

## Verification and one combined manual run

Run `./gradlew build checkGpu --offline` in a GPU-accessible session. This builds the packaged jar, runs CPU/FFM/native ownership checks, then runs real GPU checks with Metal API Validation, including the production Java clear renderer. This batch passed on Apple M4 with Metal API Validation enabled, including the scratch-reuse fixture and production clear renderer. Log: `build/native/scratch-validation.log`.

Use `build/libs/metallum-0.0.24.jar` for the combined manual run: fresh launch/menu, gameplay, MetalFX Off → 85% → 50% → Off, resize/fullscreen, F3+T reload, leave/re-enter the world, restart and verify slider persistence. Inspect memory after GPU completion. Record any recurring pink bar separately. Keep the existing Java arguments.
