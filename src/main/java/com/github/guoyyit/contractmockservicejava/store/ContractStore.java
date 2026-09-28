package com.github.guoyyit.contractmockservicejava.store;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the single active configuration revision behind an atomic reference.
 *
 * <p>{@link #replace(ActiveRevision)} publishes a complete group in one atomic operation,
 * so a request that captured the previous revision keeps seeing it in full while new
 * requests see the new one in full; no request can ever observe a mixed configuration.
 */
@Component
public class ContractStore {

    private final AtomicReference<ActiveRevision> active = new AtomicReference<>();

    public ActiveRevision current() {
        return active.get();
    }

    /** Atomically publish a new, fully built revision (fresh zeroed runtimes included). */
    public void replace(ActiveRevision revision) {
        active.set(revision);
    }
}
