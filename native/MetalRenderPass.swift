import Foundation
import Metal

// Owns the encoder until Java ends the pass by releasing its resource ID.
// Keeping the command alive also makes exceptional cleanup safe.
final class NativeRenderPass {
    let command: any MTLCommandBuffer
    let encoder: any MTLRenderCommandEncoder
    init(command: any MTLCommandBuffer, encoder: any MTLRenderCommandEncoder) {
        self.command = command
        self.encoder = encoder
    }
    deinit { encoder.endEncoding() }
}

struct RenderPassPolicy {
    static func descriptor(colorLoad: UInt32, depthLoad: UInt32, clear: [Double]) -> MTLRenderPassDescriptor? {
        guard colorLoad <= 2, depthLoad <= 2, clear.count == 5,
              let colorAction = MTLLoadAction(rawValue: UInt(colorLoad)),
              let depthAction = MTLLoadAction(rawValue: UInt(depthLoad)) else { return nil }
        let descriptor = MTLRenderPassDescriptor()
        descriptor.colorAttachments[0].loadAction = colorAction
        descriptor.colorAttachments[0].storeAction = .store
        descriptor.colorAttachments[0].clearColor = MTLClearColor(red: clear[0], green: clear[1], blue: clear[2], alpha: clear[3])
        descriptor.depthAttachment.loadAction = depthAction
        descriptor.depthAttachment.storeAction = .store
        descriptor.depthAttachment.clearDepth = clear[4]
        descriptor.stencilAttachment.loadAction = .dontCare
        descriptor.stencilAttachment.storeAction = .dontCare
        return descriptor
    }
}

@c(metallum_render_pass_create)
public func metallumRenderPassCreate(_ handle: UnsafeMutableRawPointer?, _ commandID: UInt64,
                                    _ color: UnsafeMutableRawPointer?, _ depth: UnsafeMutableRawPointer?,
                                    _ colorLoad: UInt32, _ depthLoad: UInt32, _ clear: UnsafePointer<Double>?) -> UInt64 {
    autoreleasepool {
        guard let handle, let clear, color != nil || depth != nil,
              let descriptor = RenderPassPolicy.descriptor(colorLoad: colorLoad, depthLoad: depthLoad,
                  clear: Array(UnsafeBufferPointer(start: clear, count: 5))) else { return 0 }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        guard let command = context.resources[commandID] as? any MTLCommandBuffer,
              command.status == .notEnqueued || command.status == .enqueued else { return 0 }
        if let color {
            guard let texture = Unmanaged<AnyObject>.fromOpaque(color).takeUnretainedValue() as? any MTLTexture else { return 0 }
            descriptor.colorAttachments[0].texture = texture
        }
        if let depth {
            guard let texture = Unmanaged<AnyObject>.fromOpaque(depth).takeUnretainedValue() as? any MTLTexture else { return 0 }
            descriptor.depthAttachment.texture = texture
            if texture.pixelFormat == .depth32Float_stencil8 {
                descriptor.stencilAttachment.texture = texture
            }
        }
        guard let encoder = command.makeRenderCommandEncoder(descriptor: descriptor) else { return 0 }
        return context.storeResource(NativeRenderPass(command: command, encoder: encoder))
    }
}
