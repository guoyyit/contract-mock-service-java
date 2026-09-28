package com.github.guoyyit.contractmockservicejava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.contractmockservicejava.model.MockConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * File-backed repository. A commit is written to a temporary file, fsynced,
 * then atomically moved over the target. A crashed partial write therefore
 * never appears as a committed configuration.
 *
 * <p>Normal commits can never leave a torn file (the move is atomic). If the
 * target is nonetheless found unreadable on startup (for example external
 * corruption), it is quarantined and the service starts empty rather than
 * failing to boot.
 */
@Repository
public class FileConfigRepository implements ConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(FileConfigRepository.class);

    private final Path filePath;
    private final ObjectMapper objectMapper;

    public FileConfigRepository(@Value("${mock.store.file:target/mock-config.json}") String filePath) {
        this.filePath = Path.of(filePath);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
    }

    @Override
    public synchronized MockConfig load() {
        if (!Files.exists(filePath)) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(filePath);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read committed config from " + filePath, e);
        }
        if (bytes.length == 0) {
            return null;
        }
        try {
            return objectMapper.readValue(bytes, MockConfig.class);
        } catch (IOException e) {
            quarantine(e);
            return null;
        }
    }

    private void quarantine(IOException cause) {
        Path quarantined = filePath.resolveSibling(filePath.getFileName() + ".corrupt");
        try {
            Files.move(filePath, quarantined, StandardCopyOption.REPLACE_EXISTING);
            log.warn("Unreadable committed config at {} quarantined to {}; starting empty",
                    filePath, quarantined, cause);
        } catch (IOException moveError) {
            log.error("Unreadable committed config at {} and quarantine failed; starting empty",
                    filePath, cause);
        }
    }

    @Override
    public synchronized void save(MockConfig config) {
        try {
            Path parent = filePath.toAbsolutePath().getParent();
            // Guard with exists(): createDirectories on an existing symlinked
            // directory (e.g. macOS /tmp -> /private/tmp) can otherwise raise
            // FileAlreadyExistsException.
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(parent, "mock-config", ".tmp");
            try {
                byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(config);
                try (var out = Files.newOutputStream(tmp, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC)) {
                    out.write(bytes);
                }
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist committed config to " + filePath, e);
        }
    }
}
