package com.example.minicassandra.util;

import java.util.concurrent.atomic.AtomicLong;

public final class TimestampGenerator {
    private static final AtomicLong LAST = new AtomicLong(System.currentTimeMillis() * 1_000L);

    private TimestampGenerator() {
    }

    public static long nextMicros() {
        while (true) {
            long now = System.currentTimeMillis() * 1_000L;
            long previous = LAST.get();
            long next = Math.max(now, previous + 1);
            if (LAST.compareAndSet(previous, next)) {
                return next;
            }
        }
    }
}
