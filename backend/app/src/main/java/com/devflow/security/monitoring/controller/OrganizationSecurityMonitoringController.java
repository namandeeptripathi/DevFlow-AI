package com.devflow.security.monitoring.controller;

import com.devflow.audit.dto.AuditIntegrityVerificationResponse;
import com.devflow.audit.dto.AuditLogResponse;
import com.devflow.audit.service.AuditIntegrityService;
import com.devflow.audit.service.AuthorizationAuditService;
import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.DenialReason;
import com.devflow.modules.auth.audit.AuthorizationAuditEvent;
import com.devflow.modules.auth.audit.AuthorizationAuditEventType;
import com.devflow.modules.auth.audit.AuthorizationAuditRecorder;
import com.devflow.modules.auth.context.OrganizationAuthorizationContext;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.domain.RoleScope;
import com.devflow.role.adapter.DatabaseRoleResolver;
import com.devflow.security.monitoring.domain.SecurityAlertStatus;
import com.devflow.security.monitoring.domain.SecuritySeverity;
import com.devflow.security.monitoring.dto.ResolveSecurityAlertRequest;
import com.devflow.security.monitoring.dto.SecurityAlertResponse;
import com.devflow.security.monitoring.dto.SecurityEventResponse;
import com.devflow.security.monitoring.exception.SecurityMonitoringAccessDeniedException;
import com.devflow.security.monitoring.service.SecurityAlertService;
import com.devflow.security.monitoring.service.SecurityMonitoringService;
import com.devflow.security.user.DevFlowUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * REST controller providing tenant-scoped access to authorization audit logs,
 * cryptographic integrity verification, detected security events, and security alert investigation.
 *
 * <p>Base path: {@code /api/v1/organizations/{organizationId}/security}
 *
 * <h2>Security Invariants</h2>
 * <ul>
 *   <li>Only active organization {@code OWNER} or {@code ADMIN} can access security monitoring.</li>
 *   <li>Audit logs are strictly append-only and cannot be modified or deleted via API.</li>
 *   <li>Strict tenant isolation: organization administrators cannot query cross-tenant or platform events.</li>
 *   <li>Administrative investigations (alert acknowledgement and resolution) are themselves audited.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/security")
