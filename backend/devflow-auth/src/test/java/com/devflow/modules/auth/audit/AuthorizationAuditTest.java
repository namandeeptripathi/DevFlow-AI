package com.devflow.modules.auth.audit;

import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.AuthorizationRequest;
import com.devflow.modules.auth.api.DenialReason;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.domain.RoleScope;
import com.devflow.modules.auth.evaluator.AuthorizationEvaluator;
import com.devflow.modules.auth.evaluator.TenantBoundaryValidator;
import com.devflow.modules.auth.registry.PermissionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class AuthorizationAuditTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();

    private List<AuthorizationAuditEvent> recordedEvents;
    private AuthorizationAuditRecorder auditRecorder;

    @BeforeEach
    void setUp() {
        recordedEvents = new ArrayList<>();
        auditRecorder = recordedEvents::add;
    }

    @Test
    @DisplayName("Audit event generated for AUTHORIZATION_ALLOWED")
    void shouldRecordAuthorizationAllowedAudit() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_MEMBER))
                .auditRecorder(auditRecorder)
                .build();

        evaluator.authorize(AuthorizationRequest.organization(USER_ID, ORG_ID, PermissionRegistry.PROJECT_READ));

        assertThat(recordedEvents).hasSize(1);
        AuthorizationAuditEvent event = recordedEvents.get(0);

        assertThat(event.eventType()).isEqualTo(AuthorizationAuditEventType.AUTHORIZATION_ALLOWED);
        assertThat(event.userId()).isEqualTo(USER_ID);
        assertThat(event.scope()).isEqualTo(RoleScope.ORGANIZATION);
        assertThat(event.getOrganizationId()).contains(ORG_ID);
        assertThat(event.decision().allowed()).isTrue();
        assertThat(event.denialReason()).isNull();
        assertThat(event.requestedPermissions()).contains(PermissionRegistry.PROJECT_READ);
        assertThat(event.timestamp()).isNotNull();
        assertThat(event.eventId()).isNotNull();
    }

    @Test
    @DisplayName("Audit event generated for AUTHORIZATION_DENIED due to insufficient permissions")
    void shouldRecordAuthorizationDeniedAudit() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_VIEWER))
                .auditRecorder(auditRecorder)
                .build();

        // ORGANIZATION_VIEWER does not have project.write
        evaluator.authorize(AuthorizationRequest.organization(USER_ID, ORG_ID, PermissionRegistry.PROJECT_WRITE));

        assertThat(recordedEvents).hasSize(1);
        AuthorizationAuditEvent event = recordedEvents.get(0);

        assertThat(event.eventType()).isEqualTo(AuthorizationAuditEventType.AUTHORIZATION_DENIED);
        assertThat(event.userId()).isEqualTo(USER_ID);
        assertThat(event.decision().allowed()).isFalse();
        assertThat(event.denialReason()).isEqualTo(DenialReason.INSUFFICIENT_PERMISSIONS);
    }

    @Test
    @DisplayName("Audit event generated for TENANT_BOUNDARY_VIOLATION")
    void shouldRecordTenantBoundaryViolationAudit() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator(new TenantBoundaryValidator() {
                    @Override
                    public boolean isProjectInOrganization(UUID organizationId, UUID projectId) {
                        return false; // mismatch
                    }
                })
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_LEAD))
                .auditRecorder(auditRecorder)
                .build();

        evaluator.authorize(AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.PROJECT_READ));

        assertThat(recordedEvents).hasSize(1);
        AuthorizationAuditEvent event = recordedEvents.get(0);

        assertThat(event.eventType()).isEqualTo(AuthorizationAuditEventType.TENANT_BOUNDARY_VIOLATION);
        assertThat(event.denialReason()).isEqualTo(DenialReason.TENANT_MISMATCH);
        assertThat(event.getProjectId()).contains(PROJECT_ID);
    }

    @Test
    @DisplayName("Audit event generated for MEMBERSHIP_DENIAL")
    void shouldRecordMembershipDenialAudit() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> false) // inactive
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_MEMBER))
                .auditRecorder(auditRecorder)
                .build();

        evaluator.authorize(AuthorizationRequest.organization(USER_ID, ORG_ID, PermissionRegistry.PROJECT_READ));

        assertThat(recordedEvents).hasSize(1);
        AuthorizationAuditEvent event = recordedEvents.get(0);

        assertThat(event.eventType()).isEqualTo(AuthorizationAuditEventType.MEMBERSHIP_DENIAL);
        assertThat(event.denialReason()).isEqualTo(DenialReason.MEMBERSHIP_INACTIVE);
    }

    @Test
    @DisplayName("Audit event generated for OWNERSHIP_DENIAL")
    void shouldRecordOwnershipDenialAudit() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_LEAD))
                .resourceOwnershipValidator((userId, context, type, id) -> false) // not owner
                .auditRecorder(auditRecorder)
                .build();

        evaluator.authorize(AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.PROJECT_DELETE, PROJECT_ID.toString()));

        assertThat(recordedEvents).hasSize(1);
        AuthorizationAuditEvent event = recordedEvents.get(0);

        assertThat(event.eventType()).isEqualTo(AuthorizationAuditEventType.OWNERSHIP_DENIAL);
        assertThat(event.denialReason()).isEqualTo(DenialReason.OWNERSHIP_REQUIRED);
    }

    @Test
    @DisplayName("Audit event generated for UNKNOWN_PERMISSION_DENIAL")
    void shouldRecordUnknownPermissionDenialAudit() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .auditRecorder(auditRecorder)
                .build();

        evaluator.authorize(AuthorizationRequest.organization(
                USER_ID, ORG_ID, Permission.of("unknown", "action")));

        assertThat(recordedEvents).hasSize(1);
        AuthorizationAuditEvent event = recordedEvents.get(0);

        assertThat(event.eventType()).isEqualTo(AuthorizationAuditEventType.UNKNOWN_PERMISSION_DENIAL);
        assertThat(event.denialReason()).isEqualTo(DenialReason.UNKNOWN_PERMISSION);
    }

    @Test
    @DisplayName("Audit event generated for RESOURCE_STATE_DENIAL")
    void shouldRecordResourceStateDenialAudit() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_OWNER))
                .resourceStateValidator((context, type, id, perm) -> false) // locked
                .auditRecorder(auditRecorder)
                .build();

        evaluator.authorize(AuthorizationRequest.organization(
                USER_ID, ORG_ID, PermissionRegistry.PROJECT_WRITE));

        assertThat(recordedEvents).hasSize(1);
        AuthorizationAuditEvent event = recordedEvents.get(0);

        assertThat(event.eventType()).isEqualTo(AuthorizationAuditEventType.RESOURCE_STATE_DENIAL);
        assertThat(event.denialReason()).isEqualTo(DenialReason.RESOURCE_STATE_CONFLICT);
    }

    @Test
    @DisplayName("Audit event generated for SCOPE_MISMATCH_DENIAL")
    void shouldRecordScopeMismatchDenialAudit() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.PROJECT_LEAD)) // PROJECT scope
                .auditRecorder(auditRecorder)
                .build();

        evaluator.authorize(AuthorizationRequest.organization(
                USER_ID, ORG_ID, PermissionRegistry.PROJECT_READ));

        assertThat(recordedEvents).hasSize(1);
        AuthorizationAuditEvent event = recordedEvents.get(0);

        assertThat(event.eventType()).isEqualTo(AuthorizationAuditEventType.SCOPE_MISMATCH_DENIAL);
        assertThat(event.denialReason()).isEqualTo(DenialReason.SCOPE_MISMATCH);
    }

    @Test
    @DisplayName("Audit recorder failures do not disrupt authorization decision")
    void auditRecorderFailuresMustNotDisruptAuthorization() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_MEMBER))
                .auditRecorder(event -> {
                    throw new RuntimeException("Simulated audit logger disk full");
                })
                .build();

        assertThatNoException().isThrownBy(() -> {
            AuthorizationDecision decision = evaluator.authorize(
                    AuthorizationRequest.organization(USER_ID, ORG_ID, PermissionRegistry.PROJECT_READ));
            assertThat(decision.allowed()).isTrue();
        });
    }

    @Test
    @DisplayName("Default no-op audit recorder performs safely")
    void defaultNoOpRecorderShouldWork() {
        AuthorizationAuditRecorder noOp = AuthorizationAuditRecorder.noOp();
        AuthorizationAuditEvent event = new AuthorizationAuditEvent(
                UUID.randomUUID(),
                java.time.Instant.now(),
                AuthorizationAuditEventType.AUTHORIZATION_ALLOWED,
                USER_ID,
                RoleScope.ORGANIZATION,
                ORG_ID,
                null,
                "project",
                null,
                Set.of(PermissionRegistry.PROJECT_READ),
                AuthorizationDecision.allow(),
                null
        );

        assertThatNoException().isThrownBy(() -> noOp.record(event));
    }

    @Test
    @DisplayName("Audit event generated for SUPPORT_ACCESS_ALLOWED")
    void shouldRecordSupportAccessAllowedAudit() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .platformSupportValidator((userId, orgId, caseId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.PLATFORM_SUPPORT))
                .auditRecorder(auditRecorder)
                .build();

        AuthorizationRequest request = AuthorizationRequest.platform(
                USER_ID,
                PermissionRegistry.PLATFORM_SUPPORT_CASE_READ,
                Map.of("targetOrganizationId", ORG_ID, "supportCaseId", "case-100")
        );

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isTrue();
        assertThat(recordedEvents).hasSize(1);
        AuthorizationAuditEvent event = recordedEvents.get(0);
        assertThat(event.eventType()).isEqualTo(AuthorizationAuditEventType.SUPPORT_ACCESS_ALLOWED);
        assertThat(event.userId()).isEqualTo(USER_ID);
        assertThat(event.scope()).isEqualTo(RoleScope.PLATFORM);
    }

    @Test
    @DisplayName("Audit event generated for SUPPORT_ACCESS_DENIED")
    void shouldRecordSupportAccessDeniedAudit() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .platformSupportValidator((userId, orgId, caseId) -> false)
                .roleResolver((userId, context) -> Set.of(Role.PLATFORM_SUPPORT))
                .auditRecorder(auditRecorder)
                .build();

        AuthorizationRequest request = AuthorizationRequest.platform(
                USER_ID,
                PermissionRegistry.PLATFORM_SUPPORT_CASE_READ,
                Map.of("targetOrganizationId", ORG_ID, "supportCaseId", "case-100")
        );

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(recordedEvents).hasSize(1);
        AuthorizationAuditEvent event = recordedEvents.get(0);
        assertThat(event.eventType()).isEqualTo(AuthorizationAuditEventType.SUPPORT_ACCESS_DENIED);
        assertThat(event.denialReason()).isEqualTo(DenialReason.SUPPORT_CASE_UNAUTHORIZED);
    }

    @Test
    @DisplayName("Audit event generated for EMERGENCY_ACCESS_ALLOWED")
    void shouldRecordEmergencyAccessAllowedAudit() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .emergencyAccessValidator((userId, timestamp) -> true)
                .roleResolver((userId, context) -> Set.of(Role.PLATFORM_ADMIN))
                .auditRecorder(auditRecorder)
                .build();

        AuthorizationRequest request = AuthorizationRequest.platform(
                USER_ID,
                PermissionRegistry.PLATFORM_EMERGENCY_ACCESS_EXECUTE
        );

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isTrue();
        assertThat(recordedEvents).hasSize(1);
        AuthorizationAuditEvent event = recordedEvents.get(0);
        assertThat(event.eventType()).isEqualTo(AuthorizationAuditEventType.EMERGENCY_ACCESS_ALLOWED);
        assertThat(event.userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("Audit event generated for EMERGENCY_ACCESS_DENIED")
    void shouldRecordEmergencyAccessDeniedAudit() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .emergencyAccessValidator((userId, timestamp) -> false)
                .roleResolver((userId, context) -> Set.of(Role.PLATFORM_ADMIN))
                .auditRecorder(auditRecorder)
                .build();

        AuthorizationRequest request = AuthorizationRequest.platform(
                USER_ID,
                PermissionRegistry.PLATFORM_EMERGENCY_ACCESS_EXECUTE
        );

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(recordedEvents).hasSize(1);
        AuthorizationAuditEvent event = recordedEvents.get(0);
        assertThat(event.eventType()).isEqualTo(AuthorizationAuditEventType.EMERGENCY_ACCESS_DENIED);
        assertThat(event.denialReason()).isEqualTo(DenialReason.EMERGENCY_ACCESS_NOT_APPROVED);
    }

    @Test
    @DisplayName("Audit event generated for PROJECT_ASSIGNMENT_DENIAL")
    void shouldRecordProjectAssignmentDenialAudit() {
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .membershipValidator((userId, orgId) -> true)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .roleResolver((userId, context) -> Set.of(Role.ORGANIZATION_MEMBER)) // org member only, no project role
                .projectAssignmentValidator((userId, orgId, projId) -> false) // not assigned
                .auditRecorder(auditRecorder)
                .build();

        AuthorizationRequest request = AuthorizationRequest.project(
                USER_ID, ORG_ID, PROJECT_ID, PermissionRegistry.PROJECT_READ
        );

        AuthorizationDecision decision = evaluator.authorize(request);

        assertThat(decision.allowed()).isFalse();
        assertThat(recordedEvents).hasSize(1);
        AuthorizationAuditEvent event = recordedEvents.get(0);
        assertThat(event.eventType()).isEqualTo(AuthorizationAuditEventType.PROJECT_ASSIGNMENT_DENIAL);
        assertThat(event.denialReason()).isEqualTo(DenialReason.PROJECT_ASSIGNMENT_REQUIRED);
    }
}

