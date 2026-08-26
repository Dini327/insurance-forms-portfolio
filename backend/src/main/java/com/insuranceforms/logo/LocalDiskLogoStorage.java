package com.insuranceforms.logo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class LocalDiskLogoStorage implements LogoStorage {

    private final Path storageDir;

    public LocalDiskLogoStorage(@Value("${logo.storage-dir}") String storageDir) {
        this.storageDir = Path.of(storageDir);
        try {
            Files.createDirectories(this.storageDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public LogoRef store(byte[] bytes) {
        UUID id = UUID.randomUUID();
        try {
            Files.write(pathFor(id), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new LogoRef(id, "/logos/" + id);
    }

    @Override
    public InputStream resolve(UUID id) {
        Path path = pathFor(id);
        if (!Files.exists(path)) {
            throw new LogoNotFoundException(id);
        }
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(UUID id) {
        try {
            Files.deleteIfExists(pathFor(id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // id is a UUID, never a raw String -- UUID.toString() always produces the
    // canonical 8-4-4-4-12 hex format and can never contain "/" or "..", so this
    // concatenation can never escape storageDir. See LocalDiskLogoStorageTest's
    // malformedIdentifierCannotBeParsedIntoAUuidToAttemptPathTraversal.
    private Path pathFor(UUID id) {
        return storageDir.resolve(id + ".png");
    }
}
