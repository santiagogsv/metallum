#ifndef METALLUM_H
#define METALLUM_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
uint32_t metallum_abi_version(void);
/* Returns an owning opaque context, or NULL when Metal is unavailable. */
void *metallum_device_create(void);
/* Borrowed Objective-C object, valid until context destruction. Transitional only. */
void *metallum_device_borrow_mtl(void *context);
/* NULL is allowed. A non-NULL context must be destroyed exactly once.
 * Calls and destruction must be serialized by the owning render thread.
 * All GPU work and dependent Java resources must be finished before destruction. */
void metallum_device_destroy(void *context);
/* Buffer IDs are device-local, never reused; zero means allocation failure.
 * cpu_accessible: 0 = GPU-private, 1 = shared. Other values are rejected.
 * All accesses are serialized on the render thread. Destruction must follow GPU completion.
 * Borrowed MTL and contents pointers expire at buffer or device destruction.
 * contents returns NULL for private/missing buffers; destroying a missing ID is harmless.
 * Device destruction also releases any remaining buffers. */
uint64_t metallum_buffer_create(void *context, uint64_t length, uint32_t cpu_accessible);
void *metallum_buffer_borrow_mtl(void *context, uint64_t buffer);
void *metallum_buffer_contents(void *context, uint64_t buffer);
void metallum_buffer_destroy(void *context, uint64_t buffer);
/* Resource ABI 3. IDs are device-local and in a separate namespace from buffer IDs.
 * pixel_format is MTLPixelFormat's raw value, supplied by the Blaze3D format adapter.
 * cube/render_target/repeat/linear arguments are booleans encoded as 0 or 1.
 * label is optional UTF-8, copied during creation. Invalid input/allocation returns zero.
 * Textures are private, untracked, shader-readable; render_target adds attachment usage.
 * Views own their backing independently of the source resource ID.
 * These calls and destruction use the same render-thread/GPU-completion rules as buffers. */
uint64_t metallum_texture_create(void *context, uint64_t pixel_format, uint32_t width, uint32_t height,
                                uint32_t layers, uint32_t mip_levels, uint32_t cube, uint32_t render_target, const char *label);
uint64_t metallum_texture_view_create(void *context, uint64_t texture, uint32_t base_mip, uint32_t mip_count);
uint64_t metallum_sampler_create(void *context, uint32_t repeat_u, uint32_t repeat_v, uint32_t linear_min,
                                uint32_t linear_mag, uint32_t anisotropy, double max_lod);
void *metallum_resource_borrow_mtl(void *context, uint64_t resource);
void metallum_resource_destroy(void *context, uint64_t resource);
/* Shader ABI 4. Source and entry are copied UTF-8 strings. Success returns an owning
 * resource ID (release with metallum_resource_destroy); failure returns zero and writes
 * a bounded, terminated UTF-8 diagnostic if error_capacity > 0 and error_output != NULL.
 * Libraries are cached by full source within the device. Clear after GPU completion and
 * pipeline/function retirement on resource reload; clearing does not invalidate owned functions. */
uint64_t metallum_function_create(void *context, const char *source, const char *entry,
                                  char *error_output, uint32_t error_capacity);
void metallum_shader_libraries_clear(void *context);
/* Pipeline ABI 5: all words are uint64_t in native byte order. Header fields:
 * colorFormat, depthFormat, stencilFormat, writeMask, blendingEnabled,
 * sourceRGB, destRGB, rgbOp, sourceAlpha, destAlpha, alphaOp, attributeCount, layoutCount.
 * Attribute entries: index, format, offset, bufferIndex. Layouts: bufferIndex, stride,
 * stepFunction, stepRate. All enum fields are Metal raw values; counts <= 31, indices < 31.
 * No native descriptors/pointers appear in the payload. Vertex/fragment IDs belong to this
 * device's resource table. Returns an owning resource ID, released through resource_destroy.
 * Diagnostic rules match function_create. Calls are synchronous and render-thread confined. */
uint64_t metallum_pipeline_create(void *context, uint64_t vertex, uint64_t fragment,
                                  const uint64_t *words, uint32_t count, char *error_output, uint32_t error_capacity);
/* ABI 6: independently owned resource IDs; destroy with metallum_resource_destroy. */
uint64_t metallum_depth_state_create(void *context, uint64_t compare, uint32_t write);
uint64_t metallum_present_sampler_create(void *context, uint32_t linear);
/* buffer_id is in the buffer namespace; result is in the resource namespace. */
uint64_t metallum_buffer_texture_create(void *context, uint64_t buffer_id, uint64_t format,
                                      uint64_t offset, uint64_t width, uint64_t byte_length);

/* ABI 7: output points to five uint64_t words: buffer entries, resource entries,
 * cached libraries, owned buffer bytes, MTLDevice.currentAllocatedSize bytes.
 * Render thread only; counts may include aliased resources. */
