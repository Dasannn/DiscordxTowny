package com.discordtowny.storage;

/**
 * Database failure.
 *
 * <p>The message is shown in the console: it must not contain credentials. When
 * constructing it from a cause, ensure that the connection string is not
 * included in the text.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
