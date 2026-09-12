import Foundation
import Metal
import MetalFX

final class SpatialUpscaler {
    let scaler: any MTL4FXSpatialScaler
    let input: (any MTLTexture)?
    let output: (any MTLTexture)?
    init(context: DeviceContext, source: any MTLTexture, destination: any MTLTexture) throws {
        let d = MTLFXSpatialScalerDescriptor()
        d.inputWidth = source.width; d.inputHeight = source.height
        d.outputWidth = destination.width; d.outputHeight = destination.height
        d.colorTextureFormat = source.pixelFormat; d.outputTextureFormat = destination.pixelFormat
        // Minecraft's RGBA8 render target contains display-referred color.
        d.colorProcessingMode = .perceptual
        guard let compiler = context.compiler,
              let scaler = d.makeSpatialScaler(device: context.device, compiler: compiler) else {
            throw PipelineDescriptionError.invalid("MetalFX spatial scaler unavailable")
        }
        self.scaler = scaler
        func texture(_ width: Int, _ height: Int, _ format: MTLPixelFormat, _ usage: MTLTextureUsage) throws -> any MTLTexture {
            let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: width, height: height, mipmapped: false)
            d.storageMode = .private; d.hazardTrackingMode = .untracked; d.usage = usage
            guard let t = context.device.makeTexture(descriptor: d) else { throw PipelineDescriptionError.invalid("Cannot allocate MetalFX texture") }
            return t
        }
        // Use existing private targets directly whenever they meet MetalFX's queried requirements.
        input = SpatialTexturePolicy.needsCopy(source.usage, scaler.colorTextureUsage, source.storageMode)
            ? try texture(source.width, source.height, source.pixelFormat, scaler.colorTextureUsage) : nil
        output = SpatialTexturePolicy.needsCopy(destination.usage, scaler.outputTextureUsage, destination.storageMode)
            ? try texture(destination.width, destination.height, destination.pixelFormat, scaler.outputTextureUsage) : nil
    }
    func matches(_ source: any MTLTexture, _ destination: any MTLTexture) -> Bool {
        scaler.inputWidth == source.width && scaler.inputHeight == source.height && scaler.colorTextureFormat == source.pixelFormat &&
        scaler.outputWidth == destination.width && scaler.outputHeight == destination.height && scaler.outputTextureFormat == destination.pixelFormat &&
        (input != nil) == SpatialTexturePolicy.needsCopy(source.usage, scaler.colorTextureUsage, source.storageMode) &&
        (output != nil) == SpatialTexturePolicy.needsCopy(destination.usage, scaler.outputTextureUsage, destination.storageMode)
    }
}

enum SpatialTexturePolicy {
    static func needsCopy(_ usage: MTLTextureUsage, _ required: MTLTextureUsage, _ storage: MTLStorageMode) -> Bool {
        storage != .private || !usage.contains(required)
    }
}

@c(metallum_upscale_clear)
public func metallumUpscaleClear(_ handle: UnsafeMutableRawPointer?) {
    guard let handle else { return }
    Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue().spatialScaler = nil
}

@c(metallum_upscale)
public func metallumUpscale(_ handle: UnsafeMutableRawPointer?, _ commandID: UInt64, _ sourceID: UInt64,
                           _ destinationID: UInt64, _ fenceID: UInt64,
                           _ errorOutput: UnsafeMutablePointer<CChar>?, _ capacity: UInt32) -> Int32 {
    autoreleasepool {
        ShaderCompilation.writeError("", to: errorOutput, capacity: capacity)
        do {
            guard let handle else { return 0 }
            let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
            guard let command = context.resources[commandID] as? NativeCommand, command.canEncode,
                  let source = context.resources[sourceID] as? any MTLTexture,
                  let destination = context.resources[destinationID] as? any MTLTexture,
                  let fence = context.resources[fenceID] as? any MTLFence,
                  source.width < destination.width, source.height < destination.height,
                  source.textureType == .type2D, destination.textureType == .type2D,
                  source.sampleCount == 1, destination.sampleCount == 1,
                  source.pixelFormat == .rgba8Unorm, destination.pixelFormat == .rgba8Unorm else {
                throw PipelineDescriptionError.invalid("Invalid MetalFX command, dimensions or color format")
            }
            command.endCopies()
            if context.spatialScaler?.matches(source, destination) != true {
                context.spatialScaler = try SpatialUpscaler(context: context, source: source, destination: destination)
            }
            let fx = context.spatialScaler!
            func copy(_ from: any MTLTexture, _ to: any MTLTexture) throws {
                guard let encoder = command.metal.makeComputeCommandEncoder() else {
                    throw PipelineDescriptionError.invalid("Cannot create MetalFX copy encoder")
                }
                encoder.barrier(afterQueueStages: .all, beforeStages: .blit, visibilityOptions: .device)
                encoder.waitForFence(fence, beforeEncoderStages: .blit)
                encoder.copy(sourceTexture: from, sourceSlice: 0, sourceLevel: 0, sourceOrigin: MTLOrigin(),
                    sourceSize: MTLSize(width: from.width, height: from.height, depth: 1),
                    destinationTexture: to, destinationSlice: 0, destinationLevel: 0, destinationOrigin: MTLOrigin())
                encoder.updateFence(fence, afterEncoderStages: .blit)
                encoder.endEncoding()
            }
            if let input = fx.input { try copy(source, input) }
            fx.scaler.colorTexture = fx.input ?? source
            fx.scaler.outputTexture = fx.output ?? destination
            fx.scaler.inputContentWidth = source.width; fx.scaler.inputContentHeight = source.height
            // MetalFX waits and updates this fence even when no copy encoder is needed.
            fx.scaler.fence = fence
            fx.scaler.encode(commandBuffer: command.metal)
            command.hold(fx); command.hold(source as AnyObject); command.hold(destination as AnyObject)
            if let input = fx.input { command.hold(input as AnyObject) }
            if let output = fx.output { command.hold(output as AnyObject) }
            command.hold(fence as AnyObject)
            fx.scaler.colorTexture = nil; fx.scaler.outputTexture = nil; fx.scaler.fence = nil
            if let output = fx.output { try copy(output, destination) }
            return 1
        } catch {
            ShaderCompilation.writeError(error.localizedDescription, to: errorOutput, capacity: capacity)
            return 0
        }
    }
}
