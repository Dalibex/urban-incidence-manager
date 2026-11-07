package es.uma.urbanpulse.shared.storage;

/**
 * The storage provider failed or does not have the content of a key it should have. It is not an
 * expected domain error: the API answers it with {@code 500}, without its detail.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
