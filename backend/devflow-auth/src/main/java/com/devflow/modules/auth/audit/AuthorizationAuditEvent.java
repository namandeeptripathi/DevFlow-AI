package com.devflow.modules.auth.audit;

import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.DenialReason;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.RoleScope;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable audit event capturing the complete context of an authorization evaluation decision.
 *
 * <p>Preserves a security audit record including actor, scope, target resources, permissions,
 * outcome, and rationale. Does not log passwords, JWTs, credentials, or sensitive attributes (§13).
 *
 * @param eventId              unique identifier for this audit event
 * @param timestamp            instant when the evaluation occurred
 * @param eventType            structured audit event classification
 * @param userId               UUID of the principal requesting authorization
 * @param scope                authorization scope of the request
 * @param organizationId       tenant organization identifier (if tenant-scoped)
 * @param projectId            project identifier (if project-scoped)
 * @param resourceType         governed domain resource type
 * @param resourceId           sub-resource identifier (if targeted)
 * @param requestedPermissions set of permissions evaluated
 * @param decision             resulting authorization decision
 * @param denialReason         structured classification of denial if access was rejected
 *
 * @see AuthorizationAuditEventType
 * @see AuthorizationAuditRecorder
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §13</a>
 */
public record AuthorizationAuditEvent(
        UUID eventId,
        Instant timestamp,
        AuthorizationAuditEventType eventType,
        UUID userId,
        RoleScope scope,
        UUID organizationId,
        UUID projectId,
        String resourceType,
        String resourceId,
        Set<Permission> requestedPermissions,
        AuthorizationDecision decision,
        DenialReason denialReason
) implements Serializable {

    public AuthorizationAuditEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(decision, "decision must not be null");

        requestedPermissions = requestedPermissions == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(requestedPermissions);
    }

    public Optional<UUID> getOrganizationId() {
        return Optional.ofNullable(organizationId);
    }

    public Optional<UUID> getProjectId() {
        return Optional.ofNullable(projectId);
    }

    public Optional<String> getResourceType() {
        return Optional.ofNullable(resourceType);
    }

    public Optional<String> getResourceId() {
        return Optional.ofNullable(resourceId);
    }

    public Optional<DenialReason> getDenialReason() {
        return Optional.ofNullable(denialReason);
    }
}

