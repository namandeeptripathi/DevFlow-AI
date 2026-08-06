package com.devflow.user.exception;

/**
 * Thrown when a user search query parameter fails domain validation rules
 * (e.g., query is blank, exceeds maximum length, or page size exceeds allowed limits).
 *
 * <p>Maps to HTTP 400 Bad Request via {@link com.devflow.exception.GlobalExceptionHandler}.
 *
 * @see UserDomainException
 * @see com.devflow.user.service.UserSearchService
 */
public class InvalidSearchQueryException extends UserDomainException {

    public InvalidSearchQueryException(String message) {
        super(message);
    }
}
