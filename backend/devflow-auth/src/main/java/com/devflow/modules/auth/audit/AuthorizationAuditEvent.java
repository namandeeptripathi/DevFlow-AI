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
 * Immutable audit event capturing the complete context of an authorization evaluation decision
 * or a security-sensitive role, permission, or governance administrative action.
 *
 * <p>Preserves a security audit record including actor, scope, target resources, permissions,
 * outcome, and rationale. Does not log passwords, JWTs, credentials, or sensitive attributes (§13).
 *
 * @param eventId              unique identifier for this audit event
 * @param timestamp            instant when the evaluation or operation occurred
 * @param eventType            structured audit event classification
 * @param userId               UUID of the principal requesting authorization or performing the administrative action
 * @param scope                authorization scope of the request
 * @param organizationId       tenant organization identifier (if tenant-scoped)
 * @param projectId            project identifier (if project-scoped)
 * @param resourceType         governed domain resource type
 * @param resourceId           sub-resource identifier (if targeted)
 * @param requestedPermissions set of permissions evaluated or affected
 * @param decision             resulting authorization decision or operation outcome
 * @param denialReason         structured classification of denial if access was rejected
 * @param correlationId        correlation or HTTP request trace identifier
 * @param targetId             target entity identifier (e.g. user receiving role, or custom role ID)
 * @param targetType           target entity type (e.g. "USER", "ROLE", "CUSTOM_ROLE", "ORGANIZATION")
 * @param previousState        serialized previous state representation (where applicable)
 * @param newState             serialized new state representation (where applicable)
 * @param reason               administrative rationale or justification
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
        DenialReason denialReason,
        String correlationId,
        UUID targetId,
        String targetType,
        String previousState,
        String newState,
        String reason
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

    /**
     * Backward-compatible constructor for evaluation decision audit events.
     */
    public AuthorizationAuditEvent(
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
    ) {
        this(
                eventId,
                timestamp,
                eventType,
                userId,
                scope,
                organizationId,
                projectId,
                resourceType,
                resourceId,
                requestedPermissions,
                decision,
                denialReason,
                null,
                null,
                null,
                null,
                null,
                null
        );
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

    public Optional<String> getCorrelationId() {
        return Optional.ofNullable(correlationId);
    }

    public Optional<UUID> getTargetId() {
        return Optional.ofNullable(targetId);
    }

    public Optional<String> getTargetType() {
        return Optional.ofNullable(targetType);
    }

    public Optional<String> getPreviousState() {
        return Optional.ofNullable(previousState);
    }

    public Optional<String> getNewState() {
        return Optional.ofNullable(newState);
    }

    public Optional<String> getReason() {
        return Optional.ofNullable(reason);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID eventId = UUID.randomUUID();
        private Instant timestamp = Instant.now();
        private AuthorizationAuditEventType eventType;
        private UUID userId;
        private RoleScope scope = RoleScope.ORGANIZATION;
        private UUID organizationId;
        private UUID projectId;
        private String resourceType;
        private String resourceId;
        private Set<Permission> requestedPermissions = Collections.emptySet();
        private AuthorizationDecision decision = AuthorizationDecision.allow();
        private DenialReason denialReason;
        private String correlationId;
        private UUID targetId;
        private String targetType;
        private String previousState;
        private String newState;
        private String reason;

        public Builder eventId(UUID eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder eventType(AuthorizationAuditEventType eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder userId(UUID userId) {
            this.userId = userId;
            return this;
        }

        public Builder scope(RoleScope scope) {
            this.scope = scope;
            return this;
        }

        public Builder organizationId(UUID organizationId) {
            this.organizationId = organizationId;
            return this;
        }

        public Builder projectId(UUID projectId) {
            this.projectId = projectId;
            return this;
        }

        public Builder resourceType(String resourceType) {
            this.resourceType = resourceType;
            return this;
        }

        public Builder resourceId(String resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public Builder requestedPermissions(Set<Permission> requestedPermissions) {
            this.requestedPermissions = requestedPermissions;
            return this;
        }

        public Builder decision(AuthorizationDecision decision) {
            this.decision = decision;
            return this;
        }

        public Builder denialReason(DenialReason denialReason) {
            this.denialReason = denialReason;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder targetId(UUID targetId) {
            this.targetId = targetId;
            return this;
        }

        public Builder targetType(String targetType) {
            this.targetType = targetType;
            return this;
        }

        public Builder previousState(String previousState) {
            this.previousState = previousState;
            return this;
        }

        public Builder newState(String newState) {
            this.newState = newState;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public AuthorizationAuditEvent build() {
            return new AuthorizationAuditEvent(
                    eventId,
                    timestamp,
                    eventType,
                    userId,
                    scope,
                    organizationId,
                    projectId,
                    resourceType,
                    resourceId,
                    requestedPermissions,
                    decision,
                    denialReason,
                    correlationId,
                    targetId,
                    targetType,
                    previousState,
                    newState,
                    reason
            );
        }
    }
}
