# Metal 4 resource reuse and compilation

Public version **0.0.24**; bundled Java/Swift ABI **20**. Target remains macOS 27, Metal 4, MSL 4.1 and Metal 4 MetalFX spatial scaling. No additional JVM arguments or setup are needed.

## Six coordinated improvements

1. **Dynamic upload reuse.** Each buffer records the submission in which its backing was exposed to GPU work. CPU uploads reuse backing that has not been used, or whose use is covered by completed submissions. Otherwise the existing bounded pool provides replacement storage. Completion tracking accounts for every earlier pending submission. Partial replacements copy only the prefix and suffix outside the updated range, excluding allocation padding. Empty uploads do nothing. This avoids a new upload allocator and retains earlier draws' data snapshots.
2. **Texel view reuse.** Each actual native buffer backing owns at most one cached Metal texel view, keyed by format, offset, width and byte length. Matching bindings reuse it; a changed range replaces it. The owning buffer releases its view. Native commands retain views already encoded until completion, so eviction and backing replacement do not invalidate earlier work. The one-entry bound intentionally favors a simple common-case cache over an unbounded view dictionary.
3. **Unchanged uniform bindings.** Rebinding the same buffer slice leaves descriptors clean. A backing-generation change invalidates uniforms, texel views and vertex buffers before the next draw, even if Minecraft never calls `setUniform` again. Invalidation is deliberately conservative across the active pass; it avoids adding a second per-binding ownership registry.
4. **Combined native bindings.** One FFM call binds a buffer to either/both shader stages. One call binds a texture and its sampler to either/both stages. A dual-stage texture/sampler binding drops from four crossings to one; a dual-stage uniform drops from two to one. Eight obsolete Java per-stage/offset setters, their native dispatch paths, and two per-pass buffer arrays were removed. The same Metal argument-table update logic handles all active binding paths.
5. **Persistent Metal 4 pipelines.** Swift owns a `MTL4PipelineDataSetSerializer` and [`MTL4Archive`](https://developer.apple.com/documentation/metal/mtl4archive) cache for render pipelines. Keys include complete shader-source hashes, entry points and pipeline descriptors; the directory includes GPU registry identity, OS build and compilation-policy schema. Archives are capped at 16 MiB each, 512 entries and 128 MiB per platform directory. Atomic replacement and SHA-256 integrity checks precede Metal loading. Missing, mismatched, damaged or unavailable files fall back to compilation; persistence failures disable binary capture for that context. The default location is macOS's user cache directory under `com.metallum`. Tests use `build/native/pipeline-cache`. This caches pipeline binaries; GLSL/SPIR-V/MSL translation and source-library creation still exist.
6. **Attachment stores and synchronization.** Render store actions begin as deferred (`unknown`) and resolve once at pass end. Before switching render attachments, an active encoder can discard a color/depth result only when the next pass load-clears that exact resource completely. Other attachments keep their store action. Queue consumer barriers already order earlier work before render/copy consumers; duplicate waits in those covered paths were removed. Presentation waits at the fragment stage that actually samples its source. Fence updates and MetalFX's wait/update contract remain intact. See Apple's [queue consumer barrier contract](https://developer.apple.com/documentation/Metal/synchronizing-passes-with-consumer-barriers). The remaining Java fence-wait API and built-in clear wait were retained.

These changes remove specific allocations, copies, resource creation and FFM crossings. They do not establish a Minecraft FPS improvement without a representative performance capture. Pipeline persistence primarily targets repeated compilation, not steady-state frame rate.

## Validation

Run `./gradlew build checkGpu --offline` in a GPU-accessible session. The combined suite passed on Apple M4 with Metal API Validation enabled. Log: `build/native/metal4-batch-validation.log`.

The expanded checks cover:

- CPU/FFM binding payloads for both stages, cached-view identity, range replacement, ownership and packaged ABI.
- Real GPU upload snapshots before/after partial updates, reuse before first use and after completion, and empty uploads.
- Production Java uniform and texel-buffer draws: red and green halves must retain distinct snapshots when backing changes inside one pass; repeated unchanged bindings must cause no argument-table writes or skipped native writes.
- A full color replacement while a removed depth attachment retains its original contents.
- Metal 4 archives across fresh compiler instances, damaged-file fallback, key invalidation and an unavailable cache location.
- Existing indexed/indirect draws, grouped transfers, MetalFX scaling, resource lifetime and production clear-state regressions, with Metal API Validation.

The archive corruption test reproduced a driver crash while loading malformed data; integrity verification now rejects that file before invoking Metal. Automatic approval review rejected an additional removal of the remaining fence-wait API because it considered the synchronization proof insufficient; that removal was not performed.

One combined manual check remains: fresh launch and a second launch, gameplay, MetalFX Off → 85% → 50% → Off, resize/fullscreen, F3+T, world transitions, and restart/persistence. Watch for the previously reported brief menu bar. Automated GPU checks do not replace gameplay or establish Sodium compatibility for a launch without Sodium.
