package com.devflow.audit;

import com.devflow.audit.domain.AuthorizationAuditEventEntity;
import com.devflow.audit.repository.AuthorizationAuditEventRepository;
import com.devflow.audit.service.AuditIntegrityService;
import com.devflow.audit.service.AuthorizationAuditService;
import com.devflow.config.JpaAuditingConfiguration;
import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.DenialReason;
import com.devflow.modules.auth.audit.AuthorizationAuditEvent;
import com.devflow.modules.auth.audit.AuthorizationAuditEventType;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.domain.RoleScope;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.organization.service.OrganizationMembershipService;
import com.devflow.role.domain.CustomRoleEntity;
import com.devflow.role.dto.CreateCustomRoleRequest;
import com.devflow.role.service.CustomRoleService;
import com.devflow.role.service.RoleAssignmentService;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import com.devflow.util.TraceContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Stage 8: Authorization Audit Persistence & Security Invariants Integration Tests")
class AuthorizationAuditPersistenceIntegrationTest {

    @Autowired
    private AuthorizationAuditService auditService;

    @Autowired
    private LoggingAuthorizationAuditRecorder auditRecorder;

    @Autowired
    private AuthorizationAuditEventRepository auditEventRepository;

    @Autowired
    private AuditIntegrityService integrityService;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private CustomRoleService customRoleService;

    @Autowired
    private OrganizationMembershipService membershipService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private Organization org;
    private User owner;
    private User admin;
    private User member;

