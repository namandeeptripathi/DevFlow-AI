package com.devflow.modules.auth.api;

/**
 * Classification of reasons for authorization denial, aligned with the DevFlow Authorization Model.
 *
 * @see AuthorizationDecision
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §10.1</a>
 */
public enum DenialReason {

    /**
     * Tenant boundary violation: target resource does not belong to the principal's active organization.
     */
    TENANT_MISMATCH("Tenant boundary violation: resource does not belong to active organization"),

    /**
     * Principal does not hold an active organization membership (e.g. pending invitation, suspended, revoked).
     */
    MEMBERSHIP_INACTIVE("Principal membership is missing, revoked, or inactive"),

    /**
     * Principal's resolved roles do not grant the required permission.
     */
    INSUFFICIENT_PERMISSIONS("Principal roles do not grant the required permission"),

    /**
     * The requested operation is owner-gated and the requesting principal is not the resource owner.
     */
    OWNERSHIP_REQUIRED("Operation requires resource ownership"),

    /**
     * The target resource is in a lifecycle state (e.g., archived, locked) that blocks the operation.
     */
    RESOURCE_STATE_CONFLICT("Resource state prohibits the requested operation"),

    /**
     * General or unspecified authorization rejection.
     */
    GENERAL_DENIED("Access denied");

    private final String defaultMessage;

    DenialReason(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    /**
     * Gets the default descriptive diagnostic message for this denial reason.
     *
     * @return default explanation string
     */
    public String getDefaultMessage() {
        return defaultMessage;
    }
}

