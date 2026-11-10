package com.devflow.security.monitoring;

import com.devflow.audit.repository.AuthorizationAuditEventRepository;
import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.DenialReason;
import com.devflow.modules.auth.audit.AuthorizationAuditEvent;
import com.devflow.modules.auth.audit.AuthorizationAuditEventType;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.RoleScope;
import com.devflow.security.monitoring.config.SecurityMonitoringProperties;
import com.devflow.security.monitoring.domain.SecurityAlertEntity;
import com.devflow.security.monitoring.domain.SecurityAlertStatus;
import com.devflow.security.monitoring.domain.SecurityEventEntity;
import com.devflow.security.monitoring.domain.SecuritySeverity;
import com.devflow.security.monitoring.dto.SecurityAlertResponse;
import com.devflow.security.monitoring.exception.InvalidSecurityOperationException;
import com.devflow.security.monitoring.repository.SecurityAlertRepository;
import com.devflow.security.monitoring.rule.CrossTenantAccessViolationRule;
import com.devflow.security.monitoring.rule.ProjectScopeViolationRule;
import com.devflow.security.monitoring.rule.RapidBulkRoleModificationsRule;
import com.devflow.security.monitoring.rule.RepeatedAuthorizationDenialsRule;
import com.devflow.security.monitoring.rule.UnauthorizedPermissionDelegationRule;
import com.devflow.security.monitoring.rule.UnauthorizedPrivilegedRoleGrantRule;
import com.devflow.security.monitoring.service.SecurityAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Stage 8: Suspicious Activity Detection Engine & Alert Lifecycle Tests")
class SecurityMonitoringDetectionTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private AuthorizationAuditEventRepository auditEventRepository;

    @Mock
    private SecurityAlertRepository alertRepository;

    private SecurityMonitoringProperties properties;
    private SecurityAlertService alertService;

    @BeforeEach
    void setUp() {
        properties = new SecurityMonitoringProperties();
        alertService = new SecurityAlertService(alertRepository, properties, null);
    }

    @Test
    @DisplayName("Repeated authorization denials rule triggers when threshold is reached")
    void shouldTriggerRepeatedAuthorizationDenialsRule() {
        RepeatedAuthorizationDenialsRule rule = new RepeatedAuthorizationDenialsRule(auditEventRepository, properties);

        when(auditEventRepository.countRecentDenials(eq(ORG_ID.toString()), eq(USER_ID.toString()), any(Instant.class)))
                .thenReturn(5L);
        when(auditEventRepository.findRecentEvidenceEventIds(eq(ORG_ID.toString()), eq(USER_ID.toString()), any(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of("event-1", "event-2", "event-3", "event-4", "event-5"));

        AuthorizationAuditEvent event = AuthorizationAuditEvent.builder()
                .eventType(AuthorizationAuditEventType.AUTHORIZATION_DENIED)
                .userId(USER_ID)
                .organizationId(ORG_ID)
                .scope(RoleScope.ORGANIZATION)
                .decision(AuthorizationDecision.deny(DenialReason.INSUFFICIENT_PERMISSIONS, "Denied"))
                .build();

        Optional<SecurityEventEntity> detectedOpt = rule.evaluate(event);

        assertThat(detectedOpt).isPresent();
        SecurityEventEntity detected = detectedOpt.get();
        assertThat(detected.getRuleId()).isEqualTo(RepeatedAuthorizationDenialsRule.RULE_ID);
        assertThat(detected.getSeverity()).isEqualTo(SecuritySeverity.MEDIUM);
        assertThat(detected.getActorId()).isEqualTo(USER_ID.toString());
        assertThat(detected.getEvidenceEventIds()).contains("event-1", "event-5");
    }

    @Test
    @DisplayName("Repeated authorization denials rule does NOT trigger when below threshold")
    void shouldNotTriggerRepeatedAuthorizationDenialsWhenBelowThreshold() {
        RepeatedAuthorizationDenialsRule rule = new RepeatedAuthorizationDenialsRule(auditEventRepository, properties);

        when(auditEventRepository.countRecentDenials(eq(ORG_ID.toString()), eq(USER_ID.toString()), any(Instant.class)))
                .thenReturn(2L);

        AuthorizationAuditEvent event = AuthorizationAuditEvent.builder()
                .eventType(AuthorizationAuditEventType.AUTHORIZATION_DENIED)
                .userId(USER_ID)
                .organizationId(ORG_ID)
                .scope(RoleScope.ORGANIZATION)
                .decision(AuthorizationDecision.deny(DenialReason.INSUFFICIENT_PERMISSIONS, "Denied"))
                .build();

        Optional<SecurityEventEntity> detectedOpt = rule.evaluate(event);

        assertThat(detectedOpt).isEmpty();
    }

    @Test
    @DisplayName("Cross-tenant access violation rule triggers upon repeated boundary violations")
    void shouldTriggerCrossTenantAccessViolationRule() {
        CrossTenantAccessViolationRule rule = new CrossTenantAccessViolationRule(auditEventRepository, properties);

        when(auditEventRepository.countRecentTenantBoundaryViolations(eq(USER_ID.toString()), any(Instant.class)))
                .thenReturn(3L);
        when(auditEventRepository.findRecentEvidenceEventIds(eq(ORG_ID.toString()), eq(USER_ID.toString()), any(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of("e-1", "e-2", "e-3"));

        AuthorizationAuditEvent event = AuthorizationAuditEvent.builder()
                .eventType(AuthorizationAuditEventType.TENANT_BOUNDARY_VIOLATION)
                .userId(USER_ID)
                .organizationId(ORG_ID)
                .scope(RoleScope.ORGANIZATION)
                .decision(AuthorizationDecision.deny(DenialReason.TENANT_MISMATCH, "Boundary violation"))
                .build();

        Optional<SecurityEventEntity> detectedOpt = rule.evaluate(event);

        assertThat(detectedOpt).isPresent();
        SecurityEventEntity detected = detectedOpt.get();
        assertThat(detected.getRuleId()).isEqualTo(CrossTenantAccessViolationRule.RULE_ID);
        assertThat(detected.getSeverity()).isEqualTo(SecuritySeverity.HIGH);
    }

    @Test
    @DisplayName("Project scope violation rule triggers upon unassigned project access attempts")
    void shouldTriggerProjectScopeViolationRule() {
        ProjectScopeViolationRule rule = new ProjectScopeViolationRule(auditEventRepository, properties);

        when(auditEventRepository.countRecentProjectScopeViolations(eq(ORG_ID.toString()), eq(USER_ID.toString()), any(Instant.class)))
                .thenReturn(5L);
        when(auditEventRepository.findRecentEvidenceEventIds(eq(ORG_ID.toString()), eq(USER_ID.toString()), any(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of("ps-1", "ps-2"));

        AuthorizationAuditEvent event = AuthorizationAuditEvent.builder()
                .eventType(AuthorizationAuditEventType.PROJECT_ASSIGNMENT_DENIAL)
                .userId(USER_ID)
                .organizationId(ORG_ID)
                .scope(RoleScope.PROJECT)
                .decision(AuthorizationDecision.deny(DenialReason.PROJECT_ASSIGNMENT_REQUIRED, "Project assignment required"))
                .build();

        Optional<SecurityEventEntity> detectedOpt = rule.evaluate(event);

        assertThat(detectedOpt).isPresent();
        SecurityEventEntity detected = detectedOpt.get();
        assertThat(detected.getRuleId()).isEqualTo(ProjectScopeViolationRule.RULE_ID);
    }

    @Test
    @DisplayName("Unauthorized privileged role grant attempt triggers privilege escalation rule")
    void shouldTriggerUnauthorizedPrivilegedRoleGrantRule() {
        UnauthorizedPrivilegedRoleGrantRule rule = new UnauthorizedPrivilegedRoleGrantRule(auditEventRepository, properties);

        when(auditEventRepository.countRecentPrivilegedRoleGrantAttempts(eq(ORG_ID.toString()), eq(USER_ID.toString()), any(Instant.class)))
                .thenReturn(2L);
        when(auditEventRepository.findRecentEvidenceEventIds(eq(ORG_ID.toString()), eq(USER_ID.toString()), any(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of("priv-1", "priv-2"));

        AuthorizationAuditEvent event = AuthorizationAuditEvent.builder()
                .eventType(AuthorizationAuditEventType.PRIVILEGED_ROLE_GRANT_ATTEMPTED)
                .userId(USER_ID)
                .organizationId(ORG_ID)
                .scope(RoleScope.ORGANIZATION)
                .decision(AuthorizationDecision.deny(DenialReason.INSUFFICIENT_PERMISSIONS, "Privileged grant denied"))
                .build();

        Optional<SecurityEventEntity> detectedOpt = rule.evaluate(event);

        assertThat(detectedOpt).isPresent();
        SecurityEventEntity detected = detectedOpt.get();
        assertThat(detected.getRuleId()).isEqualTo(UnauthorizedPrivilegedRoleGrantRule.RULE_ID);
        assertThat(detected.getSeverity()).isEqualTo(SecuritySeverity.HIGH);
    }

    @Test
    @DisplayName("Unauthorized permission delegation attempt triggers privilege escalation rule")
    void shouldTriggerUnauthorizedPermissionDelegationRule() {
        UnauthorizedPermissionDelegationRule rule = new UnauthorizedPermissionDelegationRule(auditEventRepository, properties);

        when(auditEventRepository.countRecentPermissionDelegationViolations(eq(ORG_ID.toString()), eq(USER_ID.toString()), any(Instant.class)))
                .thenReturn(2L);
        when(auditEventRepository.findRecentEvidenceEventIds(eq(ORG_ID.toString()), eq(USER_ID.toString()), any(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of("deleg-1", "deleg-2"));

        AuthorizationAuditEvent event = AuthorizationAuditEvent.builder()
                .eventType(AuthorizationAuditEventType.PERMISSION_DELEGATION_VIOLATION_ATTEMPTED)
                .userId(USER_ID)
                .organizationId(ORG_ID)
                .scope(RoleScope.ORGANIZATION)
                .decision(AuthorizationDecision.deny(DenialReason.INSUFFICIENT_PERMISSIONS, "Delegation denied"))
                .build();

        Optional<SecurityEventEntity> detectedOpt = rule.evaluate(event);

        assertThat(detectedOpt).isPresent();
        SecurityEventEntity detected = detectedOpt.get();
        assertThat(detected.getRuleId()).isEqualTo(UnauthorizedPermissionDelegationRule.RULE_ID);
        assertThat(detected.getSeverity()).isEqualTo(SecuritySeverity.HIGH);
    }

    @Test
    @DisplayName("Rapid bulk role modifications rule triggers administrative anomaly alert")
    void shouldTriggerRapidBulkRoleModificationsRule() {
        RapidBulkRoleModificationsRule rule = new RapidBulkRoleModificationsRule(auditEventRepository, properties);

        when(auditEventRepository.countRecentAdministrativeModifications(eq(ORG_ID.toString()), eq(USER_ID.toString()), any(Instant.class)))
                .thenReturn(12L);
        when(auditEventRepository.findRecentEvidenceEventIds(eq(ORG_ID.toString()), eq(USER_ID.toString()), any(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of("mod-1", "mod-2"));

        AuthorizationAuditEvent event = AuthorizationAuditEvent.builder()
                .eventType(AuthorizationAuditEventType.ROLE_GRANTED)
                .userId(USER_ID)
                .organizationId(ORG_ID)
                .scope(RoleScope.ORGANIZATION)
                .decision(AuthorizationDecision.allow())
                .build();

        Optional<SecurityEventEntity> detectedOpt = rule.evaluate(event);

        assertThat(detectedOpt).isPresent();
        SecurityEventEntity detected = detectedOpt.get();
        assertThat(detected.getRuleId()).isEqualTo(RapidBulkRoleModificationsRule.RULE_ID);
        assertThat(detected.getSeverity()).isEqualTo(SecuritySeverity.MEDIUM);
    }

    @Test
    @DisplayName("Alert deduplication updates existing open alert instead of flooding duplicates")
    void shouldDeduplicateSecurityAlertsWithinCooldown() {
        SecurityEventEntity secEvent = SecurityEventEntity.builder()
                .id(UUID.randomUUID().toString())
                .organizationId(ORG_ID.toString())
                .ruleId("AUTHZ_REPEATED_DENIALS")
                .severity(SecuritySeverity.MEDIUM)
                .title("Repeated Authorization Denials")
                .description("5 denials")
                .actorId(USER_ID.toString())
                .detectedAt(Instant.now())
                .build();

        SecurityAlertEntity existingAlert = SecurityAlertEntity.builder()
                .id("alert-existing")
                .organizationId(ORG_ID.toString())
                .ruleId("AUTHZ_REPEATED_DENIALS")
                .severity(SecuritySeverity.MEDIUM)
                .status(SecurityAlertStatus.OPEN)
                .title("Repeated Authorization Denials")
                .description("5 denials")
                .actorId(USER_ID.toString())
                .firstSeenAt(Instant.now().minusSeconds(120))
                .lastSeenAt(Instant.now().minusSeconds(60))
                .occurrenceCount(1)
                .latestEventId("event-old")
                .build();

        when(alertRepository.findActiveAlertForDeduplication(
                eq(ORG_ID.toString()), eq("AUTHZ_REPEATED_DENIALS"), eq(USER_ID.toString()), any(Instant.class)))
                .thenReturn(Optional.of(existingAlert));
        when(alertRepository.save(any(SecurityAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SecurityAlertEntity result = alertService.processSecurityEvent(secEvent);

        assertThat(result.getId()).isEqualTo("alert-existing");
        assertThat(result.getOccurrenceCount()).isEqualTo(2);
        assertThat(result.getLatestEventId()).isEqualTo(secEvent.getId());
    }

    @Test
    @DisplayName("Alert acknowledgement and resolution transitions work with validation")
    void shouldHandleAlertAcknowledgementAndResolution() {
        UUID alertId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        SecurityAlertEntity openAlert = SecurityAlertEntity.builder()
                .id(alertId.toString())
                .organizationId(ORG_ID.toString())
                .ruleId("AUTHZ_REPEATED_DENIALS")
                .severity(SecuritySeverity.MEDIUM)
                .status(SecurityAlertStatus.OPEN)
                .title("Alert")
                .description("Desc")
                .actorId(USER_ID.toString())
                .firstSeenAt(Instant.now())
                .lastSeenAt(Instant.now())
                .occurrenceCount(1)
                .latestEventId("e-1")
                .build();

        when(alertRepository.findByOrganizationIdAndId(ORG_ID.toString(), alertId.toString()))
                .thenReturn(Optional.of(openAlert));
        when(alertRepository.save(any(SecurityAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 1. Acknowledge
        SecurityAlertResponse ackResponse = alertService.acknowledgeAlert(ORG_ID, alertId, adminId);
        assertThat(ackResponse.getStatus()).isEqualTo(SecurityAlertStatus.ACKNOWLEDGED);
        assertThat(ackResponse.getAcknowledgedBy()).isEqualTo(adminId.toString());

        // 2. Resolve
        SecurityAlertResponse resResponse = alertService.resolveAlert(
                ORG_ID, alertId, "Investigated: developer testing integration credentials", adminId);
        assertThat(resResponse.getStatus()).isEqualTo(SecurityAlertStatus.RESOLVED);
        assertThat(resResponse.getResolvedBy()).isEqualTo(adminId.toString());
        assertThat(resResponse.getResolutionReason()).contains("developer testing");

        // 3. Resolving without reason fails
        assertThatThrownBy(() -> alertService.resolveAlert(ORG_ID, alertId, "   ", adminId))
                .isInstanceOf(InvalidSecurityOperationException.class)
                .hasMessageContaining("Resolution reason is required");
    }
}
