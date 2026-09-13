import Foundation
import Metal
import CryptoKit
import Darwin

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
            let payload = Array(UnsafeBufferPointer(start: words, count: Int(count)))
            let descriptor = try PipelineDescriptors.make(payload)
            descriptor.vertexFunctionDescriptor = vertex.descriptor()
            descriptor.fragmentFunctionDescriptor = fragment.descriptor()
            let pipeline = try context.pipelineCache.make(descriptor, key: vertex.cacheKey + "|" + fragment.cacheKey + "|" + payload.description)
            let id = context.storeResource(pipeline as AnyObject)
            if id == 0 { throw PipelineDescriptionError.invalid("Native resource IDs exhausted") }
            return id
        } catch {
            ShaderCompilation.writeError(error.localizedDescription, to: errorOutput, capacity: errorCapacity)
            return 0
        }
    }
}

// One native owner for render compilation and persistent binary reuse. No per-frame I/O.
// Each archive is keyed by both complete shader sources, entry points, descriptor and platform.
final class PipelineCache {
    private let device: any MTLDevice
    private var serializer: (any MTL4PipelineDataSetSerializer)?
    private var compiler: (any MTL4Compiler)?
    private let directory: URL
    private(set) var hits = 0
    private(set) var misses = 0
    init(device: any MTLDevice, cacheRoot: URL? = nil) {
        self.device = device
        let fm = FileManager.default
        let root = cacheRoot ?? ProcessInfo.processInfo.environment["METALLUM_CACHE_DIR"].map { URL(fileURLWithPath: $0) }
            ?? fm.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("com.metallum")
        // Change the schema when compile options/descriptor policy change. OS version includes the build.
        let platform = "metal4-v1-msl41-\(device.registryID)-\(ProcessInfo.processInfo.operatingSystemVersionString)"
        directory = root.appendingPathComponent(Self.digest(platform))
        let capture = MTL4PipelineDataSetSerializerDescriptor()
        capture.configuration = .captureBinaries
        let writable: Bool
        do { try fm.createDirectory(at: directory, withIntermediateDirectories: true); writable = true }
        catch { writable = false }
        serializer = writable ? device.makePipelineDataSetSerializer(descriptor: capture) : nil
        let descriptor = MTL4CompilerDescriptor()
        descriptor.pipelineDataSetSerializer = serializer
        compiler = try? device.makeCompiler(descriptor: descriptor)
        trim()
    }
    private static func digest(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
    func archiveURL(for key: String) -> URL {
        directory.appendingPathComponent(Self.digest(key)).appendingPathExtension("metallib")
    }
    func make(_ descriptor: MTL4RenderPipelineDescriptor, key: String) throws -> any MTLRenderPipelineState {
        let file = archiveURL(for: key)
        if let archive = verifiedArchive(file),
           let pipeline = try? archive.makeRenderPipelineState(descriptor: descriptor) {
            hits += 1
            return pipeline
        }
        misses += 1
        guard let compiler else { throw PipelineDescriptionError.invalid("Cannot create Metal 4 compiler") }
        let pipeline = try compiler.makeRenderPipelineState(descriptor: descriptor, compilerTaskOptions: nil)
        if let serializer {
            let temporary = directory.appendingPathComponent(UUID().uuidString + ".tmp")
            defer { try? FileManager.default.removeItem(at: temporary) }
            do {
                try serializer.serializeAsArchiveAndFlush(url: temporary)
                let bytes = (try temporary.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0
                if bytes <= 16 * 1024 * 1024 {
                    // Atomic replacement also tolerates another game instance compiling this key.
                    let checksum = Data(SHA256.hash(data: try Data(contentsOf: temporary)))
                    try checksum.write(to: file.appendingPathExtension("sha256"), options: .atomic)
                    _ = rename(temporary.path, file.path)
                    trim()
                }
            } catch {
                // Stop capturing if persistence fails, so failed writes cannot accumulate binary data.
                self.serializer = nil
                self.compiler = try? device.makeCompiler(descriptor: MTL4CompilerDescriptor())
            }
        }
        return pipeline
    }
    private func verifiedArchive(_ file: URL) -> (any MTL4Archive)? {
        // The current driver can crash on malformed archives rather than return NSError.
        // Verify our atomic writer's checksum before passing bytes to Metal.
        guard let size = try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              size > 0, size <= 16 * 1024 * 1024,
              let checksum = try? Data(contentsOf: file.appendingPathExtension("sha256")), checksum.count == 32,
              let bytes = try? Data(contentsOf: file), Data(SHA256.hash(data: bytes)) == checksum else { return nil }
        return try? device.makeArchive(url: file)
    }
    private func trim() {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: directory,
                includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey]) else { return }
        let entries = files.filter { $0.pathExtension == "metallib" }.compactMap { url -> (URL, Int, Date)? in
            guard let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey]) else { return nil }
            return (url, values.fileSize ?? 0, values.contentModificationDate ?? .distantPast)
        }.sorted { $0.2 > $1.2 }
        var bytes = 0
        for (index, entry) in entries.enumerated() {
            bytes += entry.1
            if index >= 512 || bytes > 128 * 1024 * 1024 {
                try? fm.removeItem(at: entry.0)
                try? fm.removeItem(at: entry.0.appendingPathExtension("sha256"))
            }
        }
    }
}
