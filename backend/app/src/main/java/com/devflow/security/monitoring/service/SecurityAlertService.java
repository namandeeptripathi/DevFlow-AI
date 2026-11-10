package com.devflow.security.monitoring.service;

import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.audit.AuthorizationAuditEvent;
import com.devflow.modules.auth.audit.AuthorizationAuditEventType;
import com.devflow.modules.auth.audit.AuthorizationAuditRecorder;
import com.devflow.modules.auth.domain.RoleScope;
import com.devflow.security.monitoring.config.SecurityMonitoringProperties;
import com.devflow.security.monitoring.domain.SecurityAlertEntity;
import com.devflow.security.monitoring.domain.SecurityAlertStatus;
import com.devflow.security.monitoring.domain.SecurityEventEntity;
import com.devflow.security.monitoring.domain.SecuritySeverity;
import com.devflow.security.monitoring.dto.SecurityAlertResponse;
import com.devflow.security.monitoring.exception.InvalidSecurityOperationException;
import com.devflow.security.monitoring.exception.SecurityAlertNotFoundException;
import com.devflow.security.monitoring.repository.SecurityAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing the security alert lifecycle, deduplication, and investigation state transitions.
 */
@Service
public class SecurityAlertService {

    private static final Logger log = LoggerFactory.getLogger(SecurityAlertService.class);

    private final SecurityAlertRepository alertRepository;
    private final SecurityMonitoringProperties properties;
    private final AuthorizationAuditRecorder auditRecorder;

