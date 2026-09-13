import Foundation
import Metal

// Owns the encoder until Java ends the pass by releasing its resource ID.
// Keeping the command alive also makes exceptional cleanup safe.
final class NativeRenderPass {
    let command: NativeCommand
    let encoder: any MTL4RenderCommandEncoder
    let hasColor: Bool
    let hasDepth: Bool
    var colorStoreAction: MTLStoreAction = .store
    var depthStoreAction: MTLStoreAction = .store
    init(command: NativeCommand, encoder: any MTL4RenderCommandEncoder, hasColor: Bool, hasDepth: Bool) {
        self.command = command
        self.encoder = encoder
        self.hasColor = hasColor; self.hasDepth = hasDepth
    }
    deinit {
        if hasColor { encoder.setColorStoreAction(colorStoreAction, index: 0) }
        if hasDepth { encoder.setDepthStoreAction(depthStoreAction) }
        encoder.endEncoding(); command.encoderOpen = false
    }
}

struct RenderPassPolicy {
    static func descriptor(colorLoad: UInt32, depthLoad: UInt32, clear: [Double]) -> MTL4RenderPassDescriptor? {
        guard colorLoad <= 2, depthLoad <= 2, clear.count == 5,
              let colorAction = MTLLoadAction(rawValue: UInt(colorLoad)),
              let depthAction = MTLLoadAction(rawValue: UInt(depthLoad)) else { return nil }
        let descriptor = MTL4RenderPassDescriptor()
        descriptor.colorAttachments[0].loadAction = colorAction
        descriptor.colorAttachments[0].storeAction = .unknown
        descriptor.colorAttachments[0].clearColor = MTLClearColor(red: clear[0], green: clear[1], blue: clear[2], alpha: clear[3])
        descriptor.depthAttachment.loadAction = depthAction
        descriptor.depthAttachment.storeAction = .unknown
        descriptor.depthAttachment.clearDepth = clear[4]
        descriptor.stencilAttachment.loadAction = .dontCare
        descriptor.stencilAttachment.storeAction = .dontCare
        return descriptor
    }
}

@c(metallum_render_pass_create)
public func metallumRenderPassCreate(_ handle: UnsafeMutableRawPointer?, _ commandID: UInt64,
                                    _ color: UInt64, _ depth: UInt64,
                                    _ colorLoad: UInt32, _ depthLoad: UInt32, _ clear: UnsafePointer<Double>?) -> UInt64 {
    autoreleasepool {
        guard let handle, let clear, color != 0 || depth != 0,
              let descriptor = RenderPassPolicy.descriptor(colorLoad: colorLoad, depthLoad: depthLoad,
                  clear: Array(UnsafeBufferPointer(start: clear, count: 5))) else { return 0 }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        guard let command = context.resources[commandID] as? NativeCommand, command.canEncode else { return 0 }
        if color != 0 {
            guard let texture = context.resources[color] as? any MTLTexture else { return 0 }
            descriptor.colorAttachments[0].texture = texture
        }
        if depth != 0 {
            guard let texture = context.resources[depth] as? any MTLTexture else { return 0 }
            descriptor.depthAttachment.texture = texture
            if texture.pixelFormat == .depth32Float_stencil8 {
                descriptor.stencilAttachment.texture = texture
            }
        }
        command.endCopies()
        guard let encoder = command.metal.makeRenderCommandEncoder(descriptor: descriptor) else { return 0 }
        command.resetBindings()
        encoder.setArgumentTable(command.vertex.metal, stages: .vertex)
        encoder.setArgumentTable(command.fragment.metal, stages: .fragment)
        // Preserve ordering and visibility across render/copy passes and submissions.
        encoder.barrier(afterQueueStages: .all, beforeStages: [.vertex, .fragment], visibilityOptions: .device)
        command.encoderOpen = true
        command.hold(context.resources[color]); command.hold(context.resources[depth])
        return context.storeResource(NativeRenderPass(command: command, encoder: encoder, hasColor: color != 0, hasDepth: depth != 0))
    }
}
