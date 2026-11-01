package com.devflow.modules.auth.api;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable outcome of an authorization policy evaluation performed by {@link AuthApi}.
 *
 * <p>Represents a binary {@code ALLOW} or {@code DENY} decision along with structured
 * denial classification and diagnostic messaging.
 *
 * @param allowed      whether the requested action is permitted
 * @param denialReason the structured classification of denial if rejected; {@code null} if permitted
 * @param message      diagnostic explanation of the decision
 *
 * @see AuthApi
 * @see DenialReason
 * @see AuthorizationRequest
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §10.1</a>
 */
public record AuthorizationDecision(
        boolean allowed,
        DenialReason denialReason,
        String message
) implements Serializable {

    private static final AuthorizationDecision ALLOWED_INSTANCE =
            new AuthorizationDecision(true, null, "Access granted");

    public AuthorizationDecision {
        if (allowed && denialReason != null) {
            throw new IllegalArgumentException("An ALLOW decision must not carry a denial reason");
        }
        if (!allowed && denialReason == null) {
            denialReason = DenialReason.GENERAL_DENIED;
        }
    }

    /**
     * Returns a standard ALLOW decision indicating access is granted.
     *
     * @return the singleton permitted decision
     */
    public static AuthorizationDecision allow() {
        return ALLOWED_INSTANCE;
    }

    /**
     * Creates a DENY decision with a structured denial reason and custom message.
     *
     * @param reason  the reason classification
     * @param message diagnostic explanation
     * @return an immutable rejected decision
     */
    public static AuthorizationDecision deny(DenialReason reason, String message) {
        Objects.requireNonNull(reason, "DenialReason must not be null for a denied decision");
        return new AuthorizationDecision(false, reason, message);
    }

    /**
     * Creates a DENY decision with a structured denial reason and its default message.
     *
     * @param reason the reason classification
     * @return an immutable rejected decision
     */
    public static AuthorizationDecision deny(DenialReason reason) {
        Objects.requireNonNull(reason, "DenialReason must not be null for a denied decision");
        return new AuthorizationDecision(false, reason, reason.getDefaultMessage());
    }

    /**
     * Returns the denial reason wrapped in an {@link Optional}.
     *
     * @return an {@link Optional} containing the {@link DenialReason} if denied, or empty if allowed
     */
    public Optional<DenialReason> getDenialReason() {
        return Optional.ofNullable(denialReason);
    }
}

