/* CPU-only C ABI fixture. Exercises the Java boundary; this does NOT emulate Metal. */
#include "metallum.h"
#include <assert.h>
#include <stdlib.h>
#include <string.h>

typedef struct { void *data; int shared; uint64_t length; } Buffer;
typedef struct { int kind, mips, references; } Resource;
typedef struct { uint64_t next, next_resource; Buffer buffers[256]; Resource *resources[256]; } Context;
#ifndef TEST_ABI_VERSION
#define TEST_ABI_VERSION 17
#endif
uint32_t metallum_abi_version(void) { return TEST_ABI_VERSION; }
void *metallum_device_create(void) { Context *c = calloc(1, sizeof(Context)); c->next = 1; c->next_resource = 1; return c; }
void *metallum_device_borrow_mtl(void *c) { return c; }
uint64_t metallum_buffer_create(void *context, uint64_t length, uint32_t shared) {
    Context *c = context;
    if (!c || !length || length > 4096 || shared > 1 || c->next >= 256) return 0;
    uint64_t id = c->next++;
    c->buffers[id] = (Buffer){calloc(1, length), shared, length};
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

uint64_t metallum_command_buffer_create(void *context, const char *label) {
    if (!context) return 0;
    Resource *r = calloc(1, sizeof(Resource)); assert(r); r->kind = 7;
    return store_resource(context, r);
}
uint64_t metallum_submit(void *context, uint64_t command) {
    Context *c = context;
    if (!c || command >= 256 || !c->resources[command] || c->resources[command]->kind != 7) return 0;
    c->resources[command]->kind = 8;
    Resource *r = calloc(1, sizeof(Resource)); assert(r); r->kind = 6;
    return store_resource(context, r);
}
int32_t metallum_submission_wait(void *context, uint64_t id, int64_t timeout, char *error, uint32_t capacity) {
    Context *c = context;
    if (error && capacity) error[0] = 0;
    return c && id < 256 && c->resources[id] && c->resources[id]->kind == 6 ? 1 : -1;
}

uint64_t metallum_fence_create(void *context) { return metallum_depth_state_create(context, 0, 0); }
int32_t metallum_copy_pass(void *context, uint64_t command, uint64_t fence, const uint64_t *words, uint32_t count, char *error, uint32_t capacity) {
    if (error && capacity) error[0] = 0;
    Context *c = context;
    if (!c || command >= 256 || fence >= 256 || !c->resources[command] || c->resources[command]->kind != 7
        || !c->resources[fence] || !words || count != 16 || words[0] > 3) return 0;
    if (words[0] == 0) {
        if (words[1] >= 256 || words[2] >= 256) return 0;
        Buffer *src = &c->buffers[words[1]], *dst = &c->buffers[words[2]];
        if (!src->data || !dst->data || words[3] > src->length || words[9] > dst->length
            || words[15] > src->length - words[3] || words[15] > dst->length - words[9]) return 0;
        memcpy((char *)dst->data + words[9], (char *)src->data + words[3], words[15]);
    }
    return 1;
}
uint64_t metallum_render_pass_create(void *context, uint64_t command, uint64_t color, uint64_t depth, uint32_t color_load, uint32_t depth_load, const double *clear) {
    Context *c = context;
    if (!c || command >= 256 || !c->resources[command] || c->resources[command]->kind != 7 || (!color && !depth) || !clear || color_load > 2 || depth_load > 2) return 0;
    Resource *r = calloc(1, sizeof(Resource)); assert(r); r->kind = 9;
    return store_resource(c, r);
}
/* Test-only recording: validates the real Java draw adapter's ABI packing. */
static int64_t last_render[11];
void metallum_test_last_render(int64_t *out) { memcpy(out, last_render, sizeof(last_render)); }
int32_t metallum_render_command(void *context, uint64_t pass, uint32_t op, uint64_t p0, uint64_t p1, const int64_t *words) {
    Context *c = context;
    if (!c || pass >= 256 || !c->resources[pass] || c->resources[pass]->kind != 9 || !words || op > 22) return 0;
    last_render[0] = op; last_render[1] = (intptr_t)p0; last_render[2] = (intptr_t)p1;
    memcpy(last_render + 3, words, 8 * sizeof(int64_t));
    return 1;
}
static int64_t batch_stats[10];
void metallum_test_batch_stats(int64_t *out) { memcpy(out, batch_stats, sizeof(batch_stats)); }
void metallum_test_reset_batch(void) { memset(batch_stats, 0, sizeof(batch_stats)); }
int32_t metallum_render_indexed_batch(void *context, uint64_t pass, uint64_t indices,
                                    const int64_t *words, const void *records, uint32_t count) {
    Context *c = context;
    if (!c || pass >= 256 || !c->resources[pass] || c->resources[pass]->kind != 9 || !records || !words || count > 256) return 0;
    batch_stats[0]++; batch_stats[1] += count;
    for (uint32_t i = 0; i < count; i++) {
        const char *r = (const char *)records + i * 16;
        int64_t offset; int32_t n, vertex;
        memcpy(&offset, r, 8); memcpy(&n, r + 8, 4); memcpy(&vertex, r + 12, 4);
        batch_stats[2] += offset; batch_stats[3] += n; batch_stats[4] += vertex;
    }
    memcpy(batch_stats + 5, words, 4 * sizeof(int64_t)); batch_stats[9] = indices;
    return 1;
}
uint64_t metallum_layer_create(void *context, double scale) {
    if (!context || !(scale > 0)) return 0;
    Resource *r = calloc(1, sizeof(Resource)); assert(r); r->kind = 10;
    return store_resource(context, r);
}
int32_t metallum_layer_configure(void *context, uint64_t layer, double width, double height, uint32_t immediate) {
    Context *c = context;
    return c && layer < 256 && c->resources[layer] && c->resources[layer]->kind == 10 && width > 0 && height > 0 && immediate <= 1;
}
int32_t metallum_present(void *context, uint64_t command, uint64_t layer, uint64_t source, uint64_t fence, uint64_t pipeline, uint64_t nearest, uint64_t linear) {
    Context *c = context;
    return c && command < 256 && layer < 256 && c->resources[command] && c->resources[command]->kind == 7
        && c->resources[layer] && c->resources[layer]->kind == 10 && source && pipeline && nearest && linear;
}

int32_t metallum_render_bytes(void *context, uint64_t pass, const void *bytes, uint64_t length, uint64_t index) {
    Context *c = context;
    if (!c || pass >= 256 || !c->resources[pass] || c->resources[pass]->kind != 9 || !bytes || length > 4096 || index >= 31) return 0;
    memset(last_render, 0, sizeof(last_render)); last_render[0] = 16; last_render[1] = (intptr_t)bytes; last_render[3] = length; last_render[4] = index;
    return 1;
}
uint64_t metallum_texture_info(void *context, uint64_t texture, uint32_t field) { return field == 0 ? 70 : 8; }
int32_t metallum_command_debug(void *context, uint64_t command, const char *label) {
    Context *c = context; return c && command < 256 && c->resources[command] && c->resources[command]->kind == 7;
}
uint64_t metallum_device_info(void *context, uint32_t field) { return field == 0 ? 4096 : field == 1 ? 1048576 : 1; }
void metallum_device_name(void *context, char *output, uint32_t capacity) { if (capacity) { strncpy(output, "Fixture Metal", capacity); output[capacity-1]=0; } }

/* Distinct values validate every diagnostics field across Java FFM. */
void metallum_diagnostics_snapshot(void *context, uint64_t *out) {
    (void)context; for (uint64_t i = 0; i < 16; ++i) out[i] = 100 + i;
}

int32_t metallum_upscale(void *context, uint64_t command, uint64_t source, uint64_t destination,
                        uint64_t fence, char *error, uint32_t capacity) {
    Context *c = context;
    if (capacity) error[0] = 0;
    if (!c || command >= 256 || source >= 256 || destination >= 256 || fence >= 256) return 0;
    return c->resources[command] && c->resources[command]->kind == 7 &&
        c->resources[source] && c->resources[source]->kind == 1 &&
        c->resources[destination] && c->resources[destination]->kind == 1 && c->resources[fence];
}
void metallum_upscale_clear(void *context) { (void)context; }
