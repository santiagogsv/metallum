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
#ifdef __cplusplus
}
#endif
#endif
