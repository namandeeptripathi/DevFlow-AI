package com.devflow.user.exception;

/**
 * Thrown when the underlying storage backend fails to persist or remove an avatar file.
 *
 * <p>Represents an infrastructure-level failure (I/O errors, permission denied, etc.)
 * as opposed to a business validation failure ({@link InvalidAvatarException}).
 *
 * <p>Maps to HTTP 500 Internal Server Error via
 * {@link com.devflow.exception.GlobalExceptionHandler}.
 *
 * @see UserDomainException
 * @see com.devflow.user.avatar.AvatarStorageService
 */
public class AvatarStorageException extends UserDomainException {

    public AvatarStorageException(String message) {
        super(message);
    }

    public AvatarStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
