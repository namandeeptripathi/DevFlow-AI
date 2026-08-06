package com.devflow.user.exception;

/**
 * Thrown when user preferences update request fails domain validation (e.g. invalid IANA timezone or language code).
 *
 * @see UserDomainException
 * @see com.devflow.user.service.UserPreferencesService
 */
public class InvalidPreferencesException extends UserDomainException {

    public InvalidPreferencesException(String message) {
        super(message);
    }

    public InvalidPreferencesException(String message, Throwable cause) {
        super(message, cause);
    }
}
