import Foundation
import Metal

@c(metallum_render_command)
public func metallumRenderCommand(_ handle: UnsafeMutableRawPointer?, _ passID: UInt64, _ op: UInt32,
                                 _ p0: UInt64, _ p1: UInt64,
                                 _ words: UnsafePointer<Int64>?) -> Int32 {
    autoreleasepool {
        guard let handle, let w = words,
              let pass = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue().resources[passID] as? NativeRenderPass else { return 0 }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        let e = pass.encoder
        func resource<T>(_ id: UInt64, as type: T.Type) -> T? { context.resources[id] as? T }
        func lookupBuffer(_ id: UInt64) -> (any MTLBuffer)? { context.buffers[id] }
        func n(_ i: Int) -> Int { Int(w[i]) }
        func d(_ i: Int) -> Double { Double(bitPattern: UInt64(bitPattern: w[i])) }
        func positive(_ indices: Int...) -> Bool { indices.allSatisfy { w[$0] >= 0 } }
        switch op {
        case 0:
            guard let pipeline = resource(p0, as: (any MTLRenderPipelineState).self) else { return 0 }
            e.setRenderPipelineState(pipeline)
        case 1:
            let state = resource(p0, as: (any MTLDepthStencilState).self)
            guard p0 == 0 || state != nil else { return 0 }
            e.setDepthStencilState(state)
        case 2: e.setDepthBias(Float(d(0)), slopeScale: Float(d(1)), clamp: Float(d(2)))
        case 3:
            guard w[0] >= 0, let value = MTLWinding(rawValue: UInt(w[0])) else { return 0 }
            e.setFrontFacing(value)
        case 4:
            guard w[0] >= 0, let value = MTLCullMode(rawValue: UInt(w[0])) else { return 0 }
            e.setCullMode(value)
        case 5:
            guard w[0] >= 0, let value = MTLTriangleFillMode(rawValue: UInt(w[0])) else { return 0 }
            e.setTriangleFillMode(value)
        case 6, 7:
            guard positive(0, 1), n(1) < 31 else { return 0 }
            let buffer = lookupBuffer(p0)
            guard p0 == 0 || buffer != nil, n(0) <= (buffer?.length ?? 0) else { return 0 }
            let table = op == 6 ? pass.command.vertex : pass.command.fragment
            if op == 6 { pass.vertexBuffers[n(1)] = buffer } else { pass.fragmentBuffers[n(1)] = buffer }
            table.setAddress(buffer.map { $0.gpuAddress + UInt64(n(0)) } ?? 0, index: n(1))
        case 8, 9:
            guard positive(0, 1), n(1) < 31,
                  let buffer = (op == 8 ? pass.vertexBuffers : pass.fragmentBuffers)[n(1)], n(0) <= buffer.length else { return 0 }
            let table = op == 8 ? pass.command.vertex : pass.command.fragment
            table.setAddress(buffer.gpuAddress + UInt64(n(0)), index: n(1))
        case 10, 11:
            guard positive(0), n(0) < 128 else { return 0 }
            let texture = resource(p0, as: (any MTLTexture).self)
            guard p0 == 0 || texture != nil else { return 0 }
            let table = op == 10 ? pass.command.vertex : pass.command.fragment
            table.setTexture(texture?.gpuResourceID ?? MTLResourceID(), index: n(0))
        case 12, 13:
            guard positive(0), n(0) < 16 else { return 0 }
            let sampler = resource(p0, as: (any MTLSamplerState).self)
            guard p0 == 0 || sampler != nil else { return 0 }
            let table = op == 12 ? pass.command.vertex : pass.command.fragment
            table.setSamplerState(sampler?.gpuResourceID ?? MTLResourceID(), index: n(0))
        case 14:
            guard positive(0, 1, 2, 3) else { return 0 }
            e.setScissorRect(MTLScissorRect(x: n(0), y: n(1), width: n(2), height: n(3)))
        case 15:
            e.setViewport(MTLViewport(originX: d(0), originY: d(1), width: d(2), height: d(3), znear: d(4), zfar: d(5)))
        case 16: return 0 // Inline data has a separate, pointer-only entry point.
        case 17:
            guard positive(0, 1, 2, 3, 4), let primitive = MTLPrimitiveType(rawValue: UInt(w[0])) else { return 0 }
            e.drawPrimitives(primitiveType: primitive, vertexStart: n(1), vertexCount: n(2), instanceCount: n(3), baseInstance: n(4))
        case 18:
            guard positive(0, 1, 2, 3, 4, 6), let primitive = MTLPrimitiveType(rawValue: UInt(w[0])),
                  let indexType = MTLIndexType(rawValue: UInt(w[2])), let buffer = lookupBuffer(p0), n(3) <= buffer.length,
                  n(1) <= (buffer.length - n(3)) / (indexType == .uint16 ? 2 : 4) else { return 0 }
            e.drawIndexedPrimitives(primitiveType: primitive, indexCount: n(1), indexType: indexType, indexBuffer: buffer.gpuAddress + UInt64(n(3)),
                                    indexBufferLength: buffer.length - n(3), instanceCount: n(4), baseVertex: n(5), baseInstance: n(6))
        case 19:
            guard positive(0, 1, 2, 3), let primitive = MTLPrimitiveType(rawValue: UInt(w[0])),
                  let indexType = MTLIndexType(rawValue: UInt(w[1])), let buffer = lookupBuffer(p0),
                  let indirect = lookupBuffer(p1),
                  DrawBatchPolicy.indirectRange(offset: w[2], count: w[3], stride: 20, length: indirect.length) else { return 0 }
            for i in 0..<n(3) {
                e.drawIndexedPrimitives(primitiveType: primitive, indexType: indexType, indexBuffer: buffer.gpuAddress, indexBufferLength: buffer.length,
                                        indirectBuffer: indirect.gpuAddress + UInt64(n(2) + i * 20))
            }
        case 20:
            guard positive(0, 1, 2), let primitive = MTLPrimitiveType(rawValue: UInt(w[0])),
                  let indirect = lookupBuffer(p0),
                  DrawBatchPolicy.indirectRange(offset: w[1], count: w[2], stride: 16, length: indirect.length) else { return 0 }
            for i in 0..<n(2) {
                e.drawPrimitives(primitiveType: primitive, indirectBuffer: indirect.gpuAddress + UInt64(n(1) + i * 16))
            }
        case 21, 22:
            guard positive(0), let fence = resource(p0, as: (any MTLFence).self) else { return 0 }
            guard w[0] > 0, w[0] <= 3 else { return 0 }
            var stages: MTLStages = []
            if w[0] & 1 != 0 { stages.insert(.vertex) }
            if w[0] & 2 != 0 { stages.insert(.fragment) }
            if op == 21 { e.updateFence(fence, afterEncoderStages: stages) }
            else { e.waitForFence(fence, beforeEncoderStages: stages) }
        default: return 0
        }
        if [6, 7, 18, 19, 20].contains(op) { pass.command.hold(context.buffers[p0] as AnyObject?) }
        else { pass.command.hold(context.resources[p0]) }
        if op == 19 { pass.command.hold(context.buffers[p1] as AnyObject?) }
        return 1
    }
}

