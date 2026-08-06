package com.devflow.user.exception;

/**
 * Thrown when a {@link com.devflow.user.domain.UserProfile} record cannot be located
 * for the requested user.
 *
 * <p>Typically raised by {@link com.devflow.user.service.UserProfileService} when a
 * profile lookup yields no result for a given user ID.
 *
 * @see UserDomainException
 * @see com.devflow.user.service.UserProfileService
 */
public class UserProfileNotFoundException extends UserDomainException {

    public UserProfileNotFoundException(String message) {
        super(message);
    }
}
