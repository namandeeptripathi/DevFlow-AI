package com.devflow.user.exception;

/**
 * Thrown when an uploaded file fails avatar validation rules.
 *
 * <p>Examples: unsupported MIME type, file exceeds maximum size, or empty file.
 *
 * <p>Maps to HTTP 400 Bad Request via
 * {@link com.devflow.exception.GlobalExceptionHandler}.
 *
 * @see UserDomainException
 * @see com.devflow.user.service.AvatarService
 */
public class InvalidAvatarException extends UserDomainException {

    public InvalidAvatarException(String message) {
        super(message);
    }
}