    public SecurityAlertService(
            SecurityAlertRepository alertRepository,
            SecurityMonitoringProperties properties,
            @Autowired(required = false) AuthorizationAuditRecorder auditRecorder
    ) {
        this.alertRepository = Objects.requireNonNull(alertRepository, "alertRepository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.auditRecorder = auditRecorder;
    }

    /**
     * Processes a detected security event, creating a new alert or deduplicating into an existing active alert.
     */
    @Transactional
    public SecurityAlertEntity processSecurityEvent(SecurityEventEntity event) {
        Objects.requireNonNull(event, "event must not be null");

        String orgId = event.getOrganizationId();
        String ruleId = event.getRuleId();
        String actorId = event.getActorId();
        Instant cooldownThreshold = Instant.now().minus(properties.getAlertCooldownMinutes(), ChronoUnit.MINUTES);

        Optional<SecurityAlertEntity> activeAlertOpt = alertRepository
                .findActiveAlertForDeduplication(orgId, ruleId, actorId, cooldownThreshold);

        if (activeAlertOpt.isPresent()) {
            SecurityAlertEntity existingAlert = activeAlertOpt.get();
            existingAlert.setOccurrenceCount(existingAlert.getOccurrenceCount() + 1);
            existingAlert.setLastSeenAt(event.getDetectedAt());
            existingAlert.setLatestEventId(event.getId());
            existingAlert.setUpdatedAt(Instant.now());

            log.info("Deduplicated security event [{}] into alert [{}] for rule [{}] (total occurrences: {})",
                    event.getId(), existingAlert.getId(), ruleId, existingAlert.getOccurrenceCount());
            return alertRepository.save(existingAlert);
        }

        SecurityAlertEntity newAlert = SecurityAlertEntity.builder()
                .id(UUID.randomUUID().toString())
                .organizationId(orgId)
                .ruleId(ruleId)
                .severity(event.getSeverity())
                .status(SecurityAlertStatus.OPEN)
                .title(event.getTitle())
                .description(event.getDescription())
                .actorId(actorId)
                .targetId(event.getTargetId())
                .firstSeenAt(event.getDetectedAt())
                .lastSeenAt(event.getDetectedAt())
                .occurrenceCount(1)
                .latestEventId(event.getId())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        log.warn("Created new security alert [{}] for tenant [{}] rule [{}] actor [{}] severity [{}]",
                newAlert.getId(), orgId, ruleId, actorId, newAlert.getSeverity());
        return alertRepository.save(newAlert);
    }

    /**
     * Acknowledges an open security alert for investigation.
     */
    @Transactional
    public SecurityAlertResponse acknowledgeAlert(UUID organizationId, UUID alertId, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(alertId, "alertId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        SecurityAlertEntity alert = alertRepository.findByOrganizationIdAndId(organizationId.toString(), alertId.toString())
                .orElseThrow(() -> new SecurityAlertNotFoundException(
                        "Security alert [" + alertId + "] not found in organization [" + organizationId + "]"));

        if (alert.getStatus() == SecurityAlertStatus.RESOLVED) {
            throw new InvalidSecurityOperationException("Cannot acknowledge an already resolved alert: " + alertId);
        }

        alert.setStatus(SecurityAlertStatus.ACKNOWLEDGED);
        alert.setAcknowledgedBy(callerUserId.toString());
        alert.setAcknowledgedAt(Instant.now());
        alert.setUpdatedAt(Instant.now());

        SecurityAlertEntity saved = alertRepository.save(alert);

        if (auditRecorder != null) {
            AuthorizationAuditEvent auditEvent = AuthorizationAuditEvent.builder()
                    .eventType(AuthorizationAuditEventType.SECURITY_ALERT_ACKNOWLEDGED)
                    .userId(callerUserId)
                    .scope(RoleScope.ORGANIZATION)
                    .organizationId(organizationId)
                    .targetId(alertId)
                    .targetType("SECURITY_ALERT")
                    .reason("Security alert acknowledged for investigation")
                    .decision(AuthorizationDecision.allow())
                    .build();
            auditRecorder.record(auditEvent);
        }

        log.info("Security alert [{}] acknowledged by user [{}] in organization [{}]", alertId, callerUserId, organizationId);
        return SecurityAlertResponse.fromEntity(saved);
    }

    /**
     * Resolves an open or acknowledged security alert with a required resolution rationale.
     */
    @Transactional
    public SecurityAlertResponse resolveAlert(UUID organizationId, UUID alertId, String resolutionReason, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(alertId, "alertId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        if (resolutionReason == null || resolutionReason.isBlank()) {
            throw new InvalidSecurityOperationException("Resolution reason is required to resolve a security alert");
        }

        SecurityAlertEntity alert = alertRepository.findByOrganizationIdAndId(organizationId.toString(), alertId.toString())
                .orElseThrow(() -> new SecurityAlertNotFoundException(
                        "Security alert [" + alertId + "] not found in organization [" + organizationId + "]"));

        alert.setStatus(SecurityAlertStatus.RESOLVED);
        alert.setResolvedBy(callerUserId.toString());
        alert.setResolvedAt(Instant.now());
        alert.setResolutionReason(resolutionReason.trim());
        alert.setUpdatedAt(Instant.now());

        SecurityAlertEntity saved = alertRepository.save(alert);

        if (auditRecorder != null) {
            AuthorizationAuditEvent auditEvent = AuthorizationAuditEvent.builder()
                    .eventType(AuthorizationAuditEventType.SECURITY_ALERT_RESOLVED)
                    .userId(callerUserId)
                    .scope(RoleScope.ORGANIZATION)
                    .organizationId(organizationId)
                    .targetId(alertId)
                    .targetType("SECURITY_ALERT")
                    .reason(resolutionReason.trim())
                    .decision(AuthorizationDecision.allow())
                    .build();
            auditRecorder.record(auditEvent);
        }

        log.info("Security alert [{}] resolved by user [{}] in organization [{}]: {}",
                alertId, callerUserId, organizationId, resolutionReason);
        return SecurityAlertResponse.fromEntity(saved);
    }

    /**
     * Lists and filters security alerts for an organization.
     */
    @Transactional(readOnly = true)
    public Page<SecurityAlertResponse> getAlerts(
            UUID organizationId,
            SecurityAlertStatus status,
            SecuritySeverity severity,
            Instant from,
            Instant to,
            Pageable pageable
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");

        String orgIdStr = organizationId.toString();
        Specification<SecurityAlertEntity> spec = (root, query, cb) -> {
            java.util.List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            predicates.add(cb.equal(root.get("organizationId"), orgIdStr));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (severity != null) {
                predicates.add(cb.equal(root.get("severity"), severity));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("lastSeenAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("lastSeenAt"), to));
            }
            query.orderBy(cb.desc(root.get("lastSeenAt")));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        return alertRepository.findAll(spec, pageable).map(SecurityAlertResponse::fromEntity);
    }

    /**
     * Retrieves an individual alert by ID within an organization scope.
     */
    @Transactional(readOnly = true)
    public SecurityAlertResponse getAlertById(UUID organizationId, UUID alertId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(alertId, "alertId must not be null");

        SecurityAlertEntity alert = alertRepository.findByOrganizationIdAndId(organizationId.toString(), alertId.toString())
                .orElseThrow(() -> new SecurityAlertNotFoundException(
                        "Security alert [" + alertId + "] not found in organization [" + organizationId + "]"));

        return SecurityAlertResponse.fromEntity(alert);
    }
}
