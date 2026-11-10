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
 * Detects repeated unauthorized attempts to grant privileged roles (e.g. OWNER or BILLING).
 */
@Component
public class UnauthorizedPrivilegedRoleGrantRule implements SecurityDetectionRule {

    public static final String RULE_ID = "PRIVILEGE_ESCALATION_ROLE_GRANT";
    public static final String RULE_NAME = "Unauthorized Privileged Role Grant Attempt";

    private final AuthorizationAuditEventRepository auditEventRepository;
    private final SecurityMonitoringProperties properties;

    public UnauthorizedPrivilegedRoleGrantRule(
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
        if (event == null || event.eventType() != AuthorizationAuditEventType.PRIVILEGED_ROLE_GRANT_ATTEMPTED || event.getOrganizationId().isEmpty()) {
            return Optional.empty();
        }

        String orgId = event.getOrganizationId().get().toString();
        String userId = event.userId().toString();
        Instant since = Instant.now().minus(properties.getPrivilegedGrantWindowMinutes(), ChronoUnit.MINUTES);

        long attemptCount = auditEventRepository.countRecentPrivilegedRoleGrantAttempts(orgId, userId, since);
        if (attemptCount >= properties.getPrivilegedGrantThreshold()) {
            List<String> evidence = auditEventRepository.findRecentEvidenceEventIds(
                    orgId, userId, AuthorizationAuditEventType.PRIVILEGED_ROLE_GRANT_ATTEMPTED.name(), since, PageRequest.of(0, 10));

            SecurityEventEntity securityEvent = SecurityEventEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .organizationId(orgId)
                    .ruleId(RULE_ID)
                    .ruleName(RULE_NAME)
                    .severity(getSeverity())
                    .title("Privilege Escalation: Unauthorized Privileged Role Grant Attempts")
                    .description(String.format("Principal [%s] made %d unauthorized attempts to grant privileged roles within %d minutes",
                            userId, attemptCount, properties.getPrivilegedGrantWindowMinutes()))
                    .actorId(userId)
                    .targetId(event.getTargetId().map(UUID::toString).orElse(null))
                    .evidenceEventIds(String.join(",", evidence))
                    .metadata(String.format("{\"count\":%d,\"threshold\":%d,\"windowMinutes\":%d}",
                            attemptCount, properties.getPrivilegedGrantThreshold(), properties.getPrivilegedGrantWindowMinutes()))
                    .detectedAt(Instant.now())
                    .build();

            return Optional.of(securityEvent);
        }

        return Optional.empty();
    }
}
