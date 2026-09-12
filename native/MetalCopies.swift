import Foundation
import Metal

@c(metallum_fence_create)
public func metallumFenceCreate(_ handle: UnsafeMutableRawPointer?) -> UInt64 {
    autoreleasepool {
        guard let handle else { return 0 }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        guard let fence = context.device.makeFence() else { return 0 }
        return context.storeResource(fence as AnyObject)
    }
}

// A fixed value payload describes one copy; consecutive copies share a native encoder.
struct CopyDescription {
    let words: SIMD16<UInt64>
    init(_ words: [UInt64]) throws {
        guard words.count == 16 else { throw PipelineDescriptionError.invalid("Invalid copy payload") }
        try self.init(SIMD16(words))
    }
    init(_ words: SIMD16<UInt64>) throws {
        guard words[0] <= 3, (0..<16).allSatisfy({ words[$0] <= UInt64(Int.max) }) else {
            throw PipelineDescriptionError.invalid("Invalid copy payload")
        }
        self.words = words
    }
    subscript(_ index: Int) -> Int { Int(words[index]) }
    func range(_ offset: Int, _ count: Int, _ length: Int) -> Bool { offset <= length && count <= length - offset }
    // Last-row extent rather than rowBytes * height accepts padded rows without
    // requiring unused padding after the last row. Arithmetic cannot overflow.
    static func bufferRegion(offset: Int, row: Int, image: Int, width: Int, height: Int,
                             pixelBytes: Int, length: Int) -> Bool {
        guard offset >= 0, offset <= length, row > 0, image >= 0, width > 0, height > 0, pixelBytes > 0,
              offset % pixelBytes == 0, row % pixelBytes == 0, image % pixelBytes == 0,
              width <= row / pixelBytes else { return false }
        let tail = width * pixelBytes
        guard tail <= length - offset else { return false }
        return height - 1 <= (length - offset - tail) / row
    }
    static func pixelBytes(_ format: MTLPixelFormat) -> Int {
        switch format {
        case .r8Unorm, .r8Snorm, .r8Uint, .r8Sint, .stencil8: return 1
        case .r16Unorm, .r16Snorm, .r16Uint, .r16Sint, .r16Float,
             .rg8Unorm, .rg8Snorm, .rg8Uint, .rg8Sint, .depth16Unorm: return 2
        case .r32Uint, .r32Sint, .r32Float, .rg16Unorm, .rg16Snorm, .rg16Uint, .rg16Sint, .rg16Float,
             .rgba8Unorm, .bgra8Unorm, .rgba8Snorm, .rgba8Uint, .rgba8Sint,
             .rgb10a2Unorm, .rg11b10Float, .depth32Float, .depth24Unorm_stencil8: return 4
        case .rg32Uint, .rg32Sint, .rg32Float, .rgba16Unorm, .rgba16Snorm, .rgba16Uint, .rgba16Sint,
             .rgba16Float, .depth32Float_stencil8: return 8
        case .rgba32Uint, .rgba32Sint, .rgba32Float: return 16
        default: return 0 // Minecraft's supported formats are uncompressed.
        }
    }
    func bufferRegion(_ buffer: any MTLBuffer, texture: any MTLTexture, offset: Int) -> Bool {
        texture.sampleCount == 1 && Self.bufferRegion(offset: offset, row: self[13], image: self[14],
            width: self[7], height: self[8], pixelBytes: Self.pixelBytes(texture.pixelFormat), length: buffer.length)
    }
    func region(_ texture: any MTLTexture, destination: Bool) -> Bool {
        let slice = self[destination ? 9 : 3], level = self[destination ? 10 : 4]
        let x = self[destination ? 11 : 5], y = self[destination ? 12 : 6]
        let slices = texture.textureType == .typeCube || texture.textureType == .typeCubeArray ? texture.arrayLength * 6 : texture.arrayLength
        guard level < texture.mipmapLevelCount, slice < slices, self[7] > 0, self[8] > 0 else { return false }
        return range(x, self[7], max(1, texture.width >> level)) && range(y, self[8], max(1, texture.height >> level))
    }
}

