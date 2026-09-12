import Foundation
import Metal

private final class DeviceContext {
    let device: MTLDevice
    init(_ device: MTLDevice) { self.device = device }
}

@_cdecl("metallum_abi_version")
public func metallumABIVersion() -> UInt32 { 1 }

@_cdecl("metallum_device_create")
public func metallumDeviceCreate() -> UnsafeMutableRawPointer? {
    autoreleasepool {
        guard let device = MTLCreateSystemDefaultDevice() else { return nil }
        return Unmanaged.passRetained(DeviceContext(device)).toOpaque()
    }
}

// Temporary migration escape hatch. The returned object is borrowed, never released by Java.
@_cdecl("metallum_device_borrow_mtl")
public func metallumDeviceBorrowMTL(_ handle: UnsafeMutableRawPointer?) -> UnsafeMutableRawPointer? {
    guard let handle else { return nil }
    let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
    return Unmanaged.passUnretained(context.device as AnyObject).toOpaque()
}

@_cdecl("metallum_device_destroy")
public func metallumDeviceDestroy(_ handle: UnsafeMutableRawPointer?) {
    guard let handle else { return }
    Unmanaged<DeviceContext>.fromOpaque(handle).release()
}