@Tag(name = "Security Monitoring", description = "Endpoints for authorization audit logs, cryptographic integrity, and security alert management")
public class OrganizationSecurityMonitoringController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuthorizationAuditService auditService;
    private final AuditIntegrityService integrityService;
    private final SecurityMonitoringService monitoringService;
    private final SecurityAlertService alertService;
    private final DatabaseRoleResolver roleResolver;
    private final AuthorizationAuditRecorder auditRecorder;

    public OrganizationSecurityMonitoringController(
            AuthorizationAuditService auditService,
            AuditIntegrityService integrityService,
            SecurityMonitoringService monitoringService,
            SecurityAlertService alertService,
            DatabaseRoleResolver roleResolver,
            @Autowired(required = false) AuthorizationAuditRecorder auditRecorder
    ) {
        this.auditService = Objects.requireNonNull(auditService, "auditService must not be null");
        this.integrityService = Objects.requireNonNull(integrityService, "integrityService must not be null");
        this.monitoringService = Objects.requireNonNull(monitoringService, "monitoringService must not be null");
        this.alertService = Objects.requireNonNull(alertService, "alertService must not be null");
        this.roleResolver = Objects.requireNonNull(roleResolver, "roleResolver must not be null");
        this.auditRecorder = auditRecorder;
    }

    // ── Audit Log APIs ────────────────────────────────────────────────────────

    @GetMapping("/audit-logs")
    @Operation(summary = "Search and list tenant authorization audit logs")
    public ResponseEntity<Page<AuditLogResponse>> getAuditLogs(
            @PathVariable UUID organizationId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID targetId,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        verifyCallerIsAdminOrOwner(organizationId, userDetails);
        Page<AuditLogResponse> logs = auditService.searchAuditLogs(
                organizationId, eventType, userId, targetId, decision, from, to, safePageRequest(page, size)
        );
        return ResponseEntity.ok(logs);
    }

    @GetMapping("/audit-logs/{eventId}")
    @Operation(summary = "Retrieve an individual authorization audit event")
    public ResponseEntity<AuditLogResponse> getAuditLogById(
            @PathVariable UUID organizationId,
            @PathVariable String eventId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        verifyCallerIsAdminOrOwner(organizationId, userDetails);
        AuditLogResponse logResponse = auditService.getAuditLogById(organizationId, eventId);
        return ResponseEntity.ok(logResponse);
    }

    @GetMapping("/audit-logs/integrity")
    @Operation(summary = "Verify cryptographic integrity of audit records within tenant scope")
    public ResponseEntity<AuditIntegrityVerificationResponse> verifyAuditIntegrity(
            @PathVariable UUID organizationId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Long fromSeq,
            @RequestParam(required = false) Long toSeq,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        verifyCallerIsAdminOrOwner(organizationId, userDetails);

        AuditIntegrityVerificationResponse response;
        if (fromSeq != null && toSeq != null) {
            response = integrityService.verifyIntegrityBySequence(organizationId, fromSeq, toSeq);
        } else {
            response = integrityService.verifyIntegrity(organizationId, from, to);
        }

        if (auditRecorder != null) {
            AuthorizationAuditEvent auditEvent = AuthorizationAuditEvent.builder()
                    .eventType(AuthorizationAuditEventType.AUDIT_INTEGRITY_VERIFIED)
                    .userId(userDetails.getId())
                    .scope(RoleScope.ORGANIZATION)
                    .organizationId(organizationId)
                    .reason(String.format("Audit integrity verified: valid=%s, records=%d, tampered=%s",
                            response.isValid(), response.getTotalRecordsVerified(), response.isTampered()))
                    .decision(AuthorizationDecision.allow())
                    .build();
            auditRecorder.record(auditEvent);
        }

        return ResponseEntity.ok(response);
    }

    // ── Security Event APIs ───────────────────────────────────────────────────

    @GetMapping("/events")
    @Operation(summary = "List detected security events")
    public ResponseEntity<Page<SecurityEventResponse>> getSecurityEvents(
            @PathVariable UUID organizationId,
            @RequestParam(required = false) String ruleId,
            @RequestParam(required = false) SecuritySeverity severity,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        verifyCallerIsAdminOrOwner(organizationId, userDetails);
        Page<SecurityEventResponse> events = monitoringService.getSecurityEvents(
                organizationId, ruleId, severity, from, to, safePageRequest(page, size)
        );
        return ResponseEntity.ok(events);
    }

    @GetMapping("/events/{eventId}")
    @Operation(summary = "Retrieve security event details")
    public ResponseEntity<SecurityEventResponse> getSecurityEventById(
            @PathVariable UUID organizationId,
            @PathVariable String eventId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        verifyCallerIsAdminOrOwner(organizationId, userDetails);
        SecurityEventResponse event = monitoringService.getSecurityEventById(organizationId, eventId);
        return ResponseEntity.ok(event);
    }

    // ── Security Alert Management APIs ────────────────────────────────────────

    @GetMapping("/alerts")
    @Operation(summary = "List security alerts")
    public ResponseEntity<Page<SecurityAlertResponse>> getAlerts(
            @PathVariable UUID organizationId,
            @RequestParam(required = false) SecurityAlertStatus status,
            @RequestParam(required = false) SecuritySeverity severity,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        verifyCallerIsAdminOrOwner(organizationId, userDetails);
        Page<SecurityAlertResponse> alerts = alertService.getAlerts(
                organizationId, status, severity, from, to, safePageRequest(page, size)
        );
        return ResponseEntity.ok(alerts);
    }

    @GetMapping("/alerts/{alertId}")
    @Operation(summary = "Retrieve security alert details")
    public ResponseEntity<SecurityAlertResponse> getAlertById(
            @PathVariable UUID organizationId,
            @PathVariable UUID alertId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        verifyCallerIsAdminOrOwner(organizationId, userDetails);
        SecurityAlertResponse alert = alertService.getAlertById(organizationId, alertId);
        return ResponseEntity.ok(alert);
    }

    @PostMapping("/alerts/{alertId}/acknowledge")
    @Operation(summary = "Acknowledge a security alert for active investigation")
    public ResponseEntity<SecurityAlertResponse> acknowledgeAlert(
            @PathVariable UUID organizationId,
            @PathVariable UUID alertId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        verifyCallerIsAdminOrOwner(organizationId, userDetails);
        SecurityAlertResponse response = alertService.acknowledgeAlert(organizationId, alertId, userDetails.getId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/alerts/{alertId}/resolve")
    @Operation(summary = "Resolve a security alert with an explicit investigation rationale")
    public ResponseEntity<SecurityAlertResponse> resolveAlert(
            @PathVariable UUID organizationId,
            @PathVariable UUID alertId,
            @Valid @RequestBody ResolveSecurityAlertRequest request,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        verifyCallerIsAdminOrOwner(organizationId, userDetails);
        SecurityAlertResponse response = alertService.resolveAlert(
                organizationId, alertId, request.getResolutionReason(), userDetails.getId()
        );
        return ResponseEntity.ok(response);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private PageRequest safePageRequest(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize);
    }

    private void verifyCallerIsAdminOrOwner(UUID organizationId, DevFlowUserDetails userDetails) {
        if (userDetails == null || userDetails.getId() == null) {
            throw new SecurityMonitoringAccessDeniedException("Unauthenticated caller");
        }

        UUID callerUserId = userDetails.getId();
        Set<Role> callerRoles = roleResolver.resolveRoles(
                callerUserId,
                OrganizationAuthorizationContext.of(organizationId)
        );

        if (!callerRoles.contains(Role.ORGANIZATION_OWNER) && !callerRoles.contains(Role.ORGANIZATION_ADMIN)) {
            if (auditRecorder != null) {
                try {
                    auditRecorder.record(AuthorizationAuditEvent.builder()
                            .eventType(AuthorizationAuditEventType.AUTHORIZATION_DENIED)
                            .userId(callerUserId)
                            .scope(RoleScope.ORGANIZATION)
                            .organizationId(organizationId)
                            .resourceType("SECURITY_MONITORING")
                            .reason("Unauthorized access attempt to tenant security monitoring")
                            .decision(AuthorizationDecision.deny(
                                    DenialReason.INSUFFICIENT_PERMISSIONS,
                                    "Caller lacks ORGANIZATION_OWNER or ORGANIZATION_ADMIN role"))
                            .build());
                } catch (Exception ignored) {
                    // Never mask access denial if non-admin evaluation audit fails
                }
            }
            throw new SecurityMonitoringAccessDeniedException(
                    "User [" + callerUserId + "] is not authorized to access security monitoring in organization [" + organizationId + "]"
            );
        }
    }
}
