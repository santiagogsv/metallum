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

final class NativeSubmission {
    let command: any MTLCommandBuffer
    let completion = SubmissionCompletion()
    init(_ command: any MTLCommandBuffer) { self.command = command }
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
        return true
    }
    deinit { command.waitUntilCompleted() }
}

@c(metallum_submit)
public func metallumSubmit(_ handle: UnsafeMutableRawPointer?, _ commandPointer: UnsafeMutableRawPointer?) -> UInt64 {
    autoreleasepool {
        guard let handle, let commandPointer,
              let command = Unmanaged<AnyObject>.fromOpaque(commandPointer).takeUnretainedValue() as? any MTLCommandBuffer,
              command.status == .notEnqueued || command.status == .enqueued else { return 0 }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        guard command.device === context.device else { return 0 }
        // Reserve the ID before constructing an owner that waits during destruction.
        guard context.nextResourceID < UInt64.max else { return 0 }
        let submission = NativeSubmission(command)
        let id = context.storeResource(submission)
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
