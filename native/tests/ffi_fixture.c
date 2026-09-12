/* CPU-only C ABI fixture. Exercises the Java boundary; this does NOT emulate Metal. */
#include "metallum.h"
#include <assert.h>
#include <stdlib.h>
#include <string.h>

typedef struct { void *data; int shared; } Buffer;
typedef struct { uint64_t next; Buffer buffers[256]; } Context;
#ifndef TEST_ABI_VERSION
#define TEST_ABI_VERSION 2
#endif
uint32_t metallum_abi_version(void) { return TEST_ABI_VERSION; }
void *metallum_device_create(void) { Context *c = calloc(1, sizeof(Context)); c->next = 1; return c; }
void *metallum_device_borrow_mtl(void *c) { return c; }
uint64_t metallum_buffer_create(void *context, uint64_t length, uint32_t shared) {
    Context *c = context;
    if (!c || !length || length > 4096 || shared > 1 || c->next >= 256) return 0;
    uint64_t id = c->next++;
    c->buffers[id] = (Buffer){calloc(1, length), shared};
    assert(c->buffers[id].data);
    return id;
}
void *metallum_buffer_borrow_mtl(void *context, uint64_t id) {
    Context *c = context;
    return c && id < 256 ? c->buffers[id].data : NULL;
}
void *metallum_buffer_contents(void *context, uint64_t id) {
    Context *c = context;
    return c && id < 256 && c->buffers[id].shared ? c->buffers[id].data : NULL;
}
void metallum_buffer_destroy(void *context, uint64_t id) {
    Context *c = context;
    if (c && id < 256) { free(c->buffers[id].data); c->buffers[id].data = NULL; }
}
void metallum_device_destroy(void *context) {
    Context *c = context;
    if (!c) return;
    for (uint64_t id = 1; id < c->next; id++) metallum_buffer_destroy(c, id);
    free(c);
}
