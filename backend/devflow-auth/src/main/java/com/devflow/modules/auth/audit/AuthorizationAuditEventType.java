package com.devflow.modules.auth.audit;

/**
 * Categorization of security audit event types emitted during authorization evaluation.
 *
 * @see AuthorizationAuditEvent
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §13</a>
 */
public enum AuthorizationAuditEventType {

    /**
     * Requested action permitted after all policy gates passed.
     */
    AUTHORIZATION_ALLOWED,

    /**
     * Request rejected due to insufficient role permissions or missing grants.
     */
    AUTHORIZATION_DENIED,

    /**
     * Cross-tenant access attempt or organization boundary violation detected.
     */
    TENANT_BOUNDARY_VIOLATION,

    /**
     * Request rejected because the principal does not hold an active membership.
     */
    MEMBERSHIP_DENIAL,

    /**
     * Request rejected because an owner-gated operation was attempted by a non-owner.
     */
    OWNERSHIP_DENIAL,

    /**
     * Request rejected because the resource lifecycle state prohibits the operation.
     */
    RESOURCE_STATE_DENIAL,

    /**
     * Request rejected because the requested permission is not in the canonical registry.
     */
    UNKNOWN_PERMISSION_DENIAL,

    /**
     * Request rejected due to a mismatch between assigned role scopes and the requested context scope.
     */
    SCOPE_MISMATCH_DENIAL,

    /**
     * Platform support access granted after valid case and tenant verification.
     */
    SUPPORT_ACCESS_ALLOWED,

    /**
     * Platform support access rejected due to missing or invalid support case verification.
     */
    SUPPORT_ACCESS_DENIED,

    /**
     * Emergency access executed under verified out-of-band approval within the 4-hour window.
     */
    EMERGENCY_ACCESS_ALLOWED,

    /**
     * Emergency access rejected due to unverified approval, window expiry, or missing validator.
     */
    EMERGENCY_ACCESS_DENIED,

    /**
     * Organization member denied project access due to absence of project role or assignment.
     */
    PROJECT_ASSIGNMENT_DENIAL
}