@c(metallum_render_bytes)
public func metallumRenderBytes(_ handle: UnsafeMutableRawPointer?, _ id: UInt64,
                               _ bytes: UnsafeRawPointer?, _ length: UInt64, _ index: UInt64) -> Int32 {
    autoreleasepool {
        guard let handle, let bytes, length <= 4096, index < 31,
              let pass = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue().resources[id] as? NativeRenderPass else { return 0 }
        guard let address = pass.command.inlineBytes(bytes, length: Int(length)) else { return 0 }
        pass.vertexBuffers[Int(index)] = nil
        pass.command.vertex.setAddress(address, index: Int(index))
        return 1
    }
}

// Validation uses division to avoid overflow on untrusted counts/offsets.
enum DrawBatchPolicy {
    static func indirectRange(offset: Int64, count: Int64, stride: Int, length: Int) -> Bool {
        offset >= 0 && offset <= length && offset % 4 == 0 && count >= 0 &&
            count <= (length - Int(offset)) / stride
    }
    static func indexRange(offset: Int64, count: Int32, indexBytes: Int, length: Int) -> Bool {
        offset >= 0 && offset <= length && offset % Int64(indexBytes) == 0 && count >= 0 &&
            Int(count) <= (length - Int(offset)) / indexBytes
    }
}

@c(metallum_render_indexed_batch)
public func metallumRenderIndexedBatch(_ handle: UnsafeMutableRawPointer?, _ passID: UInt64,
                                      _ indexID: UInt64, _ words: UnsafePointer<Int64>?,
                                      _ records: UnsafeRawPointer?, _ count: UInt32) -> Int32 {
    autoreleasepool {
        guard let handle, let w = words, let records, count <= 256,
              w[0] >= 0, w[1] >= 0, w[2] >= 0, w[3] >= 0,
              let primitive = MTLPrimitiveType(rawValue: UInt(w[0])),
              let indexType = MTLIndexType(rawValue: UInt(w[1])) else { return 0 }
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        guard let pass = context.resources[passID] as? NativeRenderPass,
              let indices = context.buffers[indexID] else { return 0 }
        let indexBytes = indexType == .uint16 ? 2 : 4
        // Validate the whole chunk before emitting any of its draws.
        for i in 0..<Int(count) {
            let start = i * 16
            guard DrawBatchPolicy.indexRange(offset: records.load(fromByteOffset: start, as: Int64.self),
                count: records.load(fromByteOffset: start + 8, as: Int32.self),
                indexBytes: indexBytes, length: indices.length) else { return 0 }
        }
        for i in 0..<Int(count) {
            let start = i * 16
            let offset = Int(records.load(fromByteOffset: start, as: Int64.self))
            let indexCount = Int(records.load(fromByteOffset: start + 8, as: Int32.self))
            if indexCount == 0 { continue }
            pass.encoder.drawIndexedPrimitives(primitiveType: primitive, indexCount: indexCount, indexType: indexType,
                indexBuffer: indices.gpuAddress + UInt64(offset), indexBufferLength: indices.length - offset,
                instanceCount: Int(w[2]), baseVertex: Int(records.load(fromByteOffset: start + 12, as: Int32.self)),
                baseInstance: Int(w[3]))
        }
        pass.command.hold(indices as AnyObject)
        return 1
    }
}
