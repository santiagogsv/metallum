# Swift 17: live MetalFX resolution

## Use

Install metallum-0.0.24-swift.17.jar, replacing the previous jar. In Minecraft's standard Options > Video Settings screen, scroll to the MetalFX slider. It ranges from 50% resolution to Off (native, 100%). Minecraft's delayed slider applies changes after adjustment; no restart is needed. This addition is to the standard video settings screen, not Sodium's replacement screen.

Only --enable-native-access=ALL-UNNAMED is needed. Remove old metallum.nativeLibrary overrides. The optional metallum.renderScale argument seeds the first unsaved setting; afterwards config/metallum-render-scale.txt wins, including when saved Off. Changes save atomically per instance.

85–90% is a conservative starting point, not a guarantee of unchanged visuals. Spatial upscaling reconstructs from fewer pixels; inspect foliage, thin lines and distant geometry while moving. At 50% width and height the world uses one quarter of the native pixels, so noticeable detail loss is likely. The GUI stays at native resolution. Native Off is the only setting that avoids upscaling-related quality loss.

## Changes and lifetime review

- Added a standard Minecraft slider with delayed application and validated saved values (50–100).
- Off releases the scaled target and Swift scaler cache. World unload and renderer close also release them. Commands retain GPU resources until submission completion.
- Sky rendering resolves the current main target instead of its construction-time cached target. This supports Off/on transitions, reloads and world changes without using a destroyed target.
- Output-size changes also resize auxiliary targets when rounding leaves the scaled dimensions unchanged.
- MetalFX writes invalidate the destination's cached clear state, preventing incorrect redundant-clear decisions.
- Existing oversized allocator retirement and bounded resource pools remain in place.

## Verified and remaining checks

Build and checkNative passed, including saved settings precedence, Off persistence, malformed values, range boundaries, temporary-file cleanup, Swift command-reference release, descriptor/allocator policy, FFM ownership, packaged ABI and existing adapters. These CPU checks do not validate a transformed Minecraft UI, GPU output or long-run process memory. No claim of zero bugs or zero leaks is made.

Manual regression: switch Off -> 85 -> 50 -> Off repeatedly; resize and toggle fullscreen at each setting; reload resources; leave and re-enter a world; close/reopen Video Settings and restart to verify persistence. Confirm native GUI sharpness and sky rendering. Use -Dmetallum.performanceDiagnostics=true to compare allocator and upscale bytes after repeated cycles. Cached upscaler bytes should return to zero with Off after a world frame, while in-flight resources retire normally. Process RSS need not immediately shrink because allocators and Java retain reusable memory.

Compare frame times at the same viewpoint, power mode and graphics settings. Upscaling adds copies and GPU work; CPU-limited scenes may show little improvement.

[Apple MetalFX documentation](https://developer.apple.com/documentation/metalfx) describes the lower-resolution rendering and reconstruction approach. The recommended percentages above are practical starting points, not Apple quality guarantees.
