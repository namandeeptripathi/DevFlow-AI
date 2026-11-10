package com.devflow.security.monitoring.dto;

import com.devflow.security.monitoring.domain.SecurityEventEntity;
import com.devflow.security.monitoring.domain.SecuritySeverity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * REST response representing a detected security event.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityEventResponse {

    private String id;
    private String organizationId;
    private String ruleId;
    private String ruleName;
    private SecuritySeverity severity;
    private String title;
    private String description;
    private String actorId;
    private String targetId;
    private String evidenceEventIds;
    private String metadata;
    private Instant detectedAt;

    public static SecurityEventResponse fromEntity(SecurityEventEntity entity) {
        if (entity == null) {
            return null;
        }
        return SecurityEventResponse.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganizationId())
                .ruleId(entity.getRuleId())
                .ruleName(entity.getRuleName())
                .severity(entity.getSeverity())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .actorId(entity.getActorId())
                .targetId(entity.getTargetId())
                .evidenceEventIds(entity.getEvidenceEventIds())
                .metadata(entity.getMetadata())
                .detectedAt(entity.getDetectedAt())
                .build();
    }
}
