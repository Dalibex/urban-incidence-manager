package es.uma.urbanpulse.shared.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Keeps every file under a base folder, the {@code evidence_data} volume (ADR-0013). */
public class LocalFsStorage implements StorageService {

    private final Path basePath;

    public LocalFsStorage(Path basePath) {
        this.basePath = basePath;
    }

    @Override
    public String put(byte[] content) {
        var key = UUID.randomUUID().toString();
        try {
            Files.createDirectories(basePath);
            Files.write(basePath.resolve(key), content, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new StorageException("Could not store the content of " + key, e);
        }
        return key;
    }

    @Override
    public byte[] get(String key) {
        var file = fileOf(key);
        try {
            return Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            throw new StorageException("There is no content for " + key, e);
        } catch (IOException e) {
            throw new StorageException("Could not read the content of " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(fileOf(key));
    }

    @Override
    public void delete(String key) {
        var file = fileOf(key);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new StorageException("Could not delete the content of " + key, e);
        }
    }

    /**
     * The file of a key. Only the canonical form of a UUID is accepted, the only keys {@link #put}
     * generates, so a key can never name a path outside the base folder.
     */
    private Path fileOf(String key) {
        if (!isGeneratedKey(key)) {
            throw new IllegalArgumentException("Not a storage key: " + key);
        }
        return basePath.resolve(key);
    }

    private static boolean isGeneratedKey(String key) {
        try {
            return UUID.fromString(key).toString().equals(key);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