    @BeforeEach
    void setUp() {
        owner = createUser("audit-owner");
        admin = createUser("audit-admin");
        member = createUser("audit-member");

        org = organizationRepository.save(Organization.builder()
                .name("Audit Test Corp")
                .slug("audit-" + UUID.randomUUID())
                .owner(owner)
                .build());

        addMember(org, owner, OrganizationRole.OWNER);
        addMember(org, admin, OrganizationRole.ADMIN);
        addMember(org, member, OrganizationRole.DEVELOPER);

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("Record allowed authorization decision and persist with cryptographic hash chain")
    void shouldPersistAllowedAuthorizationDecision() {
        UUID eventId = UUID.randomUUID();
        AuthorizationAuditEvent event = AuthorizationAuditEvent.builder()
                .eventId(eventId)
                .timestamp(Instant.now())
                .eventType(AuthorizationAuditEventType.AUTHORIZATION_ALLOWED)
                .userId(owner.getId())
                .scope(RoleScope.ORGANIZATION)
                .organizationId(org.getId())
                .resourceType("project")
                .resourceId("proj-1")
                .requestedPermissions(Set.of(PermissionRegistry.PROJECT_READ))
                .decision(AuthorizationDecision.allow())
                .build();

        auditRecorder.record(event);
        entityManager.flush();

        Optional<AuthorizationAuditEventEntity> savedOpt = auditEventRepository.findByEventId(eventId.toString());
        assertThat(savedOpt).isPresent();

        AuthorizationAuditEventEntity entity = savedOpt.get();
        assertThat(entity.getDecision()).isEqualTo("ALLOW");
        assertThat(entity.getEventType()).isEqualTo("AUTHORIZATION_ALLOWED");
        assertThat(entity.getUserId()).isEqualTo(owner.getId().toString());
        assertThat(entity.getOrganizationId()).isEqualTo(org.getId().toString());
        assertThat(entity.getSequenceNumber()).isGreaterThan(0L);
        assertThat(entity.getRecordHash()).isNotBlank();
        assertThat(entity.getPreviousRecordHash()).isNotBlank();

        // Verify cryptographic hash integrity on the persisted entity
        String recomputedHash = integrityService.computeEntityHash(entity);
        assertThat(entity.getRecordHash()).isEqualTo(recomputedHash);
    }

    @Test
    @DisplayName("Record denied authorization decision with structured denial reason")
    void shouldPersistDeniedAuthorizationDecision() {
        UUID eventId = UUID.randomUUID();
        AuthorizationAuditEvent event = AuthorizationAuditEvent.builder()
                .eventId(eventId)
                .timestamp(Instant.now())
                .eventType(AuthorizationAuditEventType.AUTHORIZATION_DENIED)
                .userId(member.getId())
                .scope(RoleScope.ORGANIZATION)
                .organizationId(org.getId())
                .resourceType("member")
                .resourceId("target-user")
                .requestedPermissions(Set.of(PermissionRegistry.MEMBER_INVITE))
                .decision(AuthorizationDecision.deny(DenialReason.INSUFFICIENT_PERMISSIONS, "Lacks member.invite"))
                .denialReason(DenialReason.INSUFFICIENT_PERMISSIONS)
                .build();

        auditRecorder.record(event);
        entityManager.flush();

        Optional<AuthorizationAuditEventEntity> savedOpt = auditEventRepository.findByEventId(eventId.toString());
        assertThat(savedOpt).isPresent();

        AuthorizationAuditEventEntity entity = savedOpt.get();
        assertThat(entity.getDecision()).isEqualTo("DENY");
        assertThat(entity.getDenialReason()).isEqualTo("INSUFFICIENT_PERMISSIONS");
        assertThat(entity.getEventType()).isEqualTo("AUTHORIZATION_DENIED");
    }

    @Test
    @DisplayName("Role assignment and revocation automatically emit persistent audit records")
    void shouldAuditRoleAssignmentAndRevocation() {
        // 1. Grant canonical role
        roleAssignmentService.assignOrganizationRole(
                org.getId(), member.getId(), Role.ORGANIZATION_ADMIN, owner.getId(), null
        );
        entityManager.flush();

        List<AuthorizationAuditEventEntity> grantEvents = auditEventRepository
                .findByOrganizationIdOrderBySequenceNumberAsc(org.getId().toString()).stream()
                .filter(e -> "ROLE_GRANTED".equals(e.getEventType()))
                .toList();

        assertThat(grantEvents).isNotEmpty();
        AuthorizationAuditEventEntity grantEvent = grantEvents.get(grantEvents.size() - 1);
        assertThat(grantEvent.getUserId()).isEqualTo(owner.getId().toString());
        assertThat(grantEvent.getTargetId()).isEqualTo(member.getId().toString());
        assertThat(grantEvent.getNewState()).isEqualTo(Role.ORGANIZATION_ADMIN.name());

        // 2. Revoke canonical role
        roleAssignmentService.revokeOrganizationRole(
                org.getId(), member.getId(), Role.ORGANIZATION_ADMIN, owner.getId(), "Project reorg"
        );
        entityManager.flush();

        List<AuthorizationAuditEventEntity> revokeEvents = auditEventRepository
                .findByOrganizationIdOrderBySequenceNumberAsc(org.getId().toString()).stream()
                .filter(e -> "ROLE_REVOKED".equals(e.getEventType()))
                .toList();

        assertThat(revokeEvents).isNotEmpty();
        AuthorizationAuditEventEntity revokeEvent = revokeEvents.get(revokeEvents.size() - 1);
        assertThat(revokeEvent.getUserId()).isEqualTo(owner.getId().toString());
        assertThat(revokeEvent.getTargetId()).isEqualTo(member.getId().toString());
        assertThat(revokeEvent.getReason()).isEqualTo("Project reorg");
    }

    @Test
    @DisplayName("Custom role lifecycle automatically emits persistent audit records")
    void shouldAuditCustomRoleLifecycle() {
        CreateCustomRoleRequest request = CreateCustomRoleRequest.builder()
                .name("SEC_AUDITOR")
                .displayName("Security Auditor")
                .permissions(Set.of("project.read", "analytics.view"))
                .build();

        CustomRoleEntity created = customRoleService.createCustomRole(org.getId(), owner.getId(), request);
        entityManager.flush();

        List<AuthorizationAuditEventEntity> customRoleEvents = auditEventRepository
                .findByOrganizationIdOrderBySequenceNumberAsc(org.getId().toString()).stream()
                .filter(e -> "CUSTOM_ROLE_CREATED".equals(e.getEventType()))
                .toList();

        assertThat(customRoleEvents).isNotEmpty();
        AuthorizationAuditEventEntity createdAudit = customRoleEvents.get(customRoleEvents.size() - 1);
        assertThat(createdAudit.getTargetId()).isEqualTo(created.getId().toString());
        assertThat(createdAudit.getNewState()).isEqualTo("SEC_AUDITOR");

        // Verify custom role permission added audit events
        List<AuthorizationAuditEventEntity> permEvents = auditEventRepository
                .findByOrganizationIdOrderBySequenceNumberAsc(org.getId().toString()).stream()
                .filter(e -> "CUSTOM_ROLE_PERMISSION_ADDED".equals(e.getEventType()))
                .toList();
        assertThat(permEvents).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Organization membership removal emits persistent audit records")
    void shouldAuditMembershipRemoval() {
        membershipService.removeMember(org.getId(), member.getId(), owner.getId());
        entityManager.flush();

        List<AuthorizationAuditEventEntity> removalEvents = auditEventRepository
                .findByOrganizationIdOrderBySequenceNumberAsc(org.getId().toString()).stream()
                .filter(e -> "MEMBERSHIP_REMOVED".equals(e.getEventType()))
                .toList();

        assertThat(removalEvents).isNotEmpty();
        AuthorizationAuditEventEntity removalEvent = removalEvents.get(removalEvents.size() - 1);
        assertThat(removalEvent.getTargetId()).isEqualTo(member.getId().toString());
        assertThat(removalEvent.getUserId()).isEqualTo(owner.getId().toString());
    }

    @Test
    @DisplayName("Propagate correlation ID from context into persistent audit entity")
    void shouldPropagateCorrelationIdIntoAuditLedger() {
        String testCorrelationId = "test-corr-" + UUID.randomUUID();
        MDC.put(TraceContext.CORRELATION_ID_KEY, testCorrelationId);

        try {
            UUID eventId = UUID.randomUUID();
            AuthorizationAuditEvent event = AuthorizationAuditEvent.builder()
                    .eventId(eventId)
                    .timestamp(Instant.now())
                    .eventType(AuthorizationAuditEventType.AUTHORIZATION_ALLOWED)
                    .userId(owner.getId())
                    .scope(RoleScope.ORGANIZATION)
                    .organizationId(org.getId())
                    .decision(AuthorizationDecision.allow())
                    .build();

            auditRecorder.record(event);
            entityManager.flush();

            AuthorizationAuditEventEntity entity = auditEventRepository.findByEventId(eventId.toString()).orElseThrow();
            assertThat(entity.getCorrelationId()).isEqualTo(testCorrelationId);
        } finally {
            MDC.remove(TraceContext.CORRELATION_ID_KEY);
        }
    }

    @Test
    @DisplayName("Database-level trigger prevents UPDATE on authorization_audit_events table")
    void shouldPreventUpdateOnAuditEventsViaDatabaseTrigger() {
        UUID eventId = UUID.randomUUID();
        AuthorizationAuditEvent event = AuthorizationAuditEvent.builder()
                .eventId(eventId)
                .timestamp(Instant.now())
                .eventType(AuthorizationAuditEventType.AUTHORIZATION_ALLOWED)
                .userId(owner.getId())
                .scope(RoleScope.ORGANIZATION)
                .organizationId(org.getId())
                .decision(AuthorizationDecision.allow())
                .build();

        auditRecorder.record(event);
        entityManager.flush();

        // Attempting direct SQL UPDATE should be blocked by trigger trg_prevent_audit_log_update_delete
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                    "UPDATE authorization_audit_events SET decision = 'TAMPERED' WHERE event_id = :eventId"
            ).setParameter("eventId", eventId.toString()).executeUpdate();
            entityManager.flush();
        }).isInstanceOf(Exception.class)
                .hasMessageContaining("Audit records are append-only");
    }

    @Test
    @DisplayName("Database-level trigger prevents DELETE on authorization_audit_events table")
    void shouldPreventDeleteOnAuditEventsViaDatabaseTrigger() {
        UUID eventId = UUID.randomUUID();
        AuthorizationAuditEvent event = AuthorizationAuditEvent.builder()
                .eventId(eventId)
                .timestamp(Instant.now())
                .eventType(AuthorizationAuditEventType.AUTHORIZATION_ALLOWED)
                .userId(owner.getId())
                .scope(RoleScope.ORGANIZATION)
                .organizationId(org.getId())
                .decision(AuthorizationDecision.allow())
                .build();

        auditRecorder.record(event);
        entityManager.flush();

        // Attempting direct SQL DELETE should be blocked by trigger trg_prevent_audit_log_update_delete
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                    "DELETE FROM authorization_audit_events WHERE event_id = :eventId"
            ).setParameter("eventId", eventId.toString()).executeUpdate();
            entityManager.flush();
        }).isInstanceOf(Exception.class)
                .hasMessageContaining("Audit records are append-only");
    }

    private User createUser(String username) {
        return userRepository.save(User.builder()
                .username(username + "-" + UUID.randomUUID().toString().substring(0, 8))
                .email(username + "-" + UUID.randomUUID().toString().substring(0, 8) + "@devflow.io")
                .passwordHash("$2a$10$hashedpassword")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());
    }

    private OrganizationMember addMember(Organization org, User user, OrganizationRole role) {
        return organizationMemberRepository.save(OrganizationMember.builder()
                .organization(org)
                .user(user)
                .role(role)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());
    }
}
