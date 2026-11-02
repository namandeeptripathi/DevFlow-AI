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
     * Requested permission is not registered in the canonical permission registry.
     */
    UNKNOWN_PERMISSION("Permission is not recognized or registered in canonical permission registry"),

    /**
     * Authorization scope mismatch between request and role or context (e.g. cross-scope escalation).
     */
    SCOPE_MISMATCH("Authorization scope mismatch between request and role or context"),

    /**
     * Platform support access is not linked to an active, verified support case for this tenant.
     */
    SUPPORT_CASE_UNAUTHORIZED("Platform support access is not linked to an active, verified support case for this tenant"),

    /**
     * Emergency access requires verified out-of-band approval within a 4-hour authorization window.
     */
    EMERGENCY_ACCESS_NOT_APPROVED("Emergency access requires verified out-of-band approval within a 4-hour window"),

    /**
     * Organization member requires an active project assignment or project role for project-scoped access.
     */
    PROJECT_ASSIGNMENT_REQUIRED("Organization member requires an active project assignment for project-scoped access"),

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

