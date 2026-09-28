package com.github.guoyyit.contractmockservicejava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.contractmockservicejava.config.MockProperties;
import com.github.guoyyit.contractmockservicejava.model.MockConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Durable storage of committed configurations.
 *
 * <p>Writes go to a temporary file in the same directory and are then atomically moved
 * over the target, so a crash mid-write can only leave the previous complete file or the
 * new complete file — never an interleaved/half-written configuration. The atomic
 * in-memory swap happens only after the durable write succeeds.
 */
@Component
public class ConfigPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(ConfigPersistenceService.class);

    private final MockProperties properties;
    private final ObjectMapper objectMapper;

    private volatile MockConfig loaded;

    public ConfigPersistenceService(MockProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public static MockConfig emptyConfig() {
        return new MockConfig(0L, null, List.of());
    }

    @PostConstruct
    public void init() {
        Path path = Path.of(properties.getStorePath());
        if (!Files.exists(path)) {
            loaded = emptyConfig();
            return;
        }
        try {
            loaded = objectMapper.readValue(path.toFile(), MockConfig.class);
            log.info("Restored committed mock configuration version {} from {}",
                    loaded.version(), path.toAbsolutePath());
        } catch (IOException e) {
            // A corrupt or truncated file is an unfinished/unsafe state: ignore it and
            // start empty rather than recovering an unverified partial configuration.
            log.warn("Ignoring unreadable persisted configuration at {}: {}",
                    path.toAbsolutePath(), e.getMessage());
            loaded = emptyConfig();
        }
    }

    /** Configuration restored at startup (never null). */
    public MockConfig load() {
        MockConfig snapshot = loaded;
        return snapshot != null ? snapshot : emptyConfig();
    }

    /** Atomically persist one fully committed configuration group. */
    public synchronized void save(MockConfig config) {
        Path target = Path.of(properties.getStorePath());
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(parent, "mock-config-", ".tmp");
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), config);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
            loaded = config;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist mock configuration", e);
        }
    }
}
