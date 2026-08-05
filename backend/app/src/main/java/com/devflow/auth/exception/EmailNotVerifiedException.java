package com.devflow.auth.exception;

/**
 * Thrown when authentication requires a verified email address but the user's email is unverified.
 */
public class EmailNotVerifiedException extends AuthenticationDomainException {

    public EmailNotVerifiedException(String message) {
        super(message);
    }
}
