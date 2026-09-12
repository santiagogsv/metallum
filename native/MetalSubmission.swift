import Foundation
import Metal
import QuartzCore
import Synchronization

enum CommandQueueConfiguration {
    // macOS 27's descriptor has an assign feedbackQueue property, but destroying
    // a populated descriptor releases that queue. Clear the borrowed reference
    // before destruction; the device and submissions own the serial queue.
    static func withDescriptor<Result>(feedbackQueue: DispatchQueue,
            _ body: (MTL4CommandQueueDescriptor) throws -> Result) rethrows -> Result {
        let descriptor = MTL4CommandQueueDescriptor()
        descriptor.feedbackQueue = feedbackQueue
        defer { descriptor.feedbackQueue = nil }
        return try body(descriptor)
    }
}

// Callback captures only completion state, never the command buffer or its owner.
// DispatchGroup supports repeatable timed waits without consuming completion state.
final class SubmissionCompletion: Sendable {
    private let group = DispatchGroup()
    private struct Result { var error: String?; var gpuNanoseconds: UInt64? }
    private let result = Mutex(Result())
    var error: String? { result.withLock { $0.error } }
    var gpuNanoseconds: UInt64? { result.withLock { $0.gpuNanoseconds } }
    init() { group.enter() }
    func finish(error: String? = nil, start: Double = 0, end: Double = 0) {
        result.withLock { $0 = Result(error: error, gpuNanoseconds: error == nil ? RendererCounters.duration(start: start, end: end) : nil) }
        group.leave()
    }
    func wait(milliseconds: Int64) -> Bool {
        let deadline: DispatchTime = milliseconds == Int64.max ? .distantFuture : .now() + .milliseconds(Int(max(0, min(milliseconds, Int64(Int.max / 1_000_000)))))
        return group.wait(timeout: deadline) == .success
    }
}

// CPU-testable lifetime set; aliases retain an object only once per command.
final class CommandReferences {
    private(set) var objects: [ObjectIdentifier: AnyObject] = [:]
    func hold(_ object: AnyObject?) {
        if let object { objects[ObjectIdentifier(object)] = object }
    }
    func clear() { objects.removeAll(keepingCapacity: false) }
}

// Pure allocation arithmetic shared by the renderer and boundary tests.
struct InlinePlacement {
    static let chunkSize = 65536
    let chunk: Int
    let offset: Int
    let end: Int
    static func next(cursor: Int, length: Int) -> InlinePlacement? {
        guard cursor >= 0, cursor <= Int.max - chunkSize - 4096, (0...4096).contains(length) else { return nil }
        var start = (cursor + 255) & ~255
        if length > chunkSize - start % chunkSize { start = (start / chunkSize + 1) * chunkSize }
        return InlinePlacement(chunk: start / chunkSize, offset: start % chunkSize, end: start + max(1, length))
    }
}

// Reset only slots touched by the previous pass, rather than issuing hundreds
// of empty table writes for every pass. Metal snapshots these bindings at draws.
final class StageBindings {
    let metal: any MTL4ArgumentTable
    private var buffers = BindingValues(count: 31)
    private var textures = BindingValues(count: 128)
    private var samplers = BindingValues(count: 16)
    private let counters: RendererCounters
    init(device: any MTLDevice, descriptor: MTL4ArgumentTableDescriptor, counters: RendererCounters) throws {
        metal = try device.makeArgumentTable(descriptor: descriptor)
        self.counters = counters
    }
    func setAddress(_ address: MTLGPUAddress, index: Int) {
        guard buffers.update(address, index: index) else { counters.bindingSkips &+= 1; return }
        metal.setAddress(address, index: index); counters.bindingWrites &+= 1
    }
    func setTexture(_ resource: MTLResourceID, index: Int) {
        guard textures.update(resource._impl, index: index) else { counters.bindingSkips &+= 1; return }
        metal.setTexture(resource, index: index); counters.bindingWrites &+= 1
    }
    func setSamplerState(_ resource: MTLResourceID, index: Int) {
        guard samplers.update(resource._impl, index: index) else { counters.bindingSkips &+= 1; return }
        metal.setSamplerState(resource, index: index); counters.bindingWrites &+= 1
    }
    func reset() {
        for i in buffers.values.indices where buffers.values[i] != 0 { setAddress(0, index: i) }
        for i in textures.values.indices where textures.values[i] != 0 { setTexture(MTLResourceID(), index: i) }
        for i in samplers.values.indices where samplers.values[i] != 0 { setSamplerState(MTLResourceID(), index: i) }
    }
}

