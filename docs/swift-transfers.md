# Grouped GPU transfers and reusable copy storage

The public version remains **0.0.24**, with the matching native library bundled in `metallum-0.0.24.jar`. The internal Java/Swift ABI is 18 because the diagnostics snapshot gained two fields; this is not a mod-version suffix.

## Changes

- Consecutive copies with the same fence share one Metal 4 compute encoder. Each additional transfer has an explicit blit-to-blit intrapass barrier. A fence change, render pass, MetalFX call, presentation, debug-group boundary or submission closes the copy encoder and updates its fence. Abandoned commands also close it before returning storage to the pool.
- Copy resources stay retained until GPU completion, even when their application IDs disappear. Existing queue barriers between passes remain in place.
- Each Java command buffer reuses its copy adapter and 16-word payload. The device reuses a 128-byte FFM payload and a 4 KiB error buffer; the Swift decoder uses fixed-size value storage. Per-copy arenas, varargs arrays and redundant Java encoder-close calls are removed.
- Texture upload/readback validation covers the full buffer extent, row pitch, alignment and supported pixel size with overflow-safe arithmetic. The final row does not require unused padding. Texture-to-texture copies check matching formats and sample counts. Zero-length buffer copies do not open a Metal pass.
- Diagnostics include copy-command and copy-pass counts. Normal shutdown prints `Metal copy encoding since last report: N transfers in M passes`, with no extra JVM argument. When periodic diagnostics are enabled, the shutdown line covers only the remaining interval. Counts describe encoding, including work subsequently abandoned; they are not GPU timing or FPS measurements. MetalFX-internal work and its optional intermediate copies are outside these counters.

## Verification

`./gradlew build checkNative --offline` covers descriptors and transfer bounds, all four production Java copy adapters with reused payloads, diagnostics field mapping, resource ownership, settings and jar/native ABI matching.

`MTL_DEBUG_LAYER=1 sh native/tests/smoke.sh` passed on the Apple M4. The GPU test verifies a four-operation dependent round trip (buffer -> texture -> texture -> buffer -> buffer) in one copy pass, complete pixel/data readback, changing fences, copy-to-render-to-copy transitions, invalid transfer rejection, counter draining and destruction of a command with an open copy encoder. The existing draw batching, MetalFX reuse/resize/cache-release and ownership regressions also pass.

The user-provided Prism log for the preceding ABI 17 build showed a successful world load, resource reload and clean exit on Minecraft 26.2 / Java 25.0.1 / Apple M4. It did not include performance diagnostics or Sodium, so it does not establish an FPS baseline or Sodium gameplay coverage.

## One combined gameplay check

Replace the previous jar with `build/libs/metallum-0.0.24.jar`. Keep the existing native-access argument; no new arguments or settings are required. Play through chunk loading, F3+T reload, window/fullscreen changes, MetalFX Off/on, and world exit/re-entry, then quit normally. The shutdown log now reports transfer grouping. Test Sodium separately if used. Automated correctness checks do not measure gameplay frame pacing or prove the absence of long-run leaks.

The synchronization design follows Apple's [intrapass synchronization](https://developer.apple.com/documentation/metal/synchronizing-stages-within-a-pass) and [fence synchronization](https://developer.apple.com/documentation/metal/synchronizing-passes-with-a-fence) contracts, checked against the installed SDK.
