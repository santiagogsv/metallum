import Foundation
import Metal

@main
struct ResourceDescriptorSmoke {
    static func main() {
        let shapes: [(UInt32, UInt32, MTLTextureType, Int)] = [
            (1, 0, .type2D, 1), (3, 0, .type2DArray, 3), (6, 1, .typeCube, 1), (12, 1, .typeCubeArray, 2)
        ]
        for (layers, cube, type, arrayLength) in shapes {
            guard let descriptor = ResourceDescriptors.texture(pixelFormat: 70, width: 8, height: 8,
                    layers: layers, mipLevels: 4, cube: cube, renderTarget: 1) else { fatalError("Descriptor rejected") }
            precondition(descriptor.textureType == type && descriptor.arrayLength == arrayLength)
            precondition(descriptor.width == 8 && descriptor.height == 8 && descriptor.mipmapLevelCount == 4)
            precondition(descriptor.pixelFormat == .rgba8Unorm && descriptor.storageMode == .private)
            precondition(descriptor.hazardTrackingMode == .untracked && descriptor.usage == [.shaderRead, .renderTarget])
        }
        precondition(ResourceDescriptors.texture(pixelFormat: 70, width: 8, height: 4, layers: 6,
                mipLevels: 1, cube: 1, renderTarget: 0) == nil)
        precondition(ResourceDescriptors.texture(pixelFormat: 70, width: 8, height: 8, layers: 7,
                mipLevels: 1, cube: 1, renderTarget: 0) == nil)
        precondition(ResourceDescriptors.texture(pixelFormat: 70, width: 8, height: 8, layers: 1,
                mipLevels: 5, cube: 0, renderTarget: 0) == nil)
        let sampleOnly = ResourceDescriptors.texture(pixelFormat: 252, width: 8, height: 8, layers: 1,
                mipLevels: 1, cube: 0, renderTarget: 0)!
        precondition(sampleOnly.pixelFormat == .depth32Float && sampleOnly.usage == [.shaderRead])
        for lod in [0.0, 0.25, 8.5, Double.nan, Double.infinity, -Double.infinity] {
            let sampler = ResourceDescriptors.sampler(repeatU: 1, repeatV: 0, linearMin: 1, linearMag: 0,
                    anisotropy: 16, maxLod: lod)!
            precondition(sampler.sAddressMode == .repeat && sampler.tAddressMode == .clampToEdge)
            precondition(sampler.minFilter == .linear && sampler.magFilter == .nearest && sampler.maxAnisotropy == 16)
            precondition(sampler.mipFilter == (lod > 0.25 ? .linear : .nearest))
            let expected: Float = lod.isNaN || lod == .infinity ? .greatestFiniteMagnitude : Float(max(0.25, lod))
            precondition(sampler.lodMinClamp == 0 && sampler.lodMaxClamp == expected)
        }
        precondition(ResourceDescriptors.sampler(repeatU: 0, repeatV: 0, linearMin: 0, linearMag: 0,
                anisotropy: 17, maxLod: 0) == nil)
        precondition(ShaderCompilation.options().languageVersion == .version4_1)
        var bytes = [CChar](repeating: 99, count: 8)
        bytes.withUnsafeMutableBufferPointer { buffer in
            ShaderCompilation.writeError("ééééé", to: buffer.baseAddress, capacity: 8)
            precondition(String(cString: buffer.baseAddress!) == "ééé")
            ShaderCompilation.writeError("long error", to: buffer.baseAddress, capacity: 1)
            precondition(buffer[0] == 0 && buffer[1] != 0)
            buffer[0] = 42
            ShaderCompilation.writeError("ignored", to: buffer.baseAddress, capacity: 0)
            precondition(buffer[0] == 42)
            precondition(metallumFunctionCreate(nil, nil, nil, buffer.baseAddress, 8) == 0)
            precondition(buffer[0] != 0 && buffer[7] == 0)
        }
        let basic: [UInt64] = [70, 252, 0, 15, 0, 0, 0, 0, 0, 0, 0, 0, 0]
        let simple = try! PipelineDescriptors.make(basic)
        precondition(simple.depthAttachmentPixelFormat == .depth32Float)
        if let vertex = simple.vertexDescriptor { precondition(vertex.attributes[0].format == .invalid) }
        precondition(simple.colorAttachments[0].pixelFormat == .rgba8Unorm)
        var packed = basic
        packed[4] = 1; packed[5] = 4; packed[6] = 5; packed[8] = 1; packed[9] = 5
        packed[11] = 1; packed[12] = 1
        packed += [0, UInt64(MTLVertexFormat.float3.rawValue), 4, 7, 7, 16, UInt64(MTLVertexStepFunction.perInstance.rawValue), 2]
        let pipeline = try! PipelineDescriptors.make(packed)
        precondition(pipeline.colorAttachments[0].isBlendingEnabled)
        precondition(pipeline.colorAttachments[0].sourceRGBBlendFactor == .sourceAlpha)
        precondition(pipeline.vertexDescriptor!.attributes[0].bufferIndex == 7)
        precondition(pipeline.vertexDescriptor!.attributes[0].offset == 4)
        precondition(pipeline.vertexDescriptor!.layouts[7].stride == 16)
        precondition(pipeline.vertexDescriptor!.layouts[7].stepFunction == .perInstance)
        precondition(pipeline.vertexDescriptor!.layouts[7].stepRate == 2)
        var missingLayout = packed; missingLayout[17] = 8
        var badIndex = packed; badIndex[13] = 31
        var duplicate = packed; duplicate[11] = 2; duplicate.insert(contentsOf: Array(packed[13..<17]), at: 17)
        for invalid in [[], Array(basic.dropLast()), missingLayout, badIndex, duplicate] {
            do { _ = try PipelineDescriptors.make(invalid); fatalError("Invalid pipeline accepted") }
            catch { }
        }
        print("Swift Metal descriptor compatibility tests passed (no GPU required)")
    }
}
