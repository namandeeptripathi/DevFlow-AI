package com.devflow.security.monitoring.rule;

import com.devflow.modules.auth.audit.AuthorizationAuditEvent;
import com.devflow.security.monitoring.domain.SecurityEventEntity;
import com.devflow.security.monitoring.domain.SecuritySeverity;

import java.util.Optional;

/**
 * SPI contract for explainable, deterministic security event detection rules.
 */
public interface SecurityDetectionRule {

    /**
     * Unique identifier for this detection rule (e.g. AUTHZ_REPEATED_DENIALS).
     */
    String getRuleId();

    /**
     * Human-readable name of this detection rule.
     */
    String getRuleName();

    /**
     * Severity classification of events triggered by this rule.
     */
    SecuritySeverity getSeverity();

    /**
     * Evaluates an incoming audit event against this rule's thresholds and sliding windows.
     *
     * @param event the audit event
     * @return Optional containing the detected security event if threshold exceeded, empty otherwise
     */
    Optional<SecurityEventEntity> evaluate(AuthorizationAuditEvent event);
}
