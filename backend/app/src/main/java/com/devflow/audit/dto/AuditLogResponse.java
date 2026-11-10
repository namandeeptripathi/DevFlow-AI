package com.devflow.audit.dto;

import com.devflow.audit.domain.AuthorizationAuditEventEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Tenant-facing representation of an immutable authorization audit event.
 *
 * <p>Never exposes sensitive credentials, secret tokens, or raw internal database details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponse {

    private String id;
    private Long sequenceNumber;
    private String eventId;
    private Instant timestamp;
    private String eventType;
    private String userId;
    private String scope;
    private String organizationId;
    private String projectId;
    private String resourceType;
    private String resourceId;
    private String requestedPermissions;
    private String decision;
    private String denialReason;
    private String correlationId;
    private String targetId;
    private String targetType;
    private String previousState;
    private String newState;
    private String reason;
    private String recordHash;
    private String previousRecordHash;

    public static AuditLogResponse fromEntity(AuthorizationAuditEventEntity entity) {
        if (entity == null) {
            return null;
        }
        return AuditLogResponse.builder()
                .id(entity.getId())
                .sequenceNumber(entity.getSequenceNumber())
                .eventId(entity.getEventId())
                .timestamp(entity.getTimestamp())
                .eventType(entity.getEventType())
                .userId(entity.getUserId())
                .scope(entity.getScope())
                .organizationId(entity.getOrganizationId())
                .projectId(entity.getProjectId())
                .resourceType(entity.getResourceType())
                .resourceId(entity.getResourceId())
                .requestedPermissions(entity.getRequestedPermissions())
                .decision(entity.getDecision())
                .denialReason(entity.getDenialReason())
                .correlationId(entity.getCorrelationId())
                .targetId(entity.getTargetId())
                .targetType(entity.getTargetType())
                .previousState(entity.getPreviousState())
                .newState(entity.getNewState())
                .reason(entity.getReason())
                .recordHash(entity.getRecordHash())
                .previousRecordHash(entity.getPreviousRecordHash())
                .build();
    }
}
