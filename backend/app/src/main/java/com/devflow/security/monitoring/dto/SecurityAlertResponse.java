package com.devflow.security.monitoring.dto;

import com.devflow.security.monitoring.domain.SecurityAlertEntity;
import com.devflow.security.monitoring.domain.SecurityAlertStatus;
import com.devflow.security.monitoring.domain.SecuritySeverity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * REST response representing a security alert.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityAlertResponse {

    private String id;
    private String organizationId;
    private String ruleId;
    private SecuritySeverity severity;
    private SecurityAlertStatus status;
    private String title;
    private String description;
    private String actorId;
    private String targetId;
    private Instant firstSeenAt;
    private Instant lastSeenAt;
    private Integer occurrenceCount;
    private String latestEventId;
    private String acknowledgedBy;
    private Instant acknowledgedAt;
    private String resolvedBy;
    private Instant resolvedAt;
    private String resolutionReason;
    private Instant createdAt;
    private Instant updatedAt;

    public static SecurityAlertResponse fromEntity(SecurityAlertEntity entity) {
        if (entity == null) {
            return null;
        }
        return SecurityAlertResponse.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganizationId())
                .ruleId(entity.getRuleId())
                .severity(entity.getSeverity())
                .status(entity.getStatus())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .actorId(entity.getActorId())
                .targetId(entity.getTargetId())
                .firstSeenAt(entity.getFirstSeenAt())
                .lastSeenAt(entity.getLastSeenAt())
                .occurrenceCount(entity.getOccurrenceCount())
                .latestEventId(entity.getLatestEventId())
                .acknowledgedBy(entity.getAcknowledgedBy())
                .acknowledgedAt(entity.getAcknowledgedAt())
                .resolvedBy(entity.getResolvedBy())
                .resolvedAt(entity.getResolvedAt())
                .resolutionReason(entity.getResolutionReason())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
