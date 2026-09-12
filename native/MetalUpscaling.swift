import Foundation
import Metal
import MetalFX

final class SpatialUpscaler {
    let scaler: any MTL4FXSpatialScaler
    let input: any MTLTexture
    let output: any MTLTexture
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
        // Dedicated textures satisfy the scaler's queried usage requirements.
        input = try texture(source.width, source.height, source.pixelFormat, scaler.colorTextureUsage)
        output = try texture(destination.width, destination.height, destination.pixelFormat, scaler.outputTextureUsage)
    }
    func matches(_ source: any MTLTexture, _ destination: any MTLTexture) -> Bool {
        input.width == source.width && input.height == source.height && input.pixelFormat == source.pixelFormat &&
        output.width == destination.width && output.height == destination.height && output.pixelFormat == destination.pixelFormat
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
            if context.spatialScaler?.matches(source, destination) != true {
                context.spatialScaler = try SpatialUpscaler(context: context, source: source, destination: destination)
            }
            let fx = context.spatialScaler!
            guard let before = command.metal.makeComputeCommandEncoder() else { return 0 }
            before.barrier(afterQueueStages: .all, beforeStages: .blit, visibilityOptions: .device)
            before.waitForFence(fence, beforeEncoderStages: .blit)
            before.copy(sourceTexture: source, sourceSlice: 0, sourceLevel: 0, sourceOrigin: MTLOrigin(),
                        sourceSize: MTLSize(width: source.width, height: source.height, depth: 1),
                        destinationTexture: fx.input, destinationSlice: 0, destinationLevel: 0, destinationOrigin: MTLOrigin())
            before.updateFence(fence, afterEncoderStages: .blit)
            before.endEncoding()
            fx.scaler.colorTexture = fx.input; fx.scaler.outputTexture = fx.output
            fx.scaler.inputContentWidth = source.width; fx.scaler.inputContentHeight = source.height
            fx.scaler.fence = fence
            fx.scaler.encode(commandBuffer: command.metal)
            // The command owns all inputs until completion, including across resize/reload.
            command.hold(fx); command.hold(source as AnyObject); command.hold(destination as AnyObject)
            command.hold(fx.input as AnyObject); command.hold(fx.output as AnyObject); command.hold(fence as AnyObject)
            fx.scaler.colorTexture = nil; fx.scaler.outputTexture = nil; fx.scaler.fence = nil
            guard let after = command.metal.makeComputeCommandEncoder() else { return 0 }
            after.barrier(afterQueueStages: .all, beforeStages: .blit, visibilityOptions: .device)
            after.waitForFence(fence, beforeEncoderStages: .blit)
            after.copy(sourceTexture: fx.output, sourceSlice: 0, sourceLevel: 0, sourceOrigin: MTLOrigin(),
                       sourceSize: MTLSize(width: destination.width, height: destination.height, depth: 1),
                       destinationTexture: destination, destinationSlice: 0, destinationLevel: 0, destinationOrigin: MTLOrigin())
            after.updateFence(fence, afterEncoderStages: .blit); after.endEncoding()
            return 1
        } catch {
            ShaderCompilation.writeError(error.localizedDescription, to: errorOutput, capacity: capacity)
            return 0
        }
    }
}
