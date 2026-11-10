package com.devflow.security.monitoring.rule;

import com.devflow.audit.repository.AuthorizationAuditEventRepository;
import com.devflow.modules.auth.audit.AuthorizationAuditEvent;
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
 * Detects repeated denied authorization attempts by a single principal within an organization.
 */
@Component
public class RepeatedAuthorizationDenialsRule implements SecurityDetectionRule {

    public static final String RULE_ID = "AUTHZ_REPEATED_DENIALS";
    public static final String RULE_NAME = "Repeated Authorization Denials";

    private final AuthorizationAuditEventRepository auditEventRepository;
    private final SecurityMonitoringProperties properties;

    public RepeatedAuthorizationDenialsRule(
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
        if (event == null || event.decision().allowed() || event.getOrganizationId().isEmpty()) {
            return Optional.empty();
        }

        String orgId = event.getOrganizationId().get().toString();
        String userId = event.userId().toString();
        Instant since = Instant.now().minus(properties.getRepeatedDenialsWindowMinutes(), ChronoUnit.MINUTES);

        long denialCount = auditEventRepository.countRecentDenials(orgId, userId, since);
        if (denialCount >= properties.getRepeatedDenialsThreshold()) {
            List<String> evidence = auditEventRepository.findRecentEvidenceEventIds(
                    orgId, userId, null, since, PageRequest.of(0, 10));

            SecurityEventEntity securityEvent = SecurityEventEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .organizationId(orgId)
                    .ruleId(RULE_ID)
                    .ruleName(RULE_NAME)
                    .severity(getSeverity())
                    .title("Repeated Authorization Denials Detected")
                    .description(String.format("Principal [%s] accumulated %d authorization denials within %d minutes",
                            userId, denialCount, properties.getRepeatedDenialsWindowMinutes()))
                    .actorId(userId)
                    .evidenceEventIds(String.join(",", evidence))
                    .metadata(String.format("{\"count\":%d,\"threshold\":%d,\"windowMinutes\":%d}",
                            denialCount, properties.getRepeatedDenialsThreshold(), properties.getRepeatedDenialsWindowMinutes()))
                    .detectedAt(Instant.now())
                    .build();

            return Optional.of(securityEvent);
        }

        return Optional.empty();
    }
}