void metallum_memory_snapshot(void *context, uint64_t *output);
/* ABI 16: 16 uint64 words. First seven drain interval counters: retired submissions,
 * valid GPU samples, GPU total ns, GPU max ns, CPU completion-wait ns, binding writes,
 * skipped binding writes. Then gauges: active slots, idle slots, staging bytes,
 * allocator bytes, held references, active residency sets, idle residency sets,
 * interval allocator trims, cached MetalFX texture bytes.
 * GPU samples describe submissions, not frames. No GPU wait occurs in this call. */
void metallum_diagnostics_snapshot(void *context, uint64_t *output);
/* Optional Metal 4 spatial upscale, RGBA8 perceptual input/output, larger destination.
 * IDs use resources. Both textures are command-owned until GPU completion. */
int32_t metallum_upscale(void *context, uint64_t command, uint64_t source, uint64_t destination,
                        uint64_t fence, char *error, uint32_t capacity);
void metallum_upscale_clear(void *context);
/* ABI 9: submit accepts an owned command-buffer resource ID and returns an owned
 * resource ID. Wait: 1 complete, 0 timeout, -1 error with UTF-8 diagnostic.
 * Closing a submission joins GPU work and all completion handlers. */
uint64_t metallum_command_buffer_create(void *context, const char *label);
uint64_t metallum_submit(void *context, uint64_t command_buffer);
int32_t metallum_submission_wait(void *context, uint64_t submission, int64_t timeout_ms, char *error, uint32_t capacity);
/* ABI 10: one complete fenced copy pass. Payload is 16 uint64_t words:
 * op(0 BB,1 BT,2 TB,3 TT), srcID,dstID,srcOffsetOrSlice,srcLevel,srcX,srcY,
 * width,height,dstOffsetOrSlice,dstLevel,dstX,dstY,rowBytes,imageBytes,size.
 * Buffer IDs use the buffer table; texture/command/fence IDs use resources. */
uint64_t metallum_fence_create(void *context);
int32_t metallum_copy_pass(void *context, uint64_t command, uint64_t fence, const uint64_t *words, uint32_t count, char *error, uint32_t capacity);
/* ABI 13: color/depth are texture resource IDs; zero omits an attachment.
 * Clear points to five doubles (RGBA, depth). Loads: 0 discard, 1 preserve, 2 clear.
 * Returns an owned pass ID; resource_borrow_mtl borrows its encoder.
 * Destroying the pass ends encoding exactly once. End before command submission. */
uint64_t metallum_render_pass_create(void *context, uint64_t command, uint64_t color, uint64_t depth, uint32_t color_load, uint32_t depth_load, const double *clear);
/* ABI 13: synchronous render-thread operations. words has eight int64_t slots;
 * floating-point slots use the IEEE double bit pattern. p0/p1 are resource IDs,
 * using the buffer table for buffer operations. Zero unbinds nullable resources.
 * Inline bytes use metallum_render_bytes. See MetalDraws.swift.
 * Returns 1 on success, 0 for invalid IDs/opcodes/arguments. */
int32_t metallum_render_command(void *context, uint64_t pass, uint32_t op, uint64_t p0, uint64_t p1, const int64_t *words);
/* ABI 17: indexed batch, consumed synchronously, at most 256 records.
 * words: primitive, index type, instance count, base instance (four int64_t).
 * Each 16-byte record: int64_t byte offset, int32_t count, int32_t base vertex.
 * Render ops 19/20 now require draw counts in words[3]/words[2], respectively. */
int32_t metallum_render_indexed_batch(void *context, uint64_t pass, uint64_t indices,
                                    const int64_t *words, const void *records, uint32_t count);
uint64_t metallum_layer_create(void *context, double scale);
int32_t metallum_layer_configure(void *context, uint64_t layer, double width, double height, uint32_t immediate);
/* Acquires and encodes the drawable entirely in Swift. Submission performs the
 * Metal 4 queue wait/commit/signal/present sequence. No available drawable is a skipped frame. */
int32_t metallum_present(void *context, uint64_t command, uint64_t layer, uint64_t source, uint64_t fence, uint64_t pipeline, uint64_t nearest, uint64_t linear);
/* ABI 14: Metal 4 compiler/queue/encoders. Resource IDs and payloads are unchanged.
 * Inline bytes are copied into command-owned storage before returning; length <= 4096.
 * Command storage and residency are recycled only after GPU completion. */
int32_t metallum_render_bytes(void *context, uint64_t pass, const void *bytes, uint64_t length, uint64_t index);
uint64_t metallum_texture_info(void *context, uint64_t texture, uint32_t field);
int32_t metallum_command_debug(void *context, uint64_t command, const char *label);
uint64_t metallum_device_info(void *context, uint32_t field);
void metallum_device_name(void *context, char *output, uint32_t capacity);
#ifdef __cplusplus
}
#endif
#endif
