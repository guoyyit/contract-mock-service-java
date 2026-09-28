package com.github.guoyyit.contractmockservicejava.store;

import com.github.guoyyit.contractmockservicejava.model.MockConfig;

/**
 * Persists only fully committed configuration sets.
 */
public interface ConfigRepository {

    /**
     * Loads the last committed config, or {@code null} if none exists.
     */
    MockConfig load();

    /**
     * Atomically persists a committed config. An interrupted/failed write
     * must never leave a torn file masquerading as committed.
     */
    void save(MockConfig config);
}
