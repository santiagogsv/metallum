#import <Metal/Metal.h>
#include "metallum.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

_Static_assert(sizeof(MTLDrawPrimitivesIndirectArguments) == 16, "Metal draw stride changed");
_Static_assert(sizeof(MTLDrawIndexedPrimitivesIndirectArguments) == 20, "Metal indexed draw stride changed");

int main(void) {
    @autoreleasepool {
        assert(metallum_abi_version() == 18);
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
            const char *render_msl = "#include <metal_stdlib>\nusing namespace metal; struct V { float4 position [[position]]; float4 color; }; vertex V vs(uint id [[vertex_id]], constant float4& color [[buffer(0)]]) { float2 p[3] = {float2(-1,-1),float2(3,-1),float2(-1,3)}; return V{float4(p[id],0,1),color}; } fragment float4 fs(V v [[stage_in]]) { return v.color; }";
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
            // Exercise Swift draw dispatch against the real SDK/GPU when available.
            uint64_t draw_texture = metallum_texture_create(context, MTLPixelFormatRGBA8Unorm, 8, 8, 1, 1, 0, 1, NULL);
            uint64_t draw_command = metallum_command_buffer_create(context, "Swift draw smoke");
            double draw_clear[] = {0, 0, 0, 1, 1};
            uint64_t draw_pass = metallum_render_pass_create(context, draw_command, draw_texture, 0, 2, 0, draw_clear);
            assert(metallum_submit(context, draw_command) == 0); // Cannot submit an open encoder.
            assert(metallum_render_pass_create(context, draw_command, draw_texture, 0, 1, 0, draw_clear) == 0);
            int64_t draw_words[8] = {0};
            assert(metallum_render_command(context, draw_pass, 0, pipeline, 0, draw_words) == 1);
            double viewport_words[8] = {0, 0, 8, 8, 0, 1, 0, 0};
            memcpy(draw_words, viewport_words, sizeof(draw_words));
            assert(metallum_render_command(context, draw_pass, 15, 0, 0, draw_words) == 1);
            int64_t triangle_words[8] = {MTLPrimitiveTypeTriangle, 0, 3, 1, 0, 0, 0, 0};
            // Cross a staging-chunk boundary, then overwrite the caller's bytes.
            float inline_data[1024] = {1, 0, 0, 1};
            for (int bind = 0; bind < 20; ++bind)
                assert(metallum_render_bytes(context, draw_pass, inline_data, sizeof(inline_data), 0) == 1);
            memset(inline_data, 0, sizeof(inline_data));
            uint64_t batch_indices = metallum_buffer_create(context, 8, 1);
            uint16_t index_data[] = {99, 1, 2, 3};
            memcpy(metallum_buffer_contents(context, batch_indices), index_data, sizeof(index_data));
            struct { int64_t offset; int32_t count, vertex; } records[] = {{0, 0, 0}, {2, 3, -1}};
            _Static_assert(sizeof(records[0]) == 16, "Indexed batch layout changed");
            int64_t batch_words[] = {MTLPrimitiveTypeTriangle, MTLIndexTypeUInt16, 1, 0};
            records[1].offset = 4; // Would overrun the index buffer.
            assert(metallum_render_indexed_batch(context, draw_pass, batch_indices, batch_words, records, 2) == 0);
            records[1].offset = 2;
            assert(metallum_render_indexed_batch(context, draw_pass, batch_indices, batch_words, records, 257) == 0);
            assert(metallum_render_indexed_batch(context, draw_pass, batch_indices, batch_words, records, 2) == 1);
            memset(records, 0, sizeof(records)); // Encoding must have consumed CPU scratch already.
            assert(metallum_render_command(context, draw_pass, 99, 0, 0, triangle_words) == 0);
            metallum_resource_destroy(context, draw_pass);
            assert(metallum_render_command(context, draw_pass, 17, 0, 0, triangle_words) == 0);
            // Reusing the same argument tables in another pass must preserve the first draw.
            uint64_t green_texture = metallum_texture_create(context, MTLPixelFormatRGBA8Unorm, 8, 8, 1, 1, 0, 1, NULL);
            uint64_t green_pass = metallum_render_pass_create(context, draw_command, green_texture, 0, 2, 0, draw_clear);
            assert(green_pass);
            assert(metallum_render_command(context, green_pass, 0, pipeline, 0, draw_words) == 1);
            assert(metallum_render_command(context, green_pass, 15, 0, 0, draw_words) == 1);
            float green[] = {0, 1, 0, 1};
            assert(metallum_render_bytes(context, green_pass, green, sizeof(green), 0) == 1);
            uint64_t indexed_arguments = metallum_buffer_create(context, 40, 1);
            MTLDrawIndexedPrimitivesIndirectArguments indexed_draws[] = {{0, 1, 0, 0, 0}, {3, 1, 1, -1, 0}};
            memcpy(metallum_buffer_contents(context, indexed_arguments), indexed_draws, sizeof(indexed_draws));
            int64_t indexed_words[8] = {MTLPrimitiveTypeTriangle, MTLIndexTypeUInt16, 0, 3};
            assert(metallum_render_command(context, green_pass, 19, batch_indices, indexed_arguments, indexed_words) == 0);
            indexed_words[3] = 2;
            assert(metallum_render_command(context, green_pass, 19, batch_indices, indexed_arguments, indexed_words) == 1);
            metallum_buffer_destroy(context, batch_indices);
            metallum_buffer_destroy(context, indexed_arguments);
            metallum_resource_destroy(context, green_pass);
            uint64_t blue_texture = metallum_texture_create(context, MTLPixelFormatRGBA8Unorm, 8, 8, 1, 1, 0, 1, NULL);
            uint64_t blue_pass = metallum_render_pass_create(context, draw_command, blue_texture, 0, 2, 0, draw_clear);
            assert(metallum_render_command(context, blue_pass, 0, pipeline, 0, draw_words) == 1);
            assert(metallum_render_command(context, blue_pass, 15, 0, 0, draw_words) == 1);
            float blue[] = {0, 0, 1, 1};
            assert(metallum_render_bytes(context, blue_pass, blue, sizeof(blue), 0) == 1);
            uint64_t arguments = metallum_buffer_create(context, 32, 1);
            MTLDrawPrimitivesIndirectArguments draws[] = {{0, 1, 0, 0}, {3, 1, 0, 0}};
            memcpy(metallum_buffer_contents(context, arguments), draws, sizeof(draws));
            int64_t indirect_words[8] = {MTLPrimitiveTypeTriangle, 0, 3};
            assert(metallum_render_command(context, blue_pass, 20, arguments, 0, indirect_words) == 0);
            indirect_words[2] = 2;
            assert(metallum_render_command(context, blue_pass, 20, arguments, 0, indirect_words) == 1);
            metallum_resource_destroy(context, blue_pass);
            metallum_buffer_destroy(context, arguments);
            uint64_t blue_pixels = metallum_buffer_create(context, 256, 1);
            uint64_t red_pixels = metallum_buffer_create(context, 256, 1);
            uint64_t green_pixels = metallum_buffer_create(context, 256, 1);
            uint64_t pixel_fence = metallum_fence_create(context);
            uint64_t red_copy[] = {2, draw_texture, red_pixels, 0,0,0,0,8,8,0,0,0,0,32,256,0};
            uint64_t green_copy[] = {2, green_texture, green_pixels, 0,0,0,0,8,8,0,0,0,0,32,256,0};
            assert(metallum_copy_pass(context, draw_command, pixel_fence, red_copy, 16, shader_error, sizeof(shader_error)) == 1);
            assert(metallum_copy_pass(context, draw_command, pixel_fence, green_copy, 16, shader_error, sizeof(shader_error)) == 1);
            uint64_t blue_copy[] = {2, blue_texture, blue_pixels, 0,0,0,0,8,8,0,0,0,0,32,256,0};
            assert(metallum_copy_pass(context, draw_command, pixel_fence, blue_copy, 16, shader_error, sizeof(shader_error)) == 1);
            metallum_resource_destroy(context, blue_texture);
            // Command ownership must outlive removal of application resource IDs.
            metallum_resource_destroy(context, green_texture);
            metallum_resource_destroy(context, pixel_fence);
            uint64_t draw_submission = metallum_submit(context, draw_command);
            assert(draw_submission && metallum_submission_wait(context, draw_submission, 5000, shader_error, sizeof(shader_error)) == 1);
            const uint8_t *red_result = metallum_buffer_contents(context, red_pixels);
            const uint8_t *green_result = metallum_buffer_contents(context, green_pixels);
            const uint8_t *blue_result = metallum_buffer_contents(context, blue_pixels);
            for (int pixel = 0; pixel < 64; ++pixel) {
                assert(blue_result[pixel*4] == 0 && blue_result[pixel*4+2] == 255 && blue_result[pixel*4+3] == 255);
                assert(red_result[pixel*4] == 255 && red_result[pixel*4+1] == 0 && red_result[pixel*4+3] == 255);
                assert(green_result[pixel*4] == 0 && green_result[pixel*4+1] == 255 && green_result[pixel*4+3] == 255);
            }
            metallum_buffer_destroy(context, blue_pixels);
            metallum_buffer_destroy(context, red_pixels); metallum_buffer_destroy(context, green_pixels);
            metallum_resource_destroy(context, draw_submission);
            metallum_resource_destroy(context, draw_command);
            metallum_resource_destroy(context, draw_texture);
            // Real MetalFX output, repeated cache reuse, resize and Off while work is in flight.
            if (metallum_device_info(context, 3)) {
                for (int cycle = 0; cycle < 4; ++cycle) {
                    uint32_t size = cycle < 2 ? 64 : 80, out_size = size * 2;
                    uint64_t source = metallum_texture_create(context, MTLPixelFormatRGBA8Unorm, size, size, 1, 1, 0, 1, NULL);
                    uint64_t target = metallum_texture_create(context, MTLPixelFormatRGBA8Unorm, out_size, out_size, 1, 1, 0, 1, NULL);
                    uint64_t fx_command = metallum_command_buffer_create(context, "MetalFX direct texture smoke");
                    uint64_t fx_fence = metallum_fence_create(context);
                    double fx_clear[] = {0, 1, 0, 1, 1};
                    uint64_t fx_pass = metallum_render_pass_create(context, fx_command, source, 0, 2, 0, fx_clear);
                    int64_t fence_words[8] = {3};
                    assert(metallum_render_command(context, fx_pass, 21, fx_fence, 0, fence_words) == 1);
                    metallum_resource_destroy(context, fx_pass);
                    assert(metallum_upscale(context, fx_command, source, target, fx_fence, shader_error, sizeof(shader_error)) == 1);
                    uint64_t fx_stats[18]; metallum_diagnostics_snapshot(context, fx_stats);
                    if (i == 0) printf("MetalFX cycle %d cached intermediate bytes: %llu\n", cycle, (unsigned long long)fx_stats[15]);
                    uint64_t pixels = metallum_buffer_create(context, out_size * out_size * 4, 1);
                    uint64_t read[] = {2, target, pixels, 0,0,0,0,out_size,out_size,0,0,0,0,out_size*4,out_size*out_size*4,0};
                    assert(metallum_copy_pass(context, fx_command, fx_fence, read, 16, shader_error, sizeof(shader_error)) == 1);
                    metallum_resource_destroy(context, source); metallum_resource_destroy(context, target);
                    metallum_resource_destroy(context, fx_fence);
                    if (cycle == 3) metallum_upscale_clear(context);
                    uint64_t fx_submission = metallum_submit(context, fx_command);
                    assert(fx_submission && metallum_submission_wait(context, fx_submission, 5000, shader_error, sizeof(shader_error)) == 1);
                    const uint8_t *result = metallum_buffer_contents(context, pixels);
                    for (uint32_t pixel = 0; pixel < out_size * out_size; ++pixel)
                        assert(result[pixel*4] <= 2 && result[pixel*4+1] >= 253 && result[pixel*4+2] <= 2);
                    metallum_buffer_destroy(context, pixels);
                    metallum_resource_destroy(context, fx_submission); metallum_resource_destroy(context, fx_command);
                }
                uint64_t fx_stats[18]; metallum_diagnostics_snapshot(context, fx_stats);
                assert(fx_stats[15] == 0);
            }
            uint64_t layer = metallum_layer_create(context, 2);
            assert(layer && metallum_layer_configure(context, layer, 1708, 960, 0) == 1);
            assert(metallum_layer_configure(context, layer, 0, 960, 0) == 0);
            metallum_resource_destroy(context, layer);
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
            uint64_t pass = metallum_render_pass_create(context, render_command, render_texture, 0, 2, 0, clear_values);
            assert(pass && metallum_resource_borrow_mtl(context, pass));
            metallum_resource_destroy(context, pass); // Must end the encoder before commit.
            metallum_resource_destroy(context, pass);
            uint64_t render_submission = metallum_submit(context, render_command);
            assert(render_submission && metallum_submission_wait(context, render_submission, 5000, shader_error, sizeof(shader_error)) == 1);
            metallum_resource_destroy(context, render_submission);
            metallum_resource_destroy(context, render_command);
            metallum_resource_destroy(context, render_texture);
            uint64_t diagnostics[18];
            metallum_diagnostics_snapshot(context, diagnostics); // Drain earlier work.
            uint64_t command = metallum_command_buffer_create(context, "Native command");
            uint64_t submission = metallum_submit(context, command);
            assert(submission);
            assert(metallum_submission_wait(context, submission, 5000, shader_error, sizeof(shader_error)) == 1);
            assert(metallum_submission_wait(context, submission, 0, shader_error, sizeof(shader_error)) == 1);
            metallum_diagnostics_snapshot(context, diagnostics);
            assert(diagnostics[0] == 1 && diagnostics[1] <= 1);
            assert(diagnostics[7] == 0 && diagnostics[8] <= 3 && diagnostics[11] == 0);
            assert(metallum_submission_wait(context, submission, 0, shader_error, sizeof(shader_error)) == 1);
            metallum_diagnostics_snapshot(context, diagnostics);
            assert(diagnostics[0] == 0 && diagnostics[4] == 0); // Already-retired wait is free.
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
            uint64_t tex_b = metallum_texture_create(context, 70, 4, 4, 1, 1, 0, 1, NULL);
            uint64_t copy_command = metallum_command_buffer_create(context, "Copy round trip");
            uint64_t copy_fence = metallum_fence_create(context);
            uint64_t upload[] = {1, copy_src, tex_a, 0,0,0,0,4,4,0,0,0,0,16,64,0};
            uint64_t texture_copy[] = {3, tex_a, tex_b, 0,0,0,0,4,4,0,0,0,0,0,0,0};
            uint64_t readback[] = {2, tex_b, copy_dst, 0,0,0,0,4,4,0,0,0,0,16,64,0};
            uint64_t buffer_copy[] = {0, copy_dst, copy_src, 0,0,0,0,0,0,64,0,0,0,0,0,64};
            metallum_diagnostics_snapshot(context, diagnostics);
            upload[3] = 240; // Valid starting offset, but the complete image does not fit.
            assert(metallum_copy_pass(context, copy_command, copy_fence, upload, 16, shader_error, sizeof(shader_error)) == 0);
            upload[3] = 0;
            assert(metallum_copy_pass(context, copy_command, copy_fence, upload, 16, shader_error, sizeof(shader_error)) == 1);
            assert(metallum_copy_pass(context, copy_command, copy_fence, texture_copy, 16, shader_error, sizeof(shader_error)) == 1);
            assert(metallum_copy_pass(context, copy_command, copy_fence, readback, 16, shader_error, sizeof(shader_error)) == 1);
            assert(metallum_copy_pass(context, copy_command, copy_fence, buffer_copy, 16, shader_error, sizeof(shader_error)) == 1);
            metallum_diagnostics_snapshot(context, diagnostics);
            assert(diagnostics[16] == 4 && diagnostics[17] == 1); // Four dependent transfers, one Metal pass.
            if (i == 0) puts("Four dependent transfers encoded in one Metal copy pass");
            // Changing fences ends the previous copy pass. Invalid transfers do not poison it.
            uint64_t other_fence = metallum_fence_create(context);
            readback[9] = 240;
            assert(metallum_copy_pass(context, copy_command, other_fence, readback, 16, shader_error, sizeof(shader_error)) == 0);
            readback[9] = 0;
            assert(metallum_copy_pass(context, copy_command, other_fence, readback, 16, shader_error, sizeof(shader_error)) == 1);
            metallum_diagnostics_snapshot(context, diagnostics);
            assert(diagnostics[16] == 1 && diagnostics[17] == 1);
            // Starting a render pass closes pending copies before changing their source.
            double blue_clear[] = {0, 0, 1, 1, 1};
            uint64_t transition_pass = metallum_render_pass_create(context, copy_command, tex_b, 0, 2, 0, blue_clear);
            assert(transition_pass);
            int64_t transition_fence[8] = {3};
            assert(metallum_render_command(context, transition_pass, 21, other_fence, 0, transition_fence) == 1);
            metallum_resource_destroy(context, transition_pass);
            readback[9] = 64;
            assert(metallum_copy_pass(context, copy_command, other_fence, readback, 16, shader_error, sizeof(shader_error)) == 1);
            metallum_resource_destroy(context, other_fence);
            uint64_t copy_submit = metallum_submit(context, copy_command);
            assert(copy_submit && metallum_submission_wait(context, copy_submit, 5000, shader_error, sizeof(shader_error)) == 1);
            assert(memcmp(pattern, pattern + 64, 64) == 0);
            const uint8_t *transition_pixels = metallum_buffer_contents(context, copy_dst);
            for (int pixel = 0; pixel < 16; pixel++) {
                assert(transition_pixels[64 + pixel * 4] == 0 && transition_pixels[64 + pixel * 4 + 2] == 255);
            }
            metallum_diagnostics_snapshot(context, diagnostics);
            assert(diagnostics[16] == 1 && diagnostics[17] == 1);
            metallum_diagnostics_snapshot(context, diagnostics);
            assert(diagnostics[16] == 0 && diagnostics[17] == 0);
            // An abandoned command must close its still-open copy encoder before recycling storage.
            uint64_t abandoned = metallum_command_buffer_create(context, "Abandoned copy pass");
            assert(metallum_copy_pass(context, abandoned, copy_fence, upload, 16, shader_error, sizeof(shader_error)) == 1);
            metallum_resource_destroy(context, abandoned);
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
