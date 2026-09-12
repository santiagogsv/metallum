#import <Metal/Metal.h>
#include "metallum.h"
#include <assert.h>
#include <stdio.h>

_Static_assert(sizeof(MTLDrawPrimitivesIndirectArguments) == 16, "Metal draw stride changed");
_Static_assert(sizeof(MTLDrawIndexedPrimitivesIndirectArguments) == 20, "Metal indexed draw stride changed");

int main(void) {
    @autoreleasepool {
        assert(metallum_abi_version() == 2);
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
            // Leave the private buffer alive to exercise device-owned cleanup.
            if (i == 0) printf("Native device: %s\n", device.name.UTF8String);
            buffer = nil;
            device = nil;
            metallum_device_destroy(context);
        }
    }
    puts("C ABI and Metal layout smoke test passed");
}
