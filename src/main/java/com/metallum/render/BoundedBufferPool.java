package com.metallum.render;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Render-thread-only cache of GPU-completed buffers. Active/in-flight buffers never enter here. */
final class BoundedBufferPool<T> {
    private final long budget;
    private final int perSizeLimit;
    private final Consumer<T> release;
    private final Map<Long, ArrayDeque<T>> buckets = new HashMap<>();
    private long bytes;
    private boolean closed;

    BoundedBufferPool(long budget, int perSizeLimit, Consumer<T> release) {
        if (budget < 0 || perSizeLimit < 1) throw new IllegalArgumentException();
        this.budget = budget;
        this.perSizeLimit = perSizeLimit;
        this.release = release;
    }

    T take(long size) {
        var bucket = buckets.get(size);
        if (bucket == null) return null;
        T result = bucket.removeFirst();
        bytes -= size;
        if (bucket.isEmpty()) buckets.remove(size);
        return result;
    }

    void recycle(long size, T buffer) {
        var bucket = buckets.get(size);
        if (closed || size <= 0 || size > budget - bytes || (bucket != null && bucket.size() >= perSizeLimit)) {
            release.accept(buffer);
            return;
        }
        buckets.computeIfAbsent(size, ignored -> new ArrayDeque<>()).addFirst(buffer);
        bytes += size;
    }

    long bytes() { return bytes; }

    void close() {
        closed = true;
        for (var bucket : buckets.values()) for (T buffer : bucket) release.accept(buffer);
        buckets.clear();
        bytes = 0;
    }
}
