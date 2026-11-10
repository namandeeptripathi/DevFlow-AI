package com.devflow.security.monitoring.rule;

import com.devflow.audit.repository.AuthorizationAuditEventRepository;
import com.devflow.modules.auth.audit.AuthorizationAuditEvent;
import com.devflow.modules.auth.audit.AuthorizationAuditEventType;
import com.devflow.security.monitoring.config.SecurityMonitoringProperties;
import com.devflow.security.monitoring.domain.SecurityEventEntity;
import com.devflow.security.monitoring.domain.SecuritySeverity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Detects rapid bulk role or permission modifications by a single actor within a short timeframe.
 */
@Component
public class RapidBulkRoleModificationsRule implements SecurityDetectionRule {

    public static final String RULE_ID = "ADMIN_ANOMALY_BULK_ROLE_MODIFICATIONS";
    public static final String RULE_NAME = "Rapid Bulk Role Modifications";

    private static final Set<AuthorizationAuditEventType> ADMINISTRATIVE_MUTATION_TYPES = Set.of(
            AuthorizationAuditEventType.ROLE_GRANTED,
            AuthorizationAuditEventType.ROLE_REVOKED,
            AuthorizationAuditEventType.CUSTOM_ROLE_CREATED,
            AuthorizationAuditEventType.CUSTOM_ROLE_UPDATED,
            AuthorizationAuditEventType.CUSTOM_ROLE_DISABLED,
            AuthorizationAuditEventType.CUSTOM_ROLE_DELETED
    );

    private final AuthorizationAuditEventRepository auditEventRepository;
    private final SecurityMonitoringProperties properties;

    public RapidBulkRoleModificationsRule(
            AuthorizationAuditEventRepository auditEventRepository,
            SecurityMonitoringProperties properties
    ) {
        this.auditEventRepository = Objects.requireNonNull(auditEventRepository, "auditEventRepository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public String getRuleName() {
        return RULE_NAME;
    }

    @Override
    public SecuritySeverity getSeverity() {
        return SecuritySeverity.MEDIUM;
    }

    @Override
    public Optional<SecurityEventEntity> evaluate(AuthorizationAuditEvent event) {
        if (event == null || !ADMINISTRATIVE_MUTATION_TYPES.contains(event.eventType()) || event.getOrganizationId().isEmpty()) {
            return Optional.empty();
        }

        String orgId = event.getOrganizationId().get().toString();
        String userId = event.userId().toString();
        Instant since = Instant.now().minus(properties.getBulkRoleModificationWindowMinutes(), ChronoUnit.MINUTES);

        long modificationCount = auditEventRepository.countRecentAdministrativeModifications(orgId, userId, since);
        if (modificationCount >= properties.getBulkRoleModificationThreshold()) {
            List<String> evidence = auditEventRepository.findRecentEvidenceEventIds(
                    orgId, userId, null, since, PageRequest.of(0, 10));

            SecurityEventEntity securityEvent = SecurityEventEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .organizationId(orgId)
                    .ruleId(RULE_ID)
                    .ruleName(RULE_NAME)
                    .severity(getSeverity())
                    .title("Administrative Anomaly: Rapid Bulk Role Modifications")
                    .description(String.format("Actor [%s] executed %d role modifications within %d minutes",
                            userId, modificationCount, properties.getBulkRoleModificationWindowMinutes()))
                    .actorId(userId)
                    .evidenceEventIds(String.join(",", evidence))
                    .metadata(String.format("{\"count\":%d,\"threshold\":%d,\"windowMinutes\":%d}",
                            modificationCount, properties.getBulkRoleModificationThreshold(), properties.getBulkRoleModificationWindowMinutes()))
                    .detectedAt(Instant.now())
                    .build();

            return Optional.of(securityEvent);
        }

        return Optional.empty();
    }
}
