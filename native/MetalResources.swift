import Foundation
import Metal

// This table owns textures, texture views and samplers. Buffer IDs remain in their own table.
extension DeviceContext {
    func storeResource(_ resource: AnyObject) -> UInt64 {
        guard nextResourceID < UInt64.max else { return 0 }
        let id = nextResourceID
        nextResourceID += 1
        resources[id] = resource
        return id
    }
}

@c(metallum_texture_create)
public func metallumTextureCreate(_ handle: UnsafeMutableRawPointer?, _ pixelFormat: UInt64,
                                 _ width: UInt32, _ height: UInt32, _ layers: UInt32, _ mipLevels: UInt32,
                                 _ cube: UInt32, _ renderTarget: UInt32, _ label: UnsafePointer<CChar>?) -> UInt64 {
    guard let handle, let descriptor = ResourceDescriptors.texture(pixelFormat: pixelFormat, width: width, height: height,
            layers: layers, mipLevels: mipLevels, cube: cube, renderTarget: renderTarget) else { return 0 }
    let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
    guard let texture = context.device.makeTexture(descriptor: descriptor) else { return 0 }
    if let label { texture.label = String(cString: label) }
    return context.storeResource(texture as AnyObject)
}

@c(metallum_texture_view_create)
public func metallumTextureViewCreate(_ handle: UnsafeMutableRawPointer?, _ textureID: UInt64,
                                     _ baseMip: UInt32, _ mipCount: UInt32) -> UInt64 {
    guard let handle, mipCount > 0 else { return 0 }
    let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
    guard let texture = context.resources[textureID] as? any MTLTexture,
          Int(baseMip) < texture.mipmapLevelCount,
          Int(mipCount) <= texture.mipmapLevelCount - Int(baseMip) else { return 0 }
    // A second table entry supplies independent ownership without making a redundant Metal view.
    if baseMip == 0 && Int(mipCount) == texture.mipmapLevelCount {
        return context.storeResource(texture as AnyObject)
    }
    let slices: Int
    switch texture.textureType {
    case .typeCube, .typeCubeArray: slices = texture.arrayLength * 6
    case .type2DArray: slices = texture.arrayLength
    default: slices = 1
    }
    guard let view = texture.makeTextureView(pixelFormat: texture.pixelFormat, textureType: texture.textureType,
                                            levels: Int(baseMip)..<(Int(baseMip) + Int(mipCount)), slices: 0..<slices) else { return 0 }
    return context.storeResource(view as AnyObject)
}

@c(metallum_sampler_create)
public func metallumSamplerCreate(_ handle: UnsafeMutableRawPointer?, _ repeatU: UInt32, _ repeatV: UInt32,
                                 _ linearMin: UInt32, _ linearMag: UInt32, _ anisotropy: UInt32,
                                 _ maxLod: Double) -> UInt64 {
    guard let handle, let descriptor = ResourceDescriptors.sampler(repeatU: repeatU, repeatV: repeatV,
            linearMin: linearMin, linearMag: linearMag, anisotropy: anisotropy, maxLod: maxLod) else { return 0 }
    let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
    guard let sampler = context.device.makeSamplerState(descriptor: descriptor) else { return 0 }
    return context.storeResource(sampler as AnyObject)
}

@c(metallum_resource_borrow_mtl)
public func metallumResourceBorrowMTL(_ handle: UnsafeMutableRawPointer?, _ id: UInt64) -> UnsafeMutableRawPointer? {
    guard let handle else { return nil }
    let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
    guard let object = context.resources[id] else { return nil }
    return Unmanaged.passUnretained(object).toOpaque()
}

@c(metallum_resource_destroy)
public func metallumResourceDestroy(_ handle: UnsafeMutableRawPointer?, _ id: UInt64) {
    guard let handle else { return }
    let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
    context.resources.removeValue(forKey: id)
}

// Descriptor policy can be checked against the Metal SDK without creating a GPU device.
enum ResourceDescriptors {
    static func texture(pixelFormat: UInt64, width: UInt32, height: UInt32, layers: UInt32,
                        mipLevels: UInt32, cube: UInt32, renderTarget: UInt32) -> MTLTextureDescriptor? {
        guard width > 0, height > 0, layers > 0, mipLevels > 0, cube <= 1, renderTarget <= 1,
              let format = MTLPixelFormat(rawValue: UInt(pixelFormat)), format != .invalid else { return nil }
        guard mipLevels <= UInt32.bitWidth - max(width, height).leadingZeroBitCount else { return nil }
        if cube == 1 && (layers % 6 != 0 || width != height) { return nil }
        let descriptor = MTLTextureDescriptor()
        descriptor.pixelFormat = format
        descriptor.width = Int(width)
        descriptor.height = Int(height)
        descriptor.mipmapLevelCount = Int(mipLevels)
        descriptor.storageMode = .private
        descriptor.hazardTrackingMode = .untracked
        descriptor.usage = renderTarget == 1 ? [.shaderRead, .renderTarget] : [.shaderRead]
        if cube == 1 {
            descriptor.textureType = layers > 6 ? .typeCubeArray : .typeCube
            descriptor.arrayLength = Int(layers / 6)
        } else {
            descriptor.textureType = layers > 1 ? .type2DArray : .type2D
            descriptor.arrayLength = Int(layers)
        }
        return descriptor
    }

    static func sampler(repeatU: UInt32, repeatV: UInt32, linearMin: UInt32, linearMag: UInt32,
                        anisotropy: UInt32, maxLod: Double) -> MTLSamplerDescriptor? {
        guard repeatU <= 1, repeatV <= 1, linearMin <= 1, linearMag <= 1,
              anisotropy >= 1, anisotropy <= 16 else { return nil }
        let descriptor = MTLSamplerDescriptor()
        descriptor.sAddressMode = repeatU == 1 ? .repeat : .clampToEdge
        descriptor.tAddressMode = repeatV == 1 ? .repeat : .clampToEdge
        descriptor.minFilter = linearMin == 1 ? .linear : .nearest
        descriptor.magFilter = linearMag == 1 ? .linear : .nearest
        descriptor.mipFilter = maxLod > 0.25 ? .linear : .nearest
        descriptor.maxAnisotropy = Int(anisotropy)
        descriptor.lodMinClamp = 0
        let clampedLod = maxLod.isNaN ? Double.nan : max(0.25, maxLod)
        descriptor.lodMaxClamp = clampedLod.isFinite ? Float(min(clampedLod, Double(Float.greatestFiniteMagnitude))) : Float.greatestFiniteMagnitude
        return descriptor
    }
}