@c(metallum_copy_pass)
public func metallumCopyPass(_ handle: UnsafeMutableRawPointer?, _ commandID: UInt64, _ fenceID: UInt64,
                            _ payload: UnsafePointer<UInt64>?, _ count: UInt32,
                            _ errorOutput: UnsafeMutablePointer<CChar>?, _ capacity: UInt32) -> Int32 {
    autoreleasepool {
        ShaderCompilation.writeError("", to: errorOutput, capacity: capacity)
        do {
            guard let handle, let payload, count == 16 else { throw PipelineDescriptionError.invalid("Invalid copy arguments") }
            let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
            guard let command = context.resources[commandID] as? NativeCommand, command.canEncode,
                  let fence = context.resources[fenceID] as? any MTLFence else { throw PipelineDescriptionError.invalid("Invalid copy command or fence") }
            let d = try CopyDescription(SIMD16(UnsafeBufferPointer(start: payload, count: 16)))
            let srcBuffer = context.buffers[d.words[1]], dstBuffer = context.buffers[d.words[2]]
            let srcTexture = context.resources[d.words[1]] as? any MTLTexture
            let dstTexture = context.resources[d.words[2]] as? any MTLTexture
            // Validate object types and copy regions before opening the encoder.
            switch d[0] {
            case 0:
                guard let srcBuffer, let dstBuffer, d.range(d[3], d[15], srcBuffer.length), d.range(d[9], d[15], dstBuffer.length) else { throw PipelineDescriptionError.invalid("Invalid buffer copy range") }
            case 1:
                guard let srcBuffer, let dstTexture, d.bufferRegion(srcBuffer, texture: dstTexture, offset: d[3]), d.region(dstTexture, destination: true) else { throw PipelineDescriptionError.invalid("Invalid texture upload") }
            case 2:
                guard let srcTexture, let dstBuffer, d.bufferRegion(dstBuffer, texture: srcTexture, offset: d[9]), d.region(srcTexture, destination: false) else { throw PipelineDescriptionError.invalid("Invalid texture readback") }
            default:
                guard let srcTexture, let dstTexture, srcTexture.pixelFormat == dstTexture.pixelFormat, srcTexture.sampleCount == dstTexture.sampleCount, d.region(srcTexture, destination: false), d.region(dstTexture, destination: true) else { throw PipelineDescriptionError.invalid("Invalid texture copy region") }
            }
            if d[0] == 0 && d[15] == 0 { return 1 }
            let encoder = try command.copyPass(fence: fence)
            let size = MTLSize(width: d[7], height: d[8], depth: 1)
            let src = MTLOrigin(x: d[5], y: d[6], z: 0), dst = MTLOrigin(x: d[11], y: d[12], z: 0)
            switch d[0] {
            case 0: encoder.copy(sourceBuffer: srcBuffer!, sourceOffset: d[3], destinationBuffer: dstBuffer!, destinationOffset: d[9], size: d[15])
            case 1: encoder.copy(sourceBuffer: srcBuffer!, sourceOffset: d[3], sourceBytesPerRow: d[13], sourceBytesPerImage: d[14], sourceSize: size, destinationTexture: dstTexture!, destinationSlice: d[9], destinationLevel: d[10], destinationOrigin: dst)
            case 2: encoder.copy(sourceTexture: srcTexture!, sourceSlice: d[3], sourceLevel: d[4], sourceOrigin: src, sourceSize: size, destinationBuffer: dstBuffer!, destinationOffset: d[9], destinationBytesPerRow: d[13], destinationBytesPerImage: d[14])
            default: encoder.copy(sourceTexture: srcTexture!, sourceSlice: d[3], sourceLevel: d[4], sourceOrigin: src, sourceSize: size, destinationTexture: dstTexture!, destinationSlice: d[9], destinationLevel: d[10], destinationOrigin: dst)
            }
            switch d[0] {
            case 0: command.hold(srcBuffer as AnyObject?); command.hold(dstBuffer as AnyObject?)
            case 1: command.hold(srcBuffer as AnyObject?); command.hold(dstTexture as AnyObject?)
            case 2: command.hold(srcTexture as AnyObject?); command.hold(dstBuffer as AnyObject?)
            default: command.hold(srcTexture as AnyObject?); command.hold(dstTexture as AnyObject?)
            }
            return 1
        } catch {
            ShaderCompilation.writeError(error.localizedDescription, to: errorOutput, capacity: capacity)
            return 0
        }
    }
}