// A slot is reused only after GPU completion. Idle staging is capped at 64 KiB
// per slot; unusually large frames do not permanently raise the idle cache.
final class CommandSlot {
    let metal: any MTL4CommandBuffer
    let allocator: any MTL4CommandAllocator
    let vertex: StageBindings
    let fragment: StageBindings
    var staging: [any MTLBuffer] = []
    var stagingOffset = 0
    var reuses = 0
    var stagingBytes: UInt64 { staging.reduce(0) { $0 + UInt64($1.length) } }
    init(device: any MTLDevice, counters: RendererCounters) throws {
        guard let metal = device.makeCommandBuffer(), let allocator = device.makeCommandAllocator() else {
            throw PipelineDescriptionError.invalid("Cannot create Metal 4 command storage")
        }
        self.metal = metal; self.allocator = allocator
        let descriptor = MTL4ArgumentTableDescriptor()
        descriptor.maxBufferBindCount = 31
        descriptor.maxTextureBindCount = 128
        descriptor.maxSamplerStateBindCount = 16
        descriptor.initializeBindings = true
        vertex = try StageBindings(device: device, descriptor: descriptor, counters: counters)
        fragment = try StageBindings(device: device, descriptor: descriptor, counters: counters)
    }
    func resetBindings() { vertex.reset(); fragment.reset() }
    func reset() {
        allocator.reset()
        stagingOffset = 0
        if staging.count > 1 { staging.removeSubrange(1...) }
    }
}

final class NativeCommand {
    private var slot: CommandSlot?
    var metal: any MTL4CommandBuffer { slot!.metal }
    var vertex: StageBindings { slot!.vertex }
    var fragment: StageBindings { slot!.fragment }
    private weak var context: DeviceContext?
    private var residency: (any MTLResidencySet)?
    var drawables: [any CAMetalDrawable] = []
    var encoderOpen = false
    var submitted = false
    private let references = CommandReferences()
    var hasStorage: Bool { slot != nil }
    var hasResidency: Bool { residency != nil }
    var stagingBytes: UInt64 { slot?.stagingBytes ?? 0 }
    var allocatorBytes: UInt64 { slot?.allocator.allocatedSize() ?? 0 }
    var referenceCount: UInt64 { UInt64(references.objects.count) }
    func recordCompletion(_ nanoseconds: UInt64?) { context?.counters.record(nanoseconds) }
    func recordWait(_ nanoseconds: UInt64) { context?.counters.cpuWait &+= nanoseconds }
    var canEncode: Bool { !submitted && !encoderOpen && slot != nil }
    init(_ slot: CommandSlot, context: DeviceContext) {
        self.slot = slot; self.context = context
        slot.metal.beginCommandBuffer(allocator: slot.allocator)
    }
    func hold(_ object: AnyObject?) { references.hold(object) }
    func resetBindings() { slot!.resetBindings() }
    func inlineBytes(_ bytes: UnsafeRawPointer, length: Int) -> MTLGPUAddress? {
        guard let slot, let context else { return nil }
        guard let placement = InlinePlacement.next(cursor: slot.stagingOffset, length: length) else { return nil }
        if placement.chunk == slot.staging.count {
            guard let buffer = context.device.makeBuffer(length: InlinePlacement.chunkSize, options: [.storageModeShared, .hazardTrackingModeUntracked]) else { return nil }
            slot.staging.append(buffer)
        }
        let buffer = slot.staging[placement.chunk]
        buffer.contents().advanced(by: placement.offset).copyMemory(from: bytes, byteCount: length)
        slot.stagingOffset = placement.end
        hold(buffer as AnyObject)
        return buffer.gpuAddress + UInt64(placement.offset)
    }
    func prepareResidency() throws {
        guard let context else { throw PipelineDescriptionError.invalid("Device released") }
        let set = try context.acquireResidencySet()
        for object in references.objects.values {
            if let allocation = object as? any MTLAllocation { set.addAllocation(allocation) }
        }
        set.commit()
        metal.useResidencySet(set)
        residency = set
    }
    func retire() {
        if let set = residency { context?.recycleResidencySet(set); residency = nil }
        references.clear()
        drawables.removeAll()
        if let slot { context?.recycleCommandSlot(slot); self.slot = nil }
    }
    deinit {
        if !submitted, let slot { slot.metal.endCommandBuffer() }
        retire()
    }
}

