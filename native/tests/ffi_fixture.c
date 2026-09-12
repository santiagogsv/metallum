/* CPU-only C ABI fixture. Exercises the Java boundary; this does NOT emulate Metal. */
#include "metallum.h"
#include <assert.h>
#include <stdlib.h>
#include <string.h>

typedef struct { void *data; int shared; } Buffer;
typedef struct { int kind, mips, references; } Resource;
typedef struct { uint64_t next, next_resource; Buffer buffers[256]; Resource *resources[256]; } Context;
#ifndef TEST_ABI_VERSION
#define TEST_ABI_VERSION 7
#endif
uint32_t metallum_abi_version(void) { return TEST_ABI_VERSION; }
void *metallum_device_create(void) { Context *c = calloc(1, sizeof(Context)); c->next = 1; c->next_resource = 1; return c; }
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
    for (uint64_t id = 1; id < c->next_resource; id++) metallum_resource_destroy(c, id);
    free(c);
}

static uint64_t store_resource(Context *c, Resource *resource) {
    assert(c->next_resource < 256);
    uint64_t id = c->next_resource++;
    c->resources[id] = resource;
    resource->references++;
    return id;
}
uint64_t metallum_texture_create(void *context, uint64_t format, uint32_t width, uint32_t height,
                                uint32_t layers, uint32_t mips, uint32_t cube, uint32_t render_target, const char *label) {
    Context *c = context;
    if (!c || !format || !width || !height || !layers || !mips || cube > 1 || render_target > 1) return 0;
    if (cube && (width != height || layers % 6)) return 0;
    Resource *r = calloc(1, sizeof(Resource));
    assert(r);
    r->kind = 1; r->mips = mips;
    return store_resource(c, r);
}
uint64_t metallum_texture_view_create(void *context, uint64_t texture, uint32_t base, uint32_t count) {
    Context *c = context;
    Resource *source = c && texture < 256 ? c->resources[texture] : NULL;
    if (!source || source->kind != 1 || !count || base >= (uint32_t)source->mips || count > (uint32_t)source->mips - base) return 0;
    if (base == 0 && count == (uint32_t)source->mips) return store_resource(c, source);
    Resource *view = calloc(1, sizeof(Resource));
    assert(view);
    view->kind = 1; view->mips = count;
    return store_resource(c, view);
}
uint64_t metallum_sampler_create(void *context, uint32_t repeat_u, uint32_t repeat_v, uint32_t linear_min,
                                uint32_t linear_mag, uint32_t anisotropy, double max_lod) {
    Context *c = context;
    if (!c || repeat_u > 1 || repeat_v > 1 || linear_min > 1 || linear_mag > 1 || !anisotropy || anisotropy > 16) return 0;
    Resource *r = calloc(1, sizeof(Resource));
    assert(r);
    r->kind = 2;
    return store_resource(c, r);
}
void *metallum_resource_borrow_mtl(void *context, uint64_t id) {
    Context *c = context;
    return c && id < 256 ? c->resources[id] : NULL;
}
void metallum_resource_destroy(void *context, uint64_t id) {
    Context *c = context;
    Resource *r = c && id < 256 ? c->resources[id] : NULL;
    if (r) {
        if (--r->references == 0) free(r);
        c->resources[id] = NULL;
    }
}

uint64_t metallum_function_create(void *context, const char *source, const char *entry, char *error, uint32_t capacity) {
    if (error && capacity) error[0] = 0;
    if (!context || !source || !entry || !strcmp(entry, "missing") || !strcmp(source, "invalid")) {
        const char *message = "Fixture shader compilation failed";
        if (error && capacity) {
            size_t count = strlen(message);
            if (count >= capacity) count = capacity - 1;
            memcpy(error, message, count); error[count] = 0;
        }
        return 0;
    }
    Resource *r = calloc(1, sizeof(Resource));
    assert(r); r->kind = 3;
    return store_resource(context, r);
}
void metallum_shader_libraries_clear(void *context) { (void)context; }

uint64_t metallum_pipeline_create(void *context, uint64_t vertex, uint64_t fragment,
                                  const uint64_t *words, uint32_t count, char *error, uint32_t capacity) {
    Context *c = context;
    if (error && capacity) error[0] = 0;
    if (!c || vertex >= 256 || fragment >= 256 || !c->resources[vertex] || !c->resources[fragment]
        || c->resources[vertex]->kind != 3 || c->resources[fragment]->kind != 3
        || !words || count < 13 || count != 13 + 4 * (words[11] + words[12])) return 0;
    Resource *r = calloc(1, sizeof(Resource));
    assert(r); r->kind = 4;
    return store_resource(c, r);
}

uint64_t metallum_depth_state_create(void *context, uint64_t compare, uint32_t write) {
    if (!context || compare > 7 || write > 1) return 0;
    Resource *r = calloc(1, sizeof(Resource)); assert(r); r->kind = 5;
    return store_resource(context, r);
}
uint64_t metallum_present_sampler_create(void *context, uint32_t linear) {
    if (linear > 1) return 0;
    return metallum_sampler_create(context, 0, 0, linear, linear, 1, 0);
}
uint64_t metallum_buffer_texture_create(void *context, uint64_t buffer, uint64_t format,
                                       uint64_t offset, uint64_t width, uint64_t length) {
    Context *c = context;
    if (!c || buffer >= 256 || !c->buffers[buffer].data || !format || !width || !length) return 0;
    Resource *r = calloc(1, sizeof(Resource)); assert(r); r->kind = 1; r->mips = 1;
    return store_resource(context, r);
}

void metallum_memory_snapshot(void *context, uint64_t *out) {
    Context *c = context; memset(out, 0, 5 * sizeof(uint64_t));
    for (uint64_t i = 1; i < c->next; ++i) if (c->buffers[i].data) out[0]++;
    for (uint64_t i = 1; i < c->next_resource; ++i) if (c->resources[i]) out[1]++;
    /* Fixture has no Metal allocator or library cache. */
}
