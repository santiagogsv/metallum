# Memory lifetime audit — Swift milestone 7

Version `0.0.24-swift.7`, ABI 7; bundled Swift library, macOS 27 / Swift 6.4 / MSL 4.1.

## Findings and fixes

1. The dynamic backing-buffer cache retained an unlimited number of buffers in exact-size buckets until device shutdown. It now retains at most 64 MiB of idle buffer storage and at most three buffers per size. Empty buckets are removed. Excess buffers are released only after the existing GPU retirement queue has completed their safe lifetime. This cap excludes active/in-flight buffers and Java container overhead. It may increase allocation churn in workloads that previously benefited from a larger cache; performance needs gameplay comparison.
2. Swift resource calls now use local autorelease pools. ARC retains exported resources in the device table before temporary framework objects are drained. Previously those calls relied on an enclosing pool on the Java render thread. This fixes a lifetime hazard; it does not prove it caused the reported RAM increase.
3. CAMetalLayer was created with `new` but its own retain was not released by device teardown. It now closes idempotently after the Cocoa view is detached, and initialization failure also releases its owning reference.
4. Pending clear maps could keep references to textures that were never consumed by another pass. Final texture retirement now removes those entries; surviving views preserve the texture's existing lifetime.
5. Added a native memory snapshot and opt-in periodic logging to distinguish renderer ownership, idle caches and Metal allocations from Java heap/process totals.

The existing shader/pipeline caches are cleared on resource reload. Their working-set size is not necessarily constant while new shaders/scenes are first encountered. Three completion upcall stubs per device still use a process-lifetime arena in the legacy command bridge; they are not created per frame. Their replacement belongs with Swift command completion migration, because freeing callback storage while GPU callbacks can execute is unsafe.

## Install and measure

Quit Minecraft and replace the jar with `metallum-0.0.24-swift.7.jar`. Keep the old native-library path argument removed.

For this memory investigation, add `-Dmetallum.memoryDiagnostics=true` to Prism's Java arguments. This is optional and only enables a log line every 30 seconds during submissions. Look for `[metallum-memory]` in `logs/latest.log`:

- `buffers`, `resources`: entries owned by the Swift context; resource views can alias allocations.
- `libraries`: cached Swift shader libraries.
- `bufferMiB`: summed lengths of Swift-owned buffers, including active/in-flight/cache buffers.
- `metalMiB`: Metal device `currentAllocatedSize`; includes Metal resources beyond the buffer count.
- `idlePoolMiB`: unused reusable dynamic buffers; capped at 64 MiB.

Warm up the same world/settings for a few minutes, remain in the same area for five minutes, then perform five resource reloads separated by 30–60 seconds. Leave/re-enter the world and compare settled samples rather than immediate reload peaks. Record Activity Monitor memory at the same times. Repeated upward steps under equivalent settled workload deserve further investigation. Returning to exactly the initial process RAM figure is not required, and no fixed RAM reduction is promised by these changes.

## Validation and limits

- Full Java/Fabric/Swift build and packaged-library extraction/loading passed.
- Actual bounded-pool implementation passed tests for diverse-size retention, byte/per-size limits, reuse accounting, idempotent teardown, late recycling and double release.
- Java/C fixture tests ran 100 ownership cycles, returning to zero owned buffers/resources before each device teardown; memory snapshot layout and state lifetime checks passed. This fixture does not emulate the GPU.
- Real Swift descriptor tests passed. The real Metal smoke executable compiled but still stops at `Metal unavailable` in this tool environment.
- No live Activity Monitor/Instruments trace was collected here. These are concrete code-level fixes, not certification that the entire renderer or Minecraft is leak-free. Use the new diagnostics to guide the next profiling pass before changing command submission architecture.

References: [Apple on autorelease pool lifetimes](https://developer.apple.com/library/archive/documentation/Cocoa/Conceptual/MemoryMgmt/Articles/mmAutoreleasePools.html), [Metal allocated resource memory](https://developer.apple.com/documentation/metal/mtldevice/currentallocatedsize).
