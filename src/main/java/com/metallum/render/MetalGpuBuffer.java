package com.metallum.render;

import com.metallum.mtl.MTLBuffer;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Environment(EnvType.CLIENT)
class MetalGpuBuffer extends GpuBuffer {
    private final MetalDevice device;
    private final boolean cpuAccessible;
    private final boolean dynamic;
    private final long allocationSize;
    @Nullable
    private MTLBuffer nativeBuffer;
    @Nullable
    private ByteBuffer storage;
    private boolean closed;

    MetalGpuBuffer(final MetalDevice device, @GpuBuffer.Usage final int usage, final long size) {
        super(usage, size);
        this.device = device;

        this.dynamic = isDynamic(usage);
        this.cpuAccessible = isCpuAccessible(usage) || this.dynamic;
        if (size < 0 || size > Long.MAX_VALUE - 15L) throw new IllegalArgumentException("Invalid buffer size: " + size);
        this.allocationSize = Math.max(16L, (size + 15L) & ~15L);
        this.nativeBuffer = device.allocateBuffer(this.allocationSize, this.cpuAccessible);

        try {
            if (this.cpuAccessible) {
                MemorySegment contents = this.nativeBuffer.contents();
                if (contents.address() == 0) throw new IllegalStateException("MTLBuffer.contents returned null");
                this.storage = contents.reinterpret(this.allocationSize).asByteBuffer().order(ByteOrder.nativeOrder());
            } else {
                this.storage = null;
            }
        } catch (Throwable failure) {
            this.nativeBuffer.close();
            this.nativeBuffer = null;
            throw failure;
        }
    }

    MetalGpuBuffer(final MetalDevice device, @GpuBuffer.Usage final int usage, final long size, final @Nullable MTLBuffer wrappedBuffer) {
        super(usage, size);
        this.device = device;
        this.cpuAccessible = false;
        this.dynamic = false;
        this.allocationSize = size;
        this.nativeBuffer = wrappedBuffer;
        this.storage = null;
    }

    ByteBuffer sliceStorage(final long offset, final long length) {
        if (this.storage == null) {
            throw new IllegalStateException("Buffer is not CPU-accessible");
        }

        return storage.duplicate()
                .position(Math.toIntExact(offset))
                .limit(Math.toIntExact(offset + length))
                .slice()
                .order(this.storage.order());
    }

    MTLBuffer metalBuffer() {
        if (this.nativeBuffer == null) {
            throw new IllegalStateException("Native Metal buffer is closed");
        }
        return this.nativeBuffer;
    }


    boolean isDynamic() {
        return this.dynamic;
    }

    boolean isCpuAccessible() {
        return this.cpuAccessible;
    }

    void writeDirect(final long offset, final ByteBuffer data) {
        this.sliceStorage(offset, data.remaining()).put(data.duplicate());
    }

    long allocationSize() {
        return this.allocationSize;
    }

    ByteBuffer currentStorage() {
        if (this.storage == null) {
            throw new IllegalStateException("Buffer is not CPU-accessible");
        }
        return this.storage.duplicate().order(this.storage.order());
    }

    void swapBacking(final MTLBuffer buffer, final ByteBuffer storage) {
        this.nativeBuffer = buffer;
        this.storage = storage;
    }

    @Override
    public boolean isClosed() {
        return this.closed || this.nativeBuffer == null;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.storage = null;
        if (this.nativeBuffer != null) {
            MTLBuffer buffer = this.nativeBuffer;
            this.nativeBuffer = null;
            this.device.queueBufferRelease(buffer);
        }
    }

    @Override
    public GpuBufferSlice.@NonNull MappedView map(final long offset, final long length, final boolean read, final boolean write) {
        if (this.isClosed()) {
            throw new IllegalStateException("Buffer already closed");
        }
        if (!read && !write) {
            throw new IllegalArgumentException("At least read or write must be true");
        }
        if (read && (this.usage() & GpuBuffer.USAGE_MAP_READ) == 0) {
            throw new IllegalStateException("Buffer is not readable");
        }
        if (write && (this.usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) {
            throw new IllegalStateException("Buffer is not writable");
        }
        ByteBuffer mapped = this.sliceStorage(offset, length);
        return new GpuBufferSlice.MappedView(this.slice(offset, length), mapped, () -> {
        });
    }

    public int getUsage() {
        return this.usage();
    }

    private static boolean isCpuAccessible(@GpuBuffer.Usage final int usage) {
        return (usage & GpuBuffer.USAGE_INDEX) != 0
                || (usage & GpuBuffer.USAGE_MAP_READ) != 0
                || (usage & GpuBuffer.USAGE_MAP_WRITE) != 0
                || (usage & GpuBuffer.USAGE_HINT_CLIENT_STORAGE) != 0;
    }

    private static boolean isDynamic(@GpuBuffer.Usage final int usage) {
        return (usage & GpuBuffer.USAGE_UNIFORM) != 0 && (usage & GpuBuffer.USAGE_COPY_DST) != 0;
    }

}
