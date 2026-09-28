package com.github.guoyyit.contractmockservicejava.store;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutable per-scenario call counter belonging to exactly one snapshot.
 */
public class ScenarioState {

    private final AtomicInteger counter = new AtomicInteger(0);

    /** Atomically consumes one call and returns the zero-based step index. */
    public int incrementAndGet() {
        return counter.getAndIncrement();
    }

    public int get() {
        return counter.get();
    }

    public void reset() {
        counter.set(0);
    }
}
