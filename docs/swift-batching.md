# Native draw batches and direct MetalFX textures

Version `0.0.24`, native ABI 17. The jar bundles its matching Swift library.

## Changes

Indexed multi-draw calls pack up to 256 active draws into a reusable 4 KiB render-thread buffer. Swift looks up the pass and index buffer once per batch, validates the entire chunk, encodes its draws, and retains the index buffer until GPU completion. Signed base vertices, byte offsets, instancing, skipped nonpositive counts and input buffer positions retain their existing semantics. The packed IntBuffer overload reads from element zero; the separate-array overload reads from each buffer's current position. Triangle fans retain their required conversion path.

Indexed and non-indexed indirect batches each cross FFM once. Swift validates the whole argument-buffer range and loops over Metal's 20-byte/16-byte argument records; Java also checks the caller's slice length. These are native encoding loops, not GPU-generated indirect command buffers.

MetalFX uses the existing private source/destination textures directly when their usage flags satisfy the scaler's queried requirements. Only an incompatible side gets an intermediate texture and copy. Texture usage flags remain narrow. Synchronization uses the existing fence, which MetalFX waits and updates. Commands retain resources through GPU completion, including when the scaler cache is cleared. `upscaleMiB` reports intermediate textures only; zero does not mean the scaler or frame has no memory cost.

Removed the replaced Java per-draw bridge loops, unsafe array-pointer reinterpretation in multi-draw, the obsolete sampler-filter enum and pass-through ownership helpers. The adapter checks now use the runtime classpath so the actual LWJGL pointer-buffer path is exercised. No rendering fallback, configuration option or dependency was added.

## Validation and gameplay baseline

`./gradlew build checkNative --offline` checks Swift descriptor/range policy, Java/C batch packing (257 active draws across two chunks), signed base vertices, input positions, empty/invalid inputs, ownership, settings and packaged ABI matching.

`sh native/tests/smoke.sh` exercises actual Metal drawing and pixel readback for indexed batches and both indirect batch types, including removal of resource IDs before submission. It also checks MetalFX output across cache reuse, resize and cache release while work remains in flight. In a GPU-accessible session on the Apple M4, the new draw/MetalFX smoke checks passed, including with `MTL_DEBUG_LAYER=1` (Metal API Validation); all tested MetalFX targets required zero intermediate texture bytes. The sandbox itself reports Metal unavailable.

Gameplay FPS, frame pacing and visual quality still need a controlled Minecraft comparison. Install `build/libs/metallum-0.0.24.jar` in place of the previous jar. Keep `--enable-native-access=ALL-UNNAMED`; remove any old native-library override. For measurement, add `-Dmetallum.performanceDiagnostics=true`.

Compare the previous build and the current build from the same saved viewpoint, resolution, render distance, graphics settings, power mode and frame cap. Warm up each run, then collect at least 60 seconds at native resolution and at 85% MetalFX. Record median and p95/p99 frame time using a frame profiler, alongside the existing submission-duration, CPU-wait and memory logs. Submission timings are not frame timings. Exercise terrain/Sodium, water, UI, resource reload, world exit/re-entry, resize/fullscreen and Off/on transitions. No FPS improvement is claimed from the smoke tests.

Stage-specific barriers, attachment store/discard decisions and broader renderer rewrites remain profiling-driven follow-ups. No broad synchronization was weakened in this change; copy-specific barriers disappear with the unnecessary copy encoders.

MetalFX texture requirements were checked against the installed macOS SDK's `MTLFXSpatialScaler.h` and `MTL4FXSpatialScaler.h`.
