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
        print("Swift Metal descriptor compatibility tests passed (no GPU required)")
    }
}
