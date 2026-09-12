import Foundation
import Metal

enum PipelineDescriptionError: LocalizedError {
    case invalid(String)
    var errorDescription: String? { switch self { case let .invalid(message): return message } }
}

enum PipelineDescriptors {
    // ABI: 11 header words, followed by attributes and layouts (4 words per entry).
    static func make(_ words: [UInt64]) throws -> MTL4RenderPipelineDescriptor {
        guard words.count >= 11, words[9] <= 31, words[10] <= 31,
              words.count == 11 + 4 * Int(words[9] + words[10]), words[1] <= 15, words[2] <= 1,
              let color = MTLPixelFormat(rawValue: UInt(words[0])) else {
            throw PipelineDescriptionError.invalid("Invalid pipeline header or entry counts")
        }
        let descriptor = MTL4RenderPipelineDescriptor()
        let attachment = descriptor.colorAttachments[0]!
        attachment.pixelFormat = color
        attachment.writeMask = MTLColorWriteMask(rawValue: UInt(words[1]))
        attachment.blendingState = words[2] == 1 ? .enabled : .disabled
        // Metal 4 derives depth/stencil formats from the render pass attachments.
        if words[2] == 1 {
            guard let srcRGB = MTLBlendFactor(rawValue: UInt(words[3])), let dstRGB = MTLBlendFactor(rawValue: UInt(words[4])),
                  let opRGB = MTLBlendOperation(rawValue: UInt(words[5])), let srcAlpha = MTLBlendFactor(rawValue: UInt(words[6])),
                  let dstAlpha = MTLBlendFactor(rawValue: UInt(words[7])), let opAlpha = MTLBlendOperation(rawValue: UInt(words[8])) else {
                throw PipelineDescriptionError.invalid("Invalid pipeline blend state")
            }
            attachment.sourceRGBBlendFactor = srcRGB
            attachment.destinationRGBBlendFactor = dstRGB
            attachment.rgbBlendOperation = opRGB
            attachment.sourceAlphaBlendFactor = srcAlpha
            attachment.destinationAlphaBlendFactor = dstAlpha
            attachment.alphaBlendOperation = opAlpha
        }
        if words[9] != 0 || words[10] != 0 {
            let vertex = MTLVertexDescriptor()
            var indices = Set<UInt64>()
            var usedBuffers = Set<UInt64>()
            for i in 0..<Int(words[9]) {
                let offset = 11 + i * 4
                let index = words[offset], buffer = words[offset + 3]
                guard index < 31, buffer < 31, words[offset + 2] <= UInt64(Int.max),
                      indices.insert(index).inserted, let format = MTLVertexFormat(rawValue: UInt(words[offset + 1])), format != .invalid else {
                    throw PipelineDescriptionError.invalid("Invalid or duplicate vertex attribute")
                }
                usedBuffers.insert(buffer)
                vertex.attributes[Int(index)].format = format
                vertex.attributes[Int(index)].offset = Int(words[offset + 2])
                vertex.attributes[Int(index)].bufferIndex = Int(buffer)
            }
            var layouts = Set<UInt64>()
            for i in 0..<Int(words[10]) {
                let offset = 11 + Int(words[9]) * 4 + i * 4
                let buffer = words[offset]
                guard buffer < 31, layouts.insert(buffer).inserted, words[offset + 1] <= UInt64(Int.max),
                      words[offset + 3] > 0, words[offset + 3] <= UInt64(Int.max),
                      let step = MTLVertexStepFunction(rawValue: UInt(words[offset + 2])), step == .perVertex || step == .perInstance else {
                    throw PipelineDescriptionError.invalid("Invalid or duplicate vertex buffer layout")
                }
                vertex.layouts[Int(buffer)].stride = Int(words[offset + 1])
                vertex.layouts[Int(buffer)].stepFunction = step
                vertex.layouts[Int(buffer)].stepRate = Int(words[offset + 3])
            }
            guard usedBuffers.isSubset(of: layouts) else { throw PipelineDescriptionError.invalid("Missing vertex buffer layout") }
            descriptor.vertexDescriptor = vertex
        }
        return descriptor
    }
}

@c(metallum_pipeline_create)
public func metallumPipelineCreate(_ handle: UnsafeMutableRawPointer?, _ vertexID: UInt64, _ fragmentID: UInt64,
                                  _ words: UnsafePointer<UInt64>?, _ count: UInt32,
                                  _ errorOutput: UnsafeMutablePointer<CChar>?, _ errorCapacity: UInt32) -> UInt64 {
    ShaderCompilation.writeError("", to: errorOutput, capacity: errorCapacity)
    guard let handle, let words, count >= 11, count <= 259 else {
        ShaderCompilation.writeError("Invalid pipeline context or description", to: errorOutput, capacity: errorCapacity)
        return 0
    }
    return autoreleasepool {
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        do {
            guard let vertex = context.resources[vertexID] as? NativeShaderFunction, vertex.function.functionType == .vertex,
                  let fragment = context.resources[fragmentID] as? NativeShaderFunction, fragment.function.functionType == .fragment else {
                throw PipelineDescriptionError.invalid("Pipeline requires vertex and fragment function IDs")
            }
            let descriptor = try PipelineDescriptors.make(Array(UnsafeBufferPointer(start: words, count: Int(count))))
            descriptor.vertexFunctionDescriptor = vertex.descriptor()
            descriptor.fragmentFunctionDescriptor = fragment.descriptor()
            guard let compiler = context.compiler else { throw PipelineDescriptionError.invalid("Cannot create Metal 4 compiler") }
            let pipeline = try compiler.makeRenderPipelineState(descriptor: descriptor, compilerTaskOptions: nil)
            let id = context.storeResource(pipeline as AnyObject)
            if id == 0 { throw PipelineDescriptionError.invalid("Native resource IDs exhausted") }
            return id
        } catch {
            ShaderCompilation.writeError(error.localizedDescription, to: errorOutput, capacity: errorCapacity)
            return 0
        }
    }
}
