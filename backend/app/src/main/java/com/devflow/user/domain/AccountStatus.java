package com.devflow.user.domain;

/**
 * Enumerates the possible lifecycle states of a DevFlow user account.
 *
 * <p>Maps directly to Spring Security account flags in {@link com.devflow.security.user.DevFlowUserDetails}:
 * <ul>
 *   <li>{@link #ACTIVE}: Fully operational account; enabled and unlocked.</li>
 *   <li>{@link #INACTIVE}: Deactivated account; disabled and expired.</li>
 *   <li>{@link #LOCKED}: Account locked due to security policy or failed attempts; locked.</li>
 *   <li>{@link #SUSPENDED}: Account suspended by platform administration; locked and disabled.</li>
 *   <li>{@link #PENDING_VERIFICATION}: Newly registered account awaiting email verification; disabled.</li>
 * </ul>
 *
 * @see User
 * @see <a href="../../../../docs/security/AUTHENTICATION_STRATEGY.md">Authentication Strategy §4</a>
 */
public enum AccountStatus {

    /**
     * Account is active, verified, and permitted to perform operational requests.
     */
    ACTIVE,

    /**
     * Account is deactivated or closed.
     */
    INACTIVE,

    /**
     * Account is locked due to access policy violations or failed authentication attempts.
     */
    LOCKED,

    /**
     * Account is suspended by platform administration.
     */
    SUSPENDED,

    /**
     * Account registration complete, but email verification remains pending.
     */
    PENDING_VERIFICATION;

    /**
     * Indicates whether an account in this status is enabled for active login.
     *
     * @return {@code true} if status is ACTIVE; {@code false} otherwise
     */
    public boolean isEnabled() {
        return this == ACTIVE;
    }

    /**
     * Indicates whether an account in this status is free from administrative or security locks.
     *
     * @return {@code true} if status is not LOCKED and not SUSPENDED; {@code false} otherwise
     */
    public boolean isAccountNonLocked() {
        return this != LOCKED && this != SUSPENDED;
    }

    /**
     * Indicates whether an account in this status has non-expired operational state.
     *
     * @return {@code true} if status is not INACTIVE; {@code false} otherwise
     */
    public boolean isAccountNonExpired() {
        return this != INACTIVE;
    }
}
