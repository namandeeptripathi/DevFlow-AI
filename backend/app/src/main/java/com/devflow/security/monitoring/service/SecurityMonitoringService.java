package com.devflow.security.monitoring.service;

import com.devflow.audit.AuthorizationAuditApplicationEvent;
import com.devflow.modules.auth.audit.AuthorizationAuditEvent;
import com.devflow.security.monitoring.domain.SecurityEventEntity;
import com.devflow.security.monitoring.domain.SecuritySeverity;
import com.devflow.security.monitoring.dto.SecurityEventResponse;
import com.devflow.security.monitoring.exception.SecurityEventNotFoundException;
import com.devflow.security.monitoring.repository.SecurityEventRepository;
import com.devflow.security.monitoring.rule.SecurityDetectionRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service orchestrating explainable security event detection and ingestion from authorization audit events.
 *
 * <p>Receives asynchronous {@link AuthorizationAuditApplicationEvent} instances, evaluates them
 * against active {@link SecurityDetectionRule} definitions, and generates security alerts.
 */
@Service
public class SecurityMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(SecurityMonitoringService.class);

    private final List<SecurityDetectionRule> detectionRules;
    private final SecurityEventRepository eventRepository;
    private final SecurityAlertService alertService;

    public SecurityMonitoringService(
            List<SecurityDetectionRule> detectionRules,
            SecurityEventRepository eventRepository,
            SecurityAlertService alertService
    ) {
        this.detectionRules = Objects.requireNonNull(detectionRules, "detectionRules must not be null");
        this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository must not be null");
        this.alertService = Objects.requireNonNull(alertService, "alertService must not be null");
    }

    /**
     * Listens for published authorization audit events and evaluates detection rules.
     */
    @EventListener
    @Transactional
    public void onAuthorizationAuditEvent(AuthorizationAuditApplicationEvent applicationEvent) {
        if (applicationEvent == null || applicationEvent.getAuditEvent() == null) {
            return;
        }

        AuthorizationAuditEvent auditEvent = applicationEvent.getAuditEvent();

        for (SecurityDetectionRule rule : detectionRules) {
            try {
                Optional<SecurityEventEntity> detectedOpt = rule.evaluate(auditEvent);
                if (detectedOpt.isPresent()) {
                    SecurityEventEntity detected = detectedOpt.get();
                    SecurityEventEntity saved = eventRepository.save(detected);
                    log.warn("SECURITY_EVENT_TRIGGERED: rule=[{}] title=[{}] org=[{}] actor=[{}]",
                            saved.getRuleId(), saved.getTitle(), saved.getOrganizationId(), saved.getActorId());
                    alertService.processSecurityEvent(saved);
                }
            } catch (Exception e) {
                log.error("Error evaluating security rule [{}] on event [{}]: {}",
                        rule.getRuleId(), auditEvent.eventId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Lists and filters security events for an organization.
     */
    @Transactional(readOnly = true)
    public Page<SecurityEventResponse> getSecurityEvents(
            UUID organizationId,
            String ruleId,
            SecuritySeverity severity,
            Instant from,
            Instant to,
            Pageable pageable
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");

        return eventRepository.findTenantSecurityEvents(
                organizationId.toString(), ruleId, severity, from, to, pageable)
                .map(SecurityEventResponse::fromEntity);
    }

    /**
     * Retrieves an individual security event by ID within an organization boundary.
     */
    @Transactional(readOnly = true)
    public SecurityEventResponse getSecurityEventById(UUID organizationId, String eventId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");

        SecurityEventEntity event = eventRepository.findByOrganizationIdAndId(organizationId.toString(), eventId)
                .orElseThrow(() -> new SecurityEventNotFoundException(
                        "Security event [" + eventId + "] not found in organization [" + organizationId + "]"));

        return SecurityEventResponse.fromEntity(event);
    }
}
