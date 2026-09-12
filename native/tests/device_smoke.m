#import <Metal/Metal.h>
#include "metallum.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

_Static_assert(sizeof(MTLDrawPrimitivesIndirectArguments) == 16, "Metal draw stride changed");
_Static_assert(sizeof(MTLDrawIndexedPrimitivesIndirectArguments) == 20, "Metal indexed draw stride changed");

int main(void) {
    @autoreleasepool {
        assert(metallum_abi_version() == 11);
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
            const char *render_msl = "#include <metal_stdlib>\nusing namespace metal; vertex float4 vs(uint id [[vertex_id]]) { return float4(0,0,0,1); } fragment float4 fs() { return float4(1); }";
            uint64_t vs = metallum_function_create(context, render_msl, "vs", shader_error, sizeof(shader_error));
            uint64_t fs = metallum_function_create(context, render_msl, "fs", shader_error, sizeof(shader_error));
            uint64_t description[] = {70, 0, 0, 15, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            uint64_t pipeline = metallum_pipeline_create(context, vs, fs, description, 13, shader_error, sizeof(shader_error));
            assert(pipeline && shader_error[0] == 0);
            assert(metallum_pipeline_create(context, fs, vs, description, 13, shader_error, sizeof(shader_error)) == 0);
            assert(shader_error[0] != 0);
            metallum_resource_destroy(context, vs); metallum_resource_destroy(context, fs);
            metallum_shader_libraries_clear(context);
            id<MTLRenderPipelineState> state = (__bridge id<MTLRenderPipelineState>)metallum_resource_borrow_mtl(context, pipeline);
            assert(state && state.device == device);
            state = nil;
            metallum_resource_destroy(context, pipeline);
            uint64_t depth = metallum_depth_state_create(context, MTLCompareFunctionLessEqual, 1);
            assert(depth && metallum_resource_borrow_mtl(context, depth));
            assert(metallum_depth_state_create(context, 8, 0) == 0);
            uint64_t present_sampler = metallum_present_sampler_create(context, 1);
            assert(present_sampler && metallum_resource_borrow_mtl(context, present_sampler));
            uint64_t texel_buffer = metallum_buffer_create(context, 1024, 1);
            uint64_t texel = metallum_buffer_texture_create(context, texel_buffer, MTLPixelFormatRGBA8Unorm, 0, 16, 64);
            assert(texel);
            assert(metallum_buffer_texture_create(context, texel_buffer, MTLPixelFormatRGBA8Unorm, 1020, 16, 64) == 0);
            metallum_buffer_destroy(context, texel_buffer);
            id<MTLTexture> texel_view = (__bridge id<MTLTexture>)metallum_resource_borrow_mtl(context, texel);
            assert(texel_view.textureType == MTLTextureTypeTextureBuffer && texel_view.width == 16);
            texel_view = nil;
            metallum_resource_destroy(context, texel);
            metallum_resource_destroy(context, depth);
            metallum_resource_destroy(context, present_sampler);
            uint64_t render_texture = metallum_texture_create(context, MTLPixelFormatRGBA8Unorm, 4, 4, 1, 1, 0, 1, NULL);
            uint64_t render_command = metallum_command_buffer_create(context, "Render clear lifecycle");
            double clear_values[] = {1, 0, 0, 1, 1};
            uint64_t pass = metallum_render_pass_create(context, render_command, metallum_resource_borrow_mtl(context, render_texture), NULL, 2, 0, clear_values);
            assert(pass && metallum_resource_borrow_mtl(context, pass));
            metallum_resource_destroy(context, pass); // Must end the encoder before commit.
            metallum_resource_destroy(context, pass);
            uint64_t render_submission = metallum_submit(context, render_command);
            assert(render_submission && metallum_submission_wait(context, render_submission, 5000, shader_error, sizeof(shader_error)) == 1);
            metallum_resource_destroy(context, render_submission);
            metallum_resource_destroy(context, render_command);
            metallum_resource_destroy(context, render_texture);
            uint64_t command = metallum_command_buffer_create(context, "Native command");
            uint64_t submission = metallum_submit(context, command);
            assert(submission);
            assert(metallum_submission_wait(context, submission, 5000, shader_error, sizeof(shader_error)) == 1);
            assert(metallum_submission_wait(context, submission, 0, shader_error, sizeof(shader_error)) == 1);
            metallum_resource_destroy(context, submission);
            metallum_resource_destroy(context, command);
            command = metallum_command_buffer_create(context, NULL);
            submission = metallum_submit(context, command);
            assert(submission);
            metallum_resource_destroy(context, submission); // Close safely joins without an explicit wait.
            metallum_resource_destroy(context, command);
            uint64_t copy_src = metallum_buffer_create(context, 256, 1), copy_dst = metallum_buffer_create(context, 256, 1);
            uint8_t *pattern = metallum_buffer_contents(context, copy_src);
            for (int byte = 0; byte < 64; byte++) pattern[byte] = (uint8_t)byte;
            uint64_t tex_a = metallum_texture_create(context, 70, 4, 4, 1, 1, 0, 0, NULL);
            uint64_t tex_b = metallum_texture_create(context, 70, 4, 4, 1, 1, 0, 0, NULL);
            uint64_t copy_command = metallum_command_buffer_create(context, "Copy round trip");
            uint64_t copy_fence = metallum_fence_create(context);
            uint64_t upload[] = {1, copy_src, tex_a, 0,0,0,0,4,4,0,0,0,0,16,64,0};
            uint64_t texture_copy[] = {3, tex_a, tex_b, 0,0,0,0,4,4,0,0,0,0,0,0,0};
            uint64_t readback[] = {2, tex_b, copy_dst, 0,0,0,0,4,4,0,0,0,0,16,64,0};
            uint64_t buffer_copy[] = {0, copy_dst, copy_src, 0,0,0,0,0,0,64,0,0,0,0,0,64};
            assert(metallum_copy_pass(context, copy_command, copy_fence, upload, 16, shader_error, sizeof(shader_error)) == 1);
            assert(metallum_copy_pass(context, copy_command, copy_fence, texture_copy, 16, shader_error, sizeof(shader_error)) == 1);
            assert(metallum_copy_pass(context, copy_command, copy_fence, readback, 16, shader_error, sizeof(shader_error)) == 1);
            assert(metallum_copy_pass(context, copy_command, copy_fence, buffer_copy, 16, shader_error, sizeof(shader_error)) == 1);
            uint64_t copy_submit = metallum_submit(context, copy_command);
            assert(copy_submit && metallum_submission_wait(context, copy_submit, 5000, shader_error, sizeof(shader_error)) == 1);
            assert(memcmp(pattern, pattern + 64, 64) == 0);
            metallum_resource_destroy(context, copy_submit); metallum_resource_destroy(context, copy_command);
            metallum_resource_destroy(context, copy_fence); metallum_resource_destroy(context, tex_a); metallum_resource_destroy(context, tex_b);
            metallum_buffer_destroy(context, copy_src); metallum_buffer_destroy(context, copy_dst);
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
