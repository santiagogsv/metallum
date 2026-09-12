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
#ifdef __cplusplus
}
#endif
#endif
