# Swift 16.1: allocator retention and optional MetalFX

Targets macOS 27, Apple Silicon, Swift 6.4 and Metal 4. Native ABI 16 is bundled in the jar; remove old metallum.nativeLibrary overrides.

## Memory

Metal allocator reset permits reuse without shrinking allocation. Completed command slots exceeding 64 MiB are now discarded after 120 uses. Smaller slots remain reusable. The interval avoids reallocating every frame. This is not a hard memory cap: active or recurring heavy work can allocate more. Retirement happens after GPU completion.

Enable -Dmetallum.performanceDiagnostics=true to see allocatorTrims and allocator bytes. Check that retained memory settles after loading and resource reloads. Cached MetalFX texture bytes are reported separately. Process memory also includes Java and other Metal allocations.

## Enable upscaling

Java arguments:

```
--enable-native-access=ALL-UNNAMED -Dmetallum.renderScale=0.85
```

Omit renderScale to disable. Accepted scales are 0.67 up to but excluding 1; invalid values use native resolution. Restart after changing it.

The world renders at 85% width and height (about 28% fewer pixels). Swift encodes MetalFX spatial scaling using MTL4FXSpatialScaler, then Minecraft draws its GUI at normal resolution. Later postprocessing also remains full resolution. No temporal history or motion vectors are required. SPIR-V and SPIRV-Cross are unchanged.

Swift caches a scaler and its private input/output textures. Resizing releases the scaler cache; commands retain resources until completion. The world target object survives resizes and level changes because Minecraft renderers cache it. Its textures resize in place and are destroyed at renderer shutdown. Two copies isolate MetalFX texture requirements from Minecraft resources. Upscaling adds memory and GPU work; it may not help CPU-limited scenes. Unsupported devices keep native resolution.

## Validation and manual checks

build/checkNative passes: Swift allocator policy and descriptors, Java/C ownership and ABI, render-scale limits, packaged library checks, and existing render adapters. These checks do not execute MetalFX or apply the mixin in a running game. The GPU smoke test cannot run in this tool environment because Metal is unavailable.

Confirm a [metallum-metalfx] dimensions message in the game log. Check foliage, text, resource reloads, resize/fullscreen, world switching, entity outlines and post effects. Compare frame times at the same position/settings/power mode with scale 1 and 0.85. Watch allocatorTrims and memory after repeated reloads. The Swift 16 user log confirms upscaling was active at 1451x816 -> 1708x960. It also exposed a stale sky-renderer target on resize. Swift 16.1 preserves that target identity; repeated resize/fullscreen and world-change testing is still required. Speedup and memory reduction are not established.

API: [Apple MTL4FXSpatialScaler](https://developer.apple.com/documentation/metalfx/mtl4fxspatialscaler).
