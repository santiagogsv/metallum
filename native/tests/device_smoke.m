#import <Metal/Metal.h>
#include "metallum.h"
#include <assert.h>
#include <stdio.h>

_Static_assert(sizeof(MTLDrawPrimitivesIndirectArguments) == 16, "Metal draw stride changed");
_Static_assert(sizeof(MTLDrawIndexedPrimitivesIndirectArguments) == 20, "Metal indexed draw stride changed");

int main(void) {
    @autoreleasepool {
        assert(metallum_abi_version() == 1);
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
            if (i == 0) printf("Native device: %s\n", device.name.UTF8String);
            buffer = nil;
            device = nil;
            metallum_device_destroy(context);
        }
    }
    puts("C ABI and Metal layout smoke test passed");
}
