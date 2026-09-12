# Pipeline simplification and clear-state isolation

Public version: **0.0.24**. The bundled Java/Swift ABI is 19; the filename remains `metallum-0.0.24.jar`.

## Changes

Each logical Minecraft pipeline now creates one native Metal 4 pipeline instead of separate with-depth/without-depth pipelines. Metal 4 uses the depth/stencil formats from the render pass, so those previous descriptors produced identical pipelines. Depth testing/writing remains controlled by the depth-stencil state. Clear-pipeline caches also share pipelines across depth formats, including the startup prewarm entries.

The C pipeline payload is reduced from 13 header words to 11 by removing ignored depth/stencil fields. The matching Java builder, Swift decoder, C header, test fixture and ABI checks were updated together. Duplicate owner fields and unused stencil-format helpers were removed. This halves native pipeline creation for each logical Minecraft pipeline, not total shader compilation time or total startup time.

Clear draws now explicitly disable culling, select filled triangles, reset depth bias and set their depth state. Previously, a clear inside an existing encoder could inherit raster state from an earlier draw and leave old pixels visible. The production Java renderer's real-GPU regression failed before this fix with `Clear left stale pixels with depth format 250 at pixel 0`; it passed after the fix. The test seeds magenta pixels, installs culling/wireframe/depth-bias state, performs a green clear, and verifies every pixel.

Repeated bindings of the same texture view and sampler avoid allocating a new binding record and avoid marking the descriptor dirty. Pending texture clears are still handled even when a binding is unchanged. Removing a binding now marks it dirty so an active shader cannot silently reuse the old binding; the existing missing-sampler validation applies on the next draw.

## Pink menu bar

The user reported a pink menu bar lasting one or two seconds at startup, with normal rendering afterwards. That exact symptom has not been reproduced. The inherited-clear-state defect is independently reproduced and fixed; it is a possible contributor, not a confirmed explanation of the reported bar. No speculative frame skipping or global texture clearing was added.

## Verification

`./gradlew build checkGpu --offline` passed on the Apple M4. The new `checkGpu` task runs `checkNative` first and enables Metal API Validation for GPU checks. It requires a GPU-accessible session.

Checks cover the compact pipeline descriptor, ownership, packaging, all existing draw/copy/MetalFX regressions, and the production Java clear renderer. The clear renderer reuses its pipeline across no-depth, D16 and D32 attachments, verifies green pixel output after hostile inherited state, verifies that changing depth formats does not grow its pipeline cache, and releases its resources.

GPU logs from this run are in `build/native/pipeline-validation.log` (reproduction before the clear-state fix) and `build/native/pipeline-validation-fixed.log` (passing suite). Automated checks do not establish Minecraft FPS gains or confirm that the one-time startup bar is gone.

Replace the previous jar with `build/libs/metallum-0.0.24.jar`. Keep the same Java arguments. One combined test should include a fresh launch/menu, gameplay, F3+T reload, MetalFX Off/on and resizing. Note whether the brief pink bar occurs again.
