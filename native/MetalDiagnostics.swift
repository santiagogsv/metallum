import Foundation
import Metal

// Render-thread counters. No GPU waits, callback locks, or sample history in snapshots.
final class RendererCounters {
    var allocatorTrims: UInt64 = 0
    var copyCommands: UInt64 = 0, copyPasses: UInt64 = 0
    var completed: UInt64 = 0, timed: UInt64 = 0, gpuTotal: UInt64 = 0, gpuMax: UInt64 = 0
    var cpuWait: UInt64 = 0, bindingWrites: UInt64 = 0, bindingSkips: UInt64 = 0
    func record(_ nanoseconds: UInt64?) {
        completed &+= 1
        if let nanoseconds { timed &+= 1; gpuTotal &+= nanoseconds; gpuMax = max(gpuMax, nanoseconds) }
    }
    func drain() -> [UInt64] {
        let result = [completed, timed, gpuTotal, gpuMax, cpuWait, bindingWrites, bindingSkips]
        completed = 0; timed = 0; gpuTotal = 0; gpuMax = 0; cpuWait = 0; bindingWrites = 0; bindingSkips = 0
        return result
    }
    static func duration(start: Double, end: Double) -> UInt64? {
        guard start.isFinite, end.isFinite, start > 0, end >= start else { return nil }
        let ns = (end - start) * 1_000_000_000
        guard ns.isFinite, ns < Double(UInt64.max) else { return nil }
        return UInt64(ns)
    }
}

// Zero denotes an unbound slot. Argument-table snapshots make repeated identical
// writes unnecessary; keeping the cache across passes is safe when reset clears it.
struct BindingValues {
    private(set) var values: [UInt64]
    init(count: Int) { values = Array(repeating: 0, count: count) }
    mutating func update(_ value: UInt64, index: Int) -> Bool {
        guard values[index] != value else { return false }
        values[index] = value
        return true
    }
}

@c(metallum_diagnostics_snapshot)
public func metallumDiagnosticsSnapshot(_ handle: UnsafeMutableRawPointer?, _ output: UnsafeMutablePointer<UInt64>?) {
    autoreleasepool {
        guard let handle, let output else { return }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        var commands: [ObjectIdentifier: NativeCommand] = [:]
        for object in context.resources.values {
            if let command = object as? NativeCommand { commands[ObjectIdentifier(command)] = command }
            if let submission = object as? NativeSubmission { commands[ObjectIdentifier(submission.owner)] = submission.owner }
        }
        let active = commands.values.filter { $0.hasStorage }
        var staging = context.idleCommandSlots.reduce(UInt64(0)) { $0 + $1.stagingBytes }
        var allocator = context.idleCommandSlots.reduce(UInt64(0)) { $0 + $1.allocator.allocatedSize() }
        for command in active { staging += command.stagingBytes; allocator += command.allocatorBytes }
        let values = context.counters.drain() + [UInt64(active.count), UInt64(context.idleCommandSlots.count),
            staging, allocator, active.reduce(0) { $0 + $1.referenceCount },
            UInt64(active.filter { $0.hasResidency }.count), UInt64(context.idleResidencySets.count), context.counters.allocatorTrims,
            context.spatialScaler.map { UInt64(($0.input?.allocatedSize ?? 0) + ($0.output?.allocatedSize ?? 0)) } ?? 0,
            context.counters.copyCommands, context.counters.copyPasses]
        context.counters.allocatorTrims = 0
        context.counters.copyCommands = 0; context.counters.copyPasses = 0
        for (i, value) in values.enumerated() { output[i] = value }
    }
}

// Reclaim burst-grown allocators after a reuse window, never on every frame.
enum CommandStoragePolicy {
    static func keep(bytes: UInt64, reuses: Int) -> Bool {
        bytes <= 64 * 1024 * 1024 || reuses < 120
    }
}
