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
import java.util.UUID;

/**
 * Detects repeated attempts by a principal to access resources across tenant boundaries.
 */
@Component
public class CrossTenantAccessViolationRule implements SecurityDetectionRule {

    public static final String RULE_ID = "AUTHZ_CROSS_TENANT_VIOLATION";
    public static final String RULE_NAME = "Cross-Tenant Access Boundary Violation";

    private final AuthorizationAuditEventRepository auditEventRepository;
    private final SecurityMonitoringProperties properties;

    public CrossTenantAccessViolationRule(
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
        return SecuritySeverity.HIGH;
    }

    @Override
    public Optional<SecurityEventEntity> evaluate(AuthorizationAuditEvent event) {
        if (event == null || event.eventType() != AuthorizationAuditEventType.TENANT_BOUNDARY_VIOLATION || event.getOrganizationId().isEmpty()) {
            return Optional.empty();
        }

        String orgId = event.getOrganizationId().get().toString();
        String userId = event.userId().toString();
        Instant since = Instant.now().minus(properties.getCrossTenantWindowMinutes(), ChronoUnit.MINUTES);

        long violationCount = auditEventRepository.countRecentTenantBoundaryViolations(userId, since);
        if (violationCount >= properties.getCrossTenantThreshold()) {
            List<String> evidence = auditEventRepository.findRecentEvidenceEventIds(
                    orgId, userId, AuthorizationAuditEventType.TENANT_BOUNDARY_VIOLATION.name(), since, PageRequest.of(0, 10));

            SecurityEventEntity securityEvent = SecurityEventEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .organizationId(orgId)
                    .ruleId(RULE_ID)
                    .ruleName(RULE_NAME)
                    .severity(getSeverity())
                    .title("Cross-Tenant Access Boundary Violations Detected")
                    .description(String.format("Principal [%s] attempted %d cross-tenant boundary violations within %d minutes",
                            userId, violationCount, properties.getCrossTenantWindowMinutes()))
                    .actorId(userId)
                    .evidenceEventIds(String.join(",", evidence))
                    .metadata(String.format("{\"count\":%d,\"threshold\":%d,\"windowMinutes\":%d}",
                            violationCount, properties.getCrossTenantThreshold(), properties.getCrossTenantWindowMinutes()))
                    .detectedAt(Instant.now())
                    .build();

            return Optional.of(securityEvent);
        }

        return Optional.empty();
    }
}
