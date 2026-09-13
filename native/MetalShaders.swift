import Foundation
import Metal
import CryptoKit

enum ShaderCompilation {
    static func options() -> MTLCompileOptions {
        let options = MTLCompileOptions()
        options.languageVersion = .version4_1
        // Preserve the existing compiler's math/optimization defaults.
        return options
    }

    // No Swift error or borrowed string crosses the C ABI. Always terminate when capacity > 0.
    static func writeError(_ message: String, to output: UnsafeMutablePointer<CChar>?, capacity: UInt32) {
        guard let output, capacity > 0 else { return }
        var offset = 0
        for scalar in message.unicodeScalars {
            let bytes = Array(String(scalar).utf8)
            if bytes.count > Int(capacity) - 1 - offset { break }
            for byte in bytes {
                output[offset] = CChar(bitPattern: byte)
                offset += 1
            }
        }
        output[offset] = 0
    }
}

// Keep the source library alive independently of the reloadable library cache.
final class NativeShaderFunction {
    let function: any MTLFunction
    let library: any MTLLibrary
    let cacheKey: String
    init(function: any MTLFunction, library: any MTLLibrary, source: String) {
        self.function = function; self.library = library
        self.cacheKey = SHA256.hash(data: Data(source.utf8)).description + ":" + function.name
    }
    func descriptor() -> MTL4LibraryFunctionDescriptor {
        let descriptor = MTL4LibraryFunctionDescriptor()
        descriptor.name = function.name
        descriptor.library = library
        return descriptor
    }
}

@c(metallum_function_create)
public func metallumFunctionCreate(_ handle: UnsafeMutableRawPointer?, _ source: UnsafePointer<CChar>?,
                                   _ entry: UnsafePointer<CChar>?, _ errorOutput: UnsafeMutablePointer<CChar>?,
                                   _ errorCapacity: UInt32) -> UInt64 {
    ShaderCompilation.writeError("", to: errorOutput, capacity: errorCapacity)
    guard let handle, let source, let entry,
          let msl = String(validatingCString: source), let name = String(validatingCString: entry),
          !msl.isEmpty, !name.isEmpty else {
        ShaderCompilation.writeError("Invalid shader context, source, or entry point", to: errorOutput, capacity: errorCapacity)
        return 0
    }
    return autoreleasepool {
        let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
        do {
            let library: any MTLLibrary
            if let cached = context.shaderLibraries[msl] {
                library = cached
            } else {
                library = try context.device.makeLibrary(source: msl, options: ShaderCompilation.options())
                context.shaderLibraries[msl] = library
            }
            guard let function = library.makeFunction(name: name) else {
                ShaderCompilation.writeError("MSL entry point not found: \(name)", to: errorOutput, capacity: errorCapacity)
                return 0
            }
            let id = context.storeResource(NativeShaderFunction(function: function, library: library, source: msl))
            if id == 0 { ShaderCompilation.writeError("Native resource IDs exhausted", to: errorOutput, capacity: errorCapacity) }
            return id
        } catch {
            ShaderCompilation.writeError(error.localizedDescription, to: errorOutput, capacity: errorCapacity)
            return 0
        }
    }
}

@c(metallum_shader_libraries_clear)
public func metallumShaderLibrariesClear(_ handle: UnsafeMutableRawPointer?) {
    guard let handle else { return }
    let context = Unmanaged<DeviceContext>.fromOpaque(handle).takeUnretainedValue()
    context.shaderLibraries.removeAll()
}