final class NativeSubmission {
    let owner: NativeCommand
    let queue: any MTL4CommandQueue
    let feedbackQueue: DispatchQueue
    let completion = SubmissionCompletion()
    private var retired = false
    init(_ command: NativeCommand, queue: any MTL4CommandQueue, feedbackQueue: DispatchQueue) {
        owner = command; self.queue = queue; self.feedbackQueue = feedbackQueue
    }
    func commit() {
        let completion = self.completion
        let options = MTL4CommitOptions()
        options.addFeedbackHandler { feedback in completion.finish(error: feedback.error?.localizedDescription, start: feedback.gpuStartTime, end: feedback.gpuEndTime) }
        owner.metal.endCommandBuffer()
        for drawable in owner.drawables { queue.waitForDrawable(drawable) }
        queue.commit([owner.metal], options: options)
        for drawable in owner.drawables { queue.signalDrawable(drawable); drawable.present() }
    }
    func wait(milliseconds: Int64) -> Bool {
        if retired { return true }
        let start = DispatchTime.now().uptimeNanoseconds
        guard completion.wait(milliseconds: milliseconds) else {
            owner.recordWait(DispatchTime.now().uptimeNanoseconds - start)
            return false
        }
        // Join the entire callback before resource retirement or library unload.
        feedbackQueue.sync {}
        owner.recordWait(DispatchTime.now().uptimeNanoseconds - start)
        owner.recordCompletion(completion.gpuNanoseconds)
        owner.retire()
        retired = true
        return true
    }
    deinit { _ = wait(milliseconds: Int64.max) }
}

@c(metallum_command_buffer_create)
public func metallumCommandBufferCreate(_ handle: UnsafeMutableRawPointer?, _ label: UnsafePointer<CChar>?) -> UInt64 {
    autoreleasepool {
        guard let handle else { return 0 }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        guard context.commandQueue != nil, let slot = try? context.acquireCommandSlot() else { return 0 }
        let command = NativeCommand(slot, context: context)
        command.metal.label = label.map { String(cString: $0) }
        return context.storeResource(command)
    }
}

@c(metallum_submit)
public func metallumSubmit(_ handle: UnsafeMutableRawPointer?, _ commandID: UInt64) -> UInt64 {
    autoreleasepool {
        guard let handle else { return 0 }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        guard let command = context.resources[commandID] as? NativeCommand, command.canEncode else { return 0 }
        // Reserve the ID before constructing an owner that waits during destruction.
        guard context.nextResourceID < UInt64.max, let queue = context.commandQueue else { return 0 }
        do { try command.prepareResidency() } catch { return 0 }
        let submission = NativeSubmission(command, queue: queue, feedbackQueue: context.feedbackQueue)
        let id = context.storeResource(submission)
        command.submitted = true
        submission.commit()
        return id
    }
}

@c(metallum_submission_wait)
public func metallumSubmissionWait(_ handle: UnsafeMutableRawPointer?, _ id: UInt64, _ timeout: Int64,
                                  _ error: UnsafeMutablePointer<CChar>?, _ capacity: UInt32) -> Int32 {
    autoreleasepool {
        ShaderCompilation.writeError("", to: error, capacity: capacity)
        guard let handle, let submission = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue().resources[id] as? NativeSubmission else {
            ShaderCompilation.writeError("Unknown submission", to: error, capacity: capacity)
            return -1
        }
        guard submission.wait(milliseconds: timeout) else { return 0 }
        if let failure = submission.completion.error {
            ShaderCompilation.writeError(failure, to: error, capacity: capacity)
            return -1
        }
        return 1
    }
}

@c(metallum_command_debug)
public func metallumCommandDebug(_ handle: UnsafeMutableRawPointer?, _ id: UInt64, _ label: UnsafePointer<CChar>?) -> Int32 {
    autoreleasepool {
        guard let handle, let command = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue().resources[id] as? NativeCommand, !command.submitted else { return 0 }
        if let label { command.metal.pushDebugGroup(String(cString: label)) } else { command.metal.popDebugGroup() }
        return 1
    }
}

@c(metallum_texture_info)
public func metallumTextureInfo(_ handle: UnsafeMutableRawPointer?, _ id: UInt64, _ field: UInt32) -> UInt64 {
    autoreleasepool {
        guard let handle, let texture = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue().resources[id] as? any MTLTexture else { return 0 }
        switch field {
        case 0: return UInt64(texture.pixelFormat.rawValue)
        case 1: return UInt64(texture.width)
        case 2: return UInt64(texture.height)
        default: return 0
        }
    }
}
