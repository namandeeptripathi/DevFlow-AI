package com.devflow.auth.exception;

/**
 * Thrown when an authentication attempt is made against a locked or suspended account.
 */
public class AccountLockedException extends AuthenticationDomainException {

    public AccountLockedException(String message) {
        super(message);
    }
}
