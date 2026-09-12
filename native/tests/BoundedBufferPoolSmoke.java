package com.metallum.render;

import java.util.HashSet;
import java.util.Set;

public class BoundedBufferPoolSmoke {
    public static void main(String[] args) {
        Set<Integer> released = new HashSet<>();
        var pool = new BoundedBufferPool<Integer>(100, 2, value -> {
            if (!released.add(value)) throw new AssertionError("Double release");
        });
        pool.recycle(40, 1); pool.recycle(40, 2); pool.recycle(40, 3);
        if (pool.bytes() != 80 || !released.contains(3)) throw new AssertionError("Budget exceeded");
        if (pool.take(40) != 2 || pool.bytes() != 40) throw new AssertionError("Take accounting");
        pool.recycle(40, 2);
        pool.recycle(101, 4);
        for (int i = 5; i < 10005; i++) pool.recycle(i, i);
        if (pool.bytes() > 100) throw new AssertionError("Diverse-size growth");
        pool.close(); pool.close();
        pool.recycle(1, 10005);
        if (pool.bytes() != 0 || pool.take(40) != null || released.size() != 10005) throw new AssertionError("Teardown leak");
        var tiny = new BoundedBufferPool<Integer>(100, 2, ignored -> {});
        tiny.recycle(1, 1); tiny.recycle(1, 2); tiny.recycle(1, 3);
        if (tiny.bytes() != 2) throw new AssertionError("Per-size bound ignored");
        tiny.close();
        System.out.println("Bounded buffer pool lifetime tests passed");
    }
}
