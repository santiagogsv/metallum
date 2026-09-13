import Foundation
import Metal
import QuartzCore

@c(metallum_layer_create)
public func metallumLayerCreate(_ handle: UnsafeMutableRawPointer?, _ scale: Double) -> UInt64 {
    autoreleasepool {
        guard let handle, scale.isFinite, scale > 0 else { return 0 }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        let layer = CAMetalLayer()
        layer.device = context.device
        layer.framebufferOnly = true
        layer.isOpaque = true
        layer.contentsScale = scale
        return context.storeResource(layer)
    }
}

@c(metallum_layer_configure)
public func metallumLayerConfigure(_ handle: UnsafeMutableRawPointer?, _ layerID: UInt64,
                                   _ width: Double, _ height: Double, _ immediate: UInt32) -> Int32 {
    autoreleasepool {
        guard let handle, width.isFinite, height.isFinite, width > 0, height > 0, immediate <= 1,
              let layer = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue().resources[layerID] as? CAMetalLayer else { return 0 }
        layer.pixelFormat = .bgra8Unorm
        layer.drawableSize = CGSize(width: width, height: height)
        layer.allowsNextDrawableTimeout = false
        layer.presentsWithTransaction = false
        layer.displaySyncEnabled = immediate == 0
        return 1
    }
}

// One complete presentation operation. Drawables never cross into Java and are
// retained by Metal for scheduled presentation, with temporary references drained here.
@c(metallum_present)
public func metallumPresent(_ handle: UnsafeMutableRawPointer?, _ commandID: UInt64, _ layerID: UInt64,
                            _ source: UInt64, _ fenceID: UInt64,
                            _ pipeline: UInt64, _ nearest: UInt64,
                            _ linear: UInt64) -> Int32 {
    autoreleasepool {
        guard let handle else { return 0 }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        guard let command = context.resources[commandID] as? NativeCommand, command.canEncode,
              let layer = context.resources[layerID] as? CAMetalLayer,
              let texture = context.resources[source] as? any MTLTexture,
              let state = context.resources[pipeline] as? any MTLRenderPipelineState,
              let nearestState = context.resources[nearest] as? any MTLSamplerState,
              let linearState = context.resources[linear] as? any MTLSamplerState else { return 0 }
        let fence = context.resources[fenceID] as? any MTLFence
        guard fenceID == 0 || fence != nil else { return 0 }
        command.endCopies()
        guard let drawable = layer.nextDrawable() else { return 1 }
        let target = drawable.texture
        let descriptor = MTL4RenderPassDescriptor()
        descriptor.colorAttachments[0].texture = target
        descriptor.colorAttachments[0].loadAction = .dontCare
        descriptor.colorAttachments[0].storeAction = .store
        guard let encoder = command.metal.makeRenderCommandEncoder(descriptor: descriptor) else { return 0 }
        encoder.setViewport(MTLViewport(originX: 0, originY: 0, width: Double(target.width), height: Double(target.height), znear: 0, zfar: 1))
        encoder.setRenderPipelineState(state)
        command.resetBindings()
        encoder.barrier(afterQueueStages: .all, beforeStages: .fragment, visibilityOptions: .device)
        encoder.setArgumentTable(command.vertex.metal, stages: .vertex)
        encoder.setArgumentTable(command.fragment.metal, stages: .fragment)
        command.fragment.setTexture(texture.gpuResourceID, index: 0)
        let scaling = texture.width != target.width || texture.height != target.height
        command.fragment.setSamplerState((scaling ? linearState : nearestState).gpuResourceID, index: 0)
        encoder.drawPrimitives(primitiveType: .triangle, vertexStart: 0, vertexCount: 3, instanceCount: 1, baseInstance: 0)
        if let fence { encoder.updateFence(fence, afterEncoderStages: .fragment) }
        encoder.endEncoding()
        command.drawables.append(drawable)
        for id in [source, fenceID, pipeline, nearest, linear, layerID] { command.hold(context.resources[id]) }
        command.hold(drawable as AnyObject)
        command.hold(target as AnyObject)
        return 1
    }
}
