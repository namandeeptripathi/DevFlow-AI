package com.devflow.user.exception;

/**
 * Thrown when a {@link com.devflow.user.domain.UserPreferences} record cannot be located
 * for the requested user.
 *
 * @see UserDomainException
 * @see com.devflow.user.service.UserPreferencesService
 */
public class UserPreferencesNotFoundException extends UserDomainException {

    public UserPreferencesNotFoundException(String message) {
        super(message);
    }
}
