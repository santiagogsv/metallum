import Foundation
import Metal
import MetalFX

final class DeviceContext {
    let counters = RendererCounters()
    var spatialScaler: SpatialUpscaler?
    var shaderLibraries: [String: any MTLLibrary] = [:]
    var resources: [UInt64: AnyObject] = [:]
    var nextResourceID: UInt64 = 1
    var buffers: [UInt64: MTLBuffer] = [:]
    var nextBufferID: UInt64 = 1
    let feedbackQueue = DispatchQueue(label: "com.metallum.metal4.feedback")
    lazy var commandQueue: (any MTL4CommandQueue)? = {
        try? CommandQueueConfiguration.withDescriptor(feedbackQueue: feedbackQueue) {
            try device.makeMTL4CommandQueue(descriptor: $0)
        }
    }()
    lazy var compiler: (any MTL4Compiler)? = try? device.makeCompiler(descriptor: MTL4CompilerDescriptor())
    var idleCommandSlots: [CommandSlot] = []
    func acquireCommandSlot() throws -> CommandSlot {
        if let slot = idleCommandSlots.popLast() { return slot }
        return try CommandSlot(device: device, counters: counters)
    }
    func recycleCommandSlot(_ slot: CommandSlot) {
        slot.reuses += 1
        guard CommandStoragePolicy.keep(bytes: slot.allocator.allocatedSize(), reuses: slot.reuses) else {
            counters.allocatorTrims &+= 1
            return
        }
        slot.reset()
        if idleCommandSlots.count < 3 { idleCommandSlots.append(slot) }
    }
    // Empty sets may be reused only after a completed submission retires them.
    var idleResidencySets: [any MTLResidencySet] = []
    func acquireResidencySet() throws -> any MTLResidencySet {
        if let set = idleResidencySets.popLast() { return set }
        let descriptor = MTLResidencySetDescriptor()
        descriptor.label = "Metallum command residency"
        return try device.makeResidencySet(descriptor: descriptor)
    }
    func recycleResidencySet(_ set: any MTLResidencySet) {
        set.removeAllAllocations(); set.commit()
        if idleResidencySets.count < 3 { idleResidencySets.append(set) }
    }
    let device: MTLDevice
    init(_ device: MTLDevice) { self.device = device }
}

@c(metallum_abi_version)
public func metallumABIVersion() -> UInt32 { 16 }

@c(metallum_device_create)
public func metallumDeviceCreate() -> UnsafeMutableRawPointer? {
    autoreleasepool {
        guard let device = MTLCreateSystemDefaultDevice() else { return nil }
        return Unmanaged.passRetained(DeviceContext(device)).toOpaque()
    }
}

// Temporary migration escape hatch. The returned object is borrowed, never released by Java.
@c(metallum_device_borrow_mtl)
public func metallumDeviceBorrowMTL(_ handle: UnsafeMutableRawPointer?) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard let handle else { return nil }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        return Unmanaged.passUnretained(context.device as AnyObject).toOpaque()
    }
}

@c(metallum_device_destroy)
public func metallumDeviceDestroy(_ handle: UnsafeMutableRawPointer?) {
    return autoreleasepool {
        guard let handle else { return }
        Unmanaged<DeviceContext>.fromOpaque(handle).release()
    }
}

// IDs are local to a device and never reused. Java never sees a Swift object layout.
@c(metallum_buffer_create)
public func metallumBufferCreate(_ handle: UnsafeMutableRawPointer?, _ length: UInt64, _ cpuAccessible: UInt32) -> UInt64 {
    return autoreleasepool {
        guard let handle, length > 0, length <= UInt64(Int.max), cpuAccessible <= 1 else { return 0 }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        guard length <= UInt64(context.device.maxBufferLength), context.nextBufferID < UInt64.max else { return 0 }
        let storage: MTLResourceOptions = cpuAccessible == 1 ? .storageModeShared : .storageModePrivate
        // The current render/blit encoder explicitly fences untracked resources.
        guard let buffer = context.device.makeBuffer(length: Int(length), options: [storage, .hazardTrackingModeUntracked]) else { return 0 }
        let id = context.nextBufferID
        context.nextBufferID += 1
        context.buffers[id] = buffer
        return id
    }
}

@c(metallum_buffer_borrow_mtl)
public func metallumBufferBorrowMTL(_ handle: UnsafeMutableRawPointer?, _ id: UInt64) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard let handle else { return nil }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        guard let buffer = context.buffers[id] else { return nil }
        return Unmanaged.passUnretained(buffer as AnyObject).toOpaque()
    }
}

@c(metallum_buffer_contents)
public func metallumBufferContents(_ handle: UnsafeMutableRawPointer?, _ id: UInt64) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard let handle else { return nil }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        guard let buffer = context.buffers[id], buffer.storageMode == .shared else { return nil }
        return buffer.contents()
    }
}

@c(metallum_buffer_destroy)
public func metallumBufferDestroy(_ handle: UnsafeMutableRawPointer?, _ id: UInt64) {
    return autoreleasepool {
        guard let handle else { return }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        context.buffers.removeValue(forKey: id)
    }
}

// Render-thread snapshot. Counts track ownership, not unique allocations (views may alias).
@c(metallum_memory_snapshot)
public func metallumMemorySnapshot(_ handle: UnsafeMutableRawPointer?, _ output: UnsafeMutablePointer<UInt64>?) {
    autoreleasepool {
        guard let handle, let output else { return }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        output[0] = UInt64(context.buffers.count)
        output[1] = UInt64(context.resources.count)
        output[2] = UInt64(context.shaderLibraries.count)
        output[3] = context.buffers.values.reduce(0) { $0 + UInt64($1.length) }
        output[4] = UInt64(context.device.currentAllocatedSize)
    }
}

@c(metallum_device_info)
public func metallumDeviceInfo(_ handle: UnsafeMutableRawPointer?, _ field: UInt32) -> UInt64 {
    guard let handle else { return 0 }
    let device = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue().device
    switch field {
    case 0: return UInt64(device.maxBufferLength)
    case 1: return device.recommendedMaxWorkingSetSize
    case 2: return device.supportsFamily(.metal4) ? 1 : 0
    case 3: return MTLFXSpatialScalerDescriptor.supportsMetal4FX(device) ? 1 : 0
    default: return 0
    }
}
@c(metallum_device_name)
public func metallumDeviceName(_ handle: UnsafeMutableRawPointer?, _ output: UnsafeMutablePointer<CChar>?, _ capacity: UInt32) {
    autoreleasepool {
        guard let handle else { return }
        ShaderCompilation.writeError(Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue().device.name, to: output, capacity: capacity)
    }
}
