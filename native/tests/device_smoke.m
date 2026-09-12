#import <Metal/Metal.h>
#include "metallum.h"
#include <assert.h>
#include <stdio.h>

_Static_assert(sizeof(MTLDrawPrimitivesIndirectArguments) == 16, "Metal draw stride changed");
_Static_assert(sizeof(MTLDrawIndexedPrimitivesIndirectArguments) == 20, "Metal indexed draw stride changed");

int main(void) {
    @autoreleasepool {
        assert(metallum_abi_version() == 4);
        assert(metallum_device_borrow_mtl(NULL) == NULL);
        metallum_device_destroy(NULL);
        for (int i = 0; i < 100; ++i) {
            void *context = metallum_device_create();
            if (!context) { fputs("Metal unavailable\n", stderr); return 1; }
            id<MTLDevice> device = (__bridge id<MTLDevice>)metallum_device_borrow_mtl(context);
            assert(device != nil);
            // Exercise the same borrowed-object interoperability used by existing Java wrappers.
            id<MTLBuffer> buffer = [device newBufferWithLength:64 options:MTLResourceStorageModeShared];
            assert(buffer != nil);
            uint64_t shared = metallum_buffer_create(context, 64, 1);
            uint64_t private_buffer = metallum_buffer_create(context, 64, 0);
            assert(shared && private_buffer && shared != private_buffer);
            uint32_t *contents = metallum_buffer_contents(context, shared);
            assert(contents);
            contents[0] = 0x12345678;
            assert(((uint32_t *)metallum_buffer_contents(context, shared))[0] == 0x12345678);
            assert(metallum_buffer_contents(context, private_buffer) == NULL);
            assert(metallum_buffer_create(context, 0, 1) == 0);
            assert(metallum_buffer_create(context, UINT64_MAX, 1) == 0);
            assert(metallum_buffer_create(context, 64, 2) == 0);
            id<MTLBuffer> borrowed = (__bridge id<MTLBuffer>)metallum_buffer_borrow_mtl(context, shared);
            assert(borrowed.length == 64 && borrowed.storageMode == MTLStorageModeShared);
            borrowed = nil;
            metallum_buffer_destroy(context, shared);
            metallum_buffer_destroy(context, shared);
            assert(metallum_buffer_borrow_mtl(context, shared) == NULL);
            assert(metallum_buffer_contents(context, shared) == NULL);
            uint32_t layer_counts[] = {1, 3, 6, 12};
            MTLTextureType types[] = {MTLTextureType2D, MTLTextureType2DArray, MTLTextureTypeCube, MTLTextureTypeCubeArray};
            for (int shape = 0; shape < 4; shape++) {
                uint64_t texture_id = metallum_texture_create(context, MTLPixelFormatRGBA8Unorm, 8, 8, layer_counts[shape], 4,
                                                              shape >= 2, 1, "Native texture test");
                assert(texture_id);
                id<MTLTexture> texture = (__bridge id<MTLTexture>)metallum_resource_borrow_mtl(context, texture_id);
                assert(texture.width == 8 && texture.height == 8 && texture.mipmapLevelCount == 4);
                assert(texture.textureType == types[shape]);
                assert(texture.arrayLength == (shape >= 2 ? layer_counts[shape] / 6 : layer_counts[shape]));
                assert(texture.storageMode == MTLStorageModePrivate && texture.hazardTrackingMode == MTLHazardTrackingModeUntracked);
                assert(texture.usage == (MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget));
                assert([texture.label isEqualToString:@"Native texture test"]);
                uint64_t full_id = metallum_texture_view_create(context, texture_id, 0, 4);
                uint64_t partial_id = metallum_texture_view_create(context, texture_id, 1, 2);
                assert(full_id && partial_id);
                id<MTLTexture> partial = (__bridge id<MTLTexture>)metallum_resource_borrow_mtl(context, partial_id);
                assert(partial.width == 4 && partial.mipmapLevelCount == 2 && partial.textureType == types[shape]);
                assert(metallum_texture_view_create(context, texture_id, 3, 2) == 0);
                assert(metallum_texture_view_create(context, texture_id, UINT32_MAX, 1) == 0);
                texture = nil; partial = nil;
                metallum_resource_destroy(context, texture_id);
                assert(metallum_resource_borrow_mtl(context, texture_id) == NULL);
                id<MTLTexture> surviving = (__bridge id<MTLTexture>)metallum_resource_borrow_mtl(context, full_id);
                assert(surviving.width == 8);
                surviving = nil;
                metallum_resource_destroy(context, full_id);
                metallum_resource_destroy(context, partial_id);
                metallum_resource_destroy(context, partial_id);
            }
            assert(metallum_texture_create(context, MTLPixelFormatRGBA8Unorm, 8, 4, 6, 1, 1, 0, NULL) == 0);
            assert(metallum_texture_create(context, MTLPixelFormatRGBA8Unorm, 8, 8, 1, 5, 0, 0, NULL) == 0);
            assert(metallum_sampler_create(context, 0, 0, 0, 0, 17, 0) == 0);
            uint64_t sampler = metallum_sampler_create(context, 1, 0, 1, 0, 16, 8.5);
            assert(sampler && metallum_resource_borrow_mtl(context, sampler));
            assert(metallum_texture_view_create(context, sampler, 0, 1) == 0);
            char shader_error[4096];
            const char *msl = "#include <metal_stdlib>\nusing namespace metal;\nkernel void first() {}\nkernel void second() {}";
            uint64_t first = metallum_function_create(context, msl, "first", shader_error, sizeof(shader_error));
            assert(first && shader_error[0] == 0);
            uint64_t second = metallum_function_create(context, msl, "second", shader_error, sizeof(shader_error));
            assert(second && shader_error[0] == 0);
            metallum_shader_libraries_clear(context);
            id<MTLFunction> function = (__bridge id<MTLFunction>)metallum_resource_borrow_mtl(context, first);
            assert([function.name isEqualToString:@"first"]);
            function = nil;
            metallum_resource_destroy(context, first);
            metallum_resource_destroy(context, second);
            assert(metallum_function_create(context, msl, "missing", shader_error, sizeof(shader_error)) == 0);
            assert(shader_error[0] != 0);
            assert(metallum_function_create(context, "invalid", "first", shader_error, sizeof(shader_error)) == 0);
            assert(shader_error[0] != 0);
            // Device teardown owns this sampler as well as the private buffer.
            // Leave the private buffer alive to exercise device-owned cleanup.
            if (i == 0) printf("Native device: %s\n", device.name.UTF8String);
            buffer = nil;
            device = nil;
            metallum_device_destroy(context);
        }
    }
    puts("C ABI and Metal layout smoke test passed");
}
