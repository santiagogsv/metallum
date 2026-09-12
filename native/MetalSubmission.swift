import Foundation
import Metal

// Callback captures only this group, never the command buffer or its owner.
// DispatchGroup supports repeatable timed waits without consuming completion state.
final class SubmissionCompletion: Sendable {
    private let group = DispatchGroup()
    init() { group.enter() }
    func finish() { group.leave() }
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

// Explicit command ownership is required by Metal 4. Establish it before changing encoders.
final class NativeCommand {
    let metal: any MTLCommandBuffer
    private weak var context: DeviceContext?
    private var residency: (any MTLResidencySet)?
    var encoderOpen = false
    var submitted = false
    private let references = CommandReferences()
    var canEncode: Bool { !submitted && !encoderOpen }
    init(_ metal: any MTLCommandBuffer, context: DeviceContext) { self.metal = metal; self.context = context }
    func hold(_ object: AnyObject?) {
        references.hold(object)
    }
    func prepareResidency() throws {
        guard let context, !references.objects.isEmpty else { return }
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
    }
}

final class NativeSubmission {
    let owner: NativeCommand
    var command: any MTLCommandBuffer { owner.metal }
    let completion = SubmissionCompletion()
    init(_ command: NativeCommand) { self.owner = command }
    func commit() {
        let completion = self.completion
        command.addCompletedHandler { _ in completion.finish() }
        command.commit()
    }
    func wait(milliseconds: Int64) -> Bool {
        guard completion.wait(milliseconds: milliseconds) else { return false }
        // The signal occurs inside the callback. Join the callback before allowing
        // resource retirement or unloading the library that contains its code.
        command.waitUntilCompleted()
        owner.retire()
        return true
    }
    deinit { command.waitUntilCompleted(); owner.retire() }
}

@c(metallum_command_buffer_create)
public func metallumCommandBufferCreate(_ handle: UnsafeMutableRawPointer?, _ label: UnsafePointer<CChar>?) -> UInt64 {
    autoreleasepool {
        guard let handle else { return 0 }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        guard let command = context.commandQueue?.makeCommandBuffer() else { return 0 }
        if let label { command.label = String(cString: label) }
        return context.storeResource(NativeCommand(command, context: context))
    }
}

@c(metallum_submit)
public func metallumSubmit(_ handle: UnsafeMutableRawPointer?, _ commandID: UInt64) -> UInt64 {
    autoreleasepool {
        guard let handle else { return 0 }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        guard let command = context.resources[commandID] as? NativeCommand, command.canEncode else { return 0 }
        // Reserve the ID before constructing an owner that waits during destruction.
        guard context.nextResourceID < UInt64.max else { return 0 }
        do { try command.prepareResidency() } catch { return 0 }
        let submission = NativeSubmission(command)
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
        if submission.command.status == .error {
            ShaderCompilation.writeError(submission.command.error?.localizedDescription ?? "Metal command failed", to: error, capacity: capacity)
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
