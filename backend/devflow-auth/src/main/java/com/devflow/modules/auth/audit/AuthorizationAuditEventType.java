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
    PROJECT_ASSIGNMENT_DENIAL,

    // ── Canonical Role Lifecycle Events (§13, Stage 8) ────────────────────────

    /**
     * Canonical role assigned to a principal within an organization or project scope.
     */
    ROLE_GRANTED,

    /**
     * Canonical role revoked from a principal.
     */
    ROLE_REVOKED,

    /**
     * Time-bounded role assignment expired.
     */
    ROLE_ASSIGNMENT_EXPIRED,

    /**
     * Role assignment superseded by a new assignment.
     */
    ROLE_ASSIGNMENT_SUPERSEDED,

    /**
     * Role assignment rejected due to policy or governance violation.
     */
    ROLE_ASSIGNMENT_REJECTED,

    // ── Custom Role Lifecycle Events (§13, Stage 8) ───────────────────────────

    /**
     * Custom role created within an organization.
     */
    CUSTOM_ROLE_CREATED,

    /**
     * Custom role metadata or settings updated.
     */
    CUSTOM_ROLE_UPDATED,

    /**
     * Custom role deactivated/disabled.
     */
    CUSTOM_ROLE_DISABLED,

    /**
     * Custom role archived or permanently deleted.
     */
    CUSTOM_ROLE_DELETED,

    /**
     * Permission attached to a custom role.
     */
    CUSTOM_ROLE_PERMISSION_ADDED,

    /**
     * Permission detached from a custom role.
     */
    CUSTOM_ROLE_PERMISSION_REMOVED,

    // ── Membership & Governance Events (§13, Stage 8) ─────────────────────────

    /**
     * Organization membership role elevated or demoted.
     */
    MEMBERSHIP_ROLE_CHANGED,

    /**
     * Member removed from organization tenant.
     */
    MEMBERSHIP_REMOVED,

    /**
     * Unauthorized attempt to assign privileged role (e.g. OWNER or BILLING).
     */
    PRIVILEGED_ROLE_GRANT_ATTEMPTED,

    /**
     * Unauthorized attempt to delegate permissions the actor does not possess.
     */
    PERMISSION_DELEGATION_VIOLATION_ATTEMPTED,

    // ── Security Monitoring & Audit Governance Events (Stage 8) ───────────────

    /**
     * Security alert acknowledged by an administrator.
     */
    SECURITY_ALERT_ACKNOWLEDGED,

    /**
     * Security alert resolved by an administrator.
     */
    SECURITY_ALERT_RESOLVED,

    /**
     * Cryptographic audit integrity verification executed over a tenant scope.
     */
    AUDIT_INTEGRITY_VERIFIED
}

