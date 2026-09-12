import Foundation
import Metal

@main
struct ResourceDescriptorSmoke {
    static func main() {
        let limit: UInt64 = 64 * 1024 * 1024
        precondition(CommandStoragePolicy.keep(bytes: limit, reuses: 120))
        precondition(CommandStoragePolicy.keep(bytes: limit + 1, reuses: 119))
        precondition(!CommandStoragePolicy.keep(bytes: limit + 1, reuses: 120))
        precondition(!CommandStoragePolicy.keep(bytes: limit * 20, reuses: 121))
        precondition(metallumUpscale(nil, 0, 0, 0, 0, nil, 0) == 0)
        metallumUpscaleClear(nil)
        precondition(RendererCounters.duration(start: 1, end: 1.25) == 250_000_000)
        for (start, end) in [(0.0, 1.0), (2.0, 1.0), (Double.nan, 2), (1, Double.infinity)] {
            precondition(RendererCounters.duration(start: start, end: end) == nil)
        }
        let counters = RendererCounters()
        counters.record(10); counters.record(nil); counters.record(30)
        precondition(counters.drain() == [3, 2, 40, 30, 0, 0, 0])
        precondition(counters.drain() == Array(repeating: 0, count: 7))
        var bindings = BindingValues(count: 31)
        precondition(!bindings.update(0, index: 30))
        precondition(bindings.update(123, index: 30))
        precondition(!bindings.update(123, index: 30))
        precondition(bindings.update(456, index: 30))
        precondition(bindings.update(0, index: 30))
        precondition(!bindings.update(0, index: 30))
        let timed = SubmissionCompletion()
        timed.finish(start: 1, end: 1.25)
        precondition(timed.wait(milliseconds: 0) && timed.gpuNanoseconds == 250_000_000)
        let gpuFailed = SubmissionCompletion()
        gpuFailed.finish(error: "GPU failure", start: 1, end: 2)
        precondition(gpuFailed.wait(milliseconds: 0) && gpuFailed.gpuNanoseconds == nil)

        // Reproduces the macOS 27 queue descriptor lifetime crash without a GPU.
        // Both successful and throwing creation must relinquish the borrowed queue.
        for throwing in [false, true] {
            for _ in 0..<100 {
                weak var retired: DispatchQueue?
                autoreleasepool {
                    let queue = DispatchQueue(label: "metallum.feedback.lifetime.test")
                    retired = queue
                    autoreleasepool {
                        do {
                            try CommandQueueConfiguration.withDescriptor(feedbackQueue: queue) { descriptor in
                                precondition(descriptor.feedbackQueue != nil)
                                if throwing { throw PipelineDescriptionError.invalid("Test queue creation failure") }
                            }
                            precondition(!throwing)
                        } catch { precondition(throwing) }
                    }
                    // Previously trapped or crashed here after descriptor destruction.
                    queue.sync {}
                }
                precondition(retired == nil, "Feedback queue leaked")
            }
        }
        for load: UInt32 in 0...2 {
            let pass = RenderPassPolicy.descriptor(colorLoad: load, depthLoad: load, clear: [0.1, 0.2, 0.3, 0.4, 0.75])!
            precondition(pass.colorAttachments[0].loadAction.rawValue == UInt(load))
            precondition(pass.depthAttachment.loadAction.rawValue == UInt(load))
            precondition(pass.colorAttachments[0].storeAction == .store && pass.depthAttachment.storeAction == .store)
            precondition(pass.colorAttachments[0].clearColor.alpha == 0.4 && pass.depthAttachment.clearDepth == 0.75)
            precondition(pass.stencilAttachment.loadAction == .dontCare && pass.stencilAttachment.storeAction == .dontCare)
        }
        precondition(RenderPassPolicy.descriptor(colorLoad: 3, depthLoad: 1, clear: [0,0,0,0,1]) == nil)
        precondition(RenderPassPolicy.descriptor(colorLoad: 1, depthLoad: 1, clear: []) == nil)
        precondition(metallumRenderPassCreate(nil, 0, 0, 0, 1, 1, nil) == 0)
        precondition(metallumRenderCommand(nil, 0, 0, 0, 0, nil) == 0)
        precondition(metallumLayerCreate(nil, 1) == 0)
        precondition(metallumLayerConfigure(nil, 0, 8, 8, 0) == 0)
        precondition(metallumPresent(nil, 0, 0, 0, 0, 0, 0, 0) == 0)
        for _ in 0..<1000 {
            let references = CommandReferences()
            weak var weakObject: NSObject?
            do {
                let object = NSObject()
                weakObject = object
                references.hold(object); references.hold(object); references.hold(nil)
                precondition(references.objects.count == 1)
            }
            precondition(weakObject != nil)
            references.clear(); references.clear()
            precondition(weakObject == nil && references.objects.isEmpty)
        }
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
        if let vertex = simple.vertexDescriptor { precondition(vertex.attributes[0].format == .invalid) }
        precondition(simple.colorAttachments[0].pixelFormat == .rgba8Unorm)
        var packed = basic
        packed[4] = 1; packed[5] = 4; packed[6] = 5; packed[8] = 1; packed[9] = 5
        packed[11] = 1; packed[12] = 1
        packed += [0, UInt64(MTLVertexFormat.float3.rawValue), 4, 7, 7, 16, UInt64(MTLVertexStepFunction.perInstance.rawValue), 2]
        let pipeline = try! PipelineDescriptors.make(packed)
        precondition(pipeline.colorAttachments[0].blendingState == .enabled)
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
        for compare in UInt64(0)...7 {
            let depth = ResourceDescriptors.depth(compare: compare, write: 1)!
            precondition(depth.depthCompareFunction.rawValue == compare && depth.isDepthWriteEnabled)
        }
        precondition(ResourceDescriptors.depth(compare: 8, write: 0) == nil)
        precondition(ResourceDescriptors.depth(compare: 0, write: 2) == nil)
        for linear in [false, true] {
            let sampler = ResourceDescriptors.presentSampler(linear: linear)
            precondition(sampler.minFilter == (linear ? .linear : .nearest) && sampler.magFilter == sampler.minFilter)
            precondition(sampler.mipFilter == .notMipmapped && sampler.sAddressMode == .clampToEdge && sampler.tAddressMode == .clampToEdge)
        }
        var cursor = 0
        for length in Array(repeating: [0, 16, 4096, 31, 256], count: 1000).flatMap({ $0 }) {
            let allocation = InlinePlacement.next(cursor: cursor, length: length)!
            precondition(allocation.offset % 256 == 0)
            precondition(allocation.offset + length <= InlinePlacement.chunkSize)
            precondition(allocation.chunk * InlinePlacement.chunkSize + allocation.offset >= cursor)
            cursor = allocation.end
        }
        let crossing = InlinePlacement.next(cursor: 65520, length: 4096)!
        precondition(crossing.chunk == 1 && crossing.offset == 0 && crossing.end == 69632)
        precondition(InlinePlacement.next(cursor: Int.max, length: 16) == nil)
        precondition(InlinePlacement.next(cursor: 0, length: 4097) == nil)
        let failed = SubmissionCompletion()
        DispatchQueue.global().async { failed.finish(error: "GPU failure") }
        precondition(failed.wait(milliseconds: 1000) && failed.error == "GPU failure")
        precondition(failed.wait(milliseconds: 0) && failed.error == "GPU failure")
        let pending = SubmissionCompletion()
        precondition(!pending.wait(milliseconds: 0))
        precondition(!pending.wait(milliseconds: 1))
        DispatchQueue.global().async { pending.finish() }
        precondition(pending.wait(milliseconds: 1000))
        precondition(pending.wait(milliseconds: 0))
        precondition(pending.wait(milliseconds: Int64.max))
        for _ in 0..<1000 {
            weak var released: SubmissionCompletion?
            do {
                let completion = SubmissionCompletion()
                released = completion
                completion.finish()
                precondition(completion.wait(milliseconds: 0))
            }
            precondition(released == nil)
        }
        precondition(!SpatialTexturePolicy.needsCopy([.shaderRead, .renderTarget], .shaderRead, .private))
        precondition(!SpatialTexturePolicy.needsCopy([.shaderRead, .renderTarget], [.shaderRead, .renderTarget], .private))
        precondition(SpatialTexturePolicy.needsCopy(.shaderRead, [.shaderRead, .shaderWrite], .private))
        precondition(SpatialTexturePolicy.needsCopy([.shaderRead, .shaderWrite], .shaderWrite, .shared))

        precondition(DrawBatchPolicy.indexRange(offset: 4, count: 6, indexBytes: 2, length: 16))
        precondition(!DrawBatchPolicy.indexRange(offset: 4, count: 7, indexBytes: 2, length: 16))
        precondition(!DrawBatchPolicy.indexRange(offset: 2, count: 1, indexBytes: 4, length: 16))
        precondition(!DrawBatchPolicy.indexRange(offset: -4, count: 1, indexBytes: 4, length: 16))
        precondition(!DrawBatchPolicy.indexRange(offset: Int64.max, count: Int32.max, indexBytes: 4, length: 16))
        precondition(DrawBatchPolicy.indirectRange(offset: 20, count: 3, stride: 20, length: 80))
        precondition(!DrawBatchPolicy.indirectRange(offset: 20, count: 4, stride: 20, length: 80))
        precondition(!DrawBatchPolicy.indirectRange(offset: 2, count: 1, stride: 16, length: 80))
        precondition(!DrawBatchPolicy.indirectRange(offset: 0, count: Int64.max, stride: 20, length: 80))
        precondition(!DrawBatchPolicy.indirectRange(offset: 0, count: -1, stride: 16, length: 80))
        precondition(DrawBatchPolicy.indirectRange(offset: 80, count: 0, stride: 16, length: 80))

        precondition(CopyDescription.bufferRegion(offset: 4, row: 16, image: 32, width: 3, height: 2, pixelBytes: 4, length: 32))
        precondition(!CopyDescription.bufferRegion(offset: 4, row: 16, image: 32, width: 3, height: 2, pixelBytes: 4, length: 31))
        precondition(!CopyDescription.bufferRegion(offset: 0, row: 8, image: 0, width: 3, height: 1, pixelBytes: 4, length: 64))
        precondition(!CopyDescription.bufferRegion(offset: 1, row: 16, image: 0, width: 3, height: 1, pixelBytes: 4, length: 64))
        precondition(!CopyDescription.bufferRegion(offset: 0, row: Int.max - 3, image: 0, width: 3, height: Int.max, pixelBytes: 4, length: Int.max))
        precondition(CopyDescription.bufferRegion(offset: 0, row: 16, image: 0, width: 3, height: 1, pixelBytes: 4, length: 12))

        let copy = try! CopyDescription([0, 1, 2, 8, 0, 0, 0, 0, 0, 16, 0, 0, 0, 0, 0, 8])
        precondition(copy[3] == 8 && copy[9] == 16 && copy.range(8, 8, 16))
        precondition(!copy.range(8, 9, 16) && !copy.range(17, 0, 16))
        for bad: [UInt64] in [[], Array(repeating: UInt64.max, count: 16), [4] + Array(repeating: 0, count: 15)] {
            do { _ = try CopyDescription(bad); fatalError("Invalid copy accepted") } catch { }
        }
        print("Swift Metal descriptor compatibility tests passed (no GPU required)")
    }
}
