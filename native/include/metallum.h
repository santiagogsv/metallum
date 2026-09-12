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
#ifdef __cplusplus
}
#endif
#endif
