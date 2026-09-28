package com.github.guoyyit.contractmockservicejava.store;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-endpoint/per-scenario mutable call-sequence state.
 *
 * <p>The counter is advanced <em>exactly once</em> when a request claims a step, before
 * any scenario delay runs. A request cancelled during the delay therefore neither
 * consumes the same step twice nor loses the increment: cancellation releases only the
 * scheduled delivery task, the committed sequence position stays.
 */
public class ScenarioRuntime {

    private final AtomicLong nextIndex = new AtomicLong(0L);

    public ScenarioRuntime() {
    }

    /**
     * Claim the next step index atomically.
     *
     * @return zero-based index of the step this request must render
     */
    public long nextIndex() {
        return nextIndex.getAndIncrement();
    }

    public long currentIndex() {
        return nextIndex.get();
    }

    /** Deterministically reset the sequence to its initial state. */
    public void reset() {
        nextIndex.set(0L);
    }
}
