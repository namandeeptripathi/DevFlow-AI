package com.devflow.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Persistent append-only audit event record capturing authorization decisions and
 * security-sensitive administrative operations.
 *
 * <p>Protected by database-level triggers and cryptographic hash chaining against tampering.
 */
@Entity
@Table(name = "authorization_audit_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthorizationAuditEventEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "sequence_number", nullable = false)
    private Long sequenceNumber;

    @Column(name = "event_id", length = 36, nullable = false, unique = true)
    private String eventId;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "event_type", length = 60, nullable = false)
    private String eventType;

    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(name = "scope", length = 30, nullable = false)
    private String scope;

    @Column(name = "organization_id", length = 36)
    private String organizationId;

    @Column(name = "project_id", length = 36)
    private String projectId;

    @Column(name = "resource_type", length = 100)
    private String resourceType;

    @Column(name = "resource_id", length = 100)
    private String resourceId;

    @Column(name = "requested_permissions", columnDefinition = "TEXT")
    private String requestedPermissions;

    @Column(name = "decision", length = 20, nullable = false)
    private String decision;

    @Column(name = "denial_reason", length = 60)
    private String denialReason;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "target_id", length = 36)
    private String targetId;

    @Column(name = "target_type", length = 50)
    private String targetType;

    @Column(name = "previous_state", columnDefinition = "TEXT")
    private String previousState;

    @Column(name = "new_state", columnDefinition = "TEXT")
    private String newState;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "record_hash", length = 64, nullable = false)
    private String recordHash;

    @Column(name = "previous_record_hash", length = 64, nullable = false)
    private String previousRecordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
