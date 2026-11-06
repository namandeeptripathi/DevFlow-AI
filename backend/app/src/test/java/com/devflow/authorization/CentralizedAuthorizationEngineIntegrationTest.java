package com.devflow.authorization;

import com.devflow.audit.LoggingAuthorizationAuditRecorder;
import com.devflow.modules.auth.api.AuthApi;
import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.AuthorizationRequest;
import com.devflow.modules.auth.api.DenialReason;
import com.devflow.modules.auth.audit.AuthorizationAuditEvent;
import com.devflow.modules.auth.audit.AuthorizationAuditEventType;
import com.devflow.modules.auth.context.OrganizationAuthorizationContext;
import com.devflow.modules.auth.context.PlatformAuthorizationContext;
import com.devflow.modules.auth.context.ProjectAuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.evaluator.AuthorizationEvaluator;
import com.devflow.modules.auth.evaluator.MembershipValidator;
import com.devflow.modules.auth.evaluator.RoleResolver;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.modules.auth.registry.RolePermissionMapping;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.role.adapter.DatabaseMembershipValidator;
import com.devflow.role.adapter.DatabaseProjectAssignmentValidator;
import com.devflow.role.adapter.DatabaseResourceOwnershipValidator;
import com.devflow.role.adapter.DatabaseResourceStateValidator;
import com.devflow.role.adapter.DatabaseRoleResolver;
import com.devflow.role.adapter.DatabaseTenantBoundaryValidator;
import com.devflow.role.adapter.DefaultEmergencyAccessValidator;
import com.devflow.role.adapter.DefaultPlatformSupportValidator;
import com.devflow.role.domain.RoleEntity;
import com.devflow.role.repository.OrganizationRoleAssignmentRepository;
import com.devflow.role.repository.RoleRepository;
import com.devflow.role.service.RoleAssignmentService;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Stage 5: Centralized Authorization Engine Integration Tests")
class CentralizedAuthorizationEngineIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AuthApi authApi;

    @Autowired
    private PermissionRegistry permissionRegistry;

    @Autowired
    private RolePermissionMapping rolePermissionMapping;

    @Autowired
    private DatabaseRoleResolver databaseRoleResolver;

    @Autowired
    private DatabaseMembershipValidator databaseMembershipValidator;

    @Autowired
    private DatabaseProjectAssignmentValidator databaseProjectAssignmentValidator;

    @Autowired
    private DatabaseTenantBoundaryValidator databaseTenantBoundaryValidator;

    @Autowired
    private DatabaseResourceOwnershipValidator databaseResourceOwnershipValidator;

    @Autowired
    private DatabaseResourceStateValidator databaseResourceStateValidator;

    @Autowired
    private DefaultPlatformSupportValidator platformSupportValidator;

    @Autowired
    private DefaultEmergencyAccessValidator emergencyAccessValidator;

    @Autowired
    private LoggingAuthorizationAuditRecorder auditRecorder;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private OrganizationRoleAssignmentRepository orgAssignmentRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private EntityManager entityManager;

    private User testOwner;
    private User testAdmin;
    private User testMember;
    private User testBillingUser;
    private Organization testOrg;

    @BeforeEach
    void setUp() {
        auditRecorder.clearEvents();
        platformSupportValidator.clearAll();
        emergencyAccessValidator.clearAll();

        testOwner = userRepository.save(User.builder()
                .email("owner-" + UUID.randomUUID() + "@devflow.com")
                .username("owner_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed_pw")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        testAdmin = userRepository.save(User.builder()
                .email("admin-" + UUID.randomUUID() + "@devflow.com")
                .username("admin_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed_pw")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        testMember = userRepository.save(User.builder()
                .email("member-" + UUID.randomUUID() + "@devflow.com")
                .username("member_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed_pw")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        testBillingUser = userRepository.save(User.builder()
                .email("billing-" + UUID.randomUUID() + "@devflow.com")
                .username("billing_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed_pw")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        testOrg = organizationRepository.save(Organization.builder()
                .name("Engine Test Org")
                .slug("engine-org-" + UUID.randomUUID().toString().substring(0, 8))
                .owner(testOwner)
                .build());

        // Assign OWNER membership
        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(testOrg)
                .user(testOwner)
                .role(OrganizationRole.OWNER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .joinedAt(Instant.now())
                .build());

        // Assign ADMIN membership
        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(testOrg)
                .user(testAdmin)
                .role(OrganizationRole.ADMIN)
                .status(OrganizationMembershipStatus.ACTIVE)
                .joinedAt(Instant.now())
                .build());

        // Assign DEVELOPER membership (maps to ORGANIZATION_MEMBER)
        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(testOrg)
                .user(testMember)
                .role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .joinedAt(Instant.now())
                .build());

        // Assign Billing membership
        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(testOrg)
                .user(testBillingUser)
                .role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .joinedAt(Instant.now())
                .build());

        entityManager.flush();
    }

    // ── 1. CONTEXT WIRING ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Application Context Wiring")
    class ContextWiringTests {

        @Test
        @DisplayName("All centralized authorization engine components and SPI adapters are loaded into Spring context")
        void contextWiresAllAuthorizationBeans() {
            assertThat(applicationContext.getBean(AuthApi.class)).isNotNull().isSameAs(authApi);
            assertThat(applicationContext.getBean(PermissionRegistry.class)).isNotNull();
            assertThat(applicationContext.getBean(RolePermissionMapping.class)).isNotNull();
            assertThat(applicationContext.getBean(DatabaseRoleResolver.class)).isNotNull();
            assertThat(applicationContext.getBean(DatabaseMembershipValidator.class)).isNotNull();
            assertThat(applicationContext.getBean(DatabaseProjectAssignmentValidator.class)).isNotNull();
            assertThat(applicationContext.getBean(DatabaseTenantBoundaryValidator.class)).isNotNull();
            assertThat(applicationContext.getBean(DatabaseResourceOwnershipValidator.class)).isNotNull();
            assertThat(applicationContext.getBean(DatabaseResourceStateValidator.class)).isNotNull();
            assertThat(applicationContext.getBean(DefaultPlatformSupportValidator.class)).isNotNull();
            assertThat(applicationContext.getBean(DefaultEmergencyAccessValidator.class)).isNotNull();
            assertThat(applicationContext.getBean(LoggingAuthorizationAuditRecorder.class)).isNotNull();
        }
    }

    // ── 2. DATABASE ROLE RESOLUTION ───────────────────────────────────────────

    @Nested
    @DisplayName("Database Role Resolution & Permission Union")
    class RoleResolutionAndUnionTests {

        @Test
        @DisplayName("User with multiple persistent roles receives permission union")
        void multipleRoles_permissionUnion() {
            // Assign both ORGANIZATION_MEMBER and ORGANIZATION_BILLING to testMember
            roleAssignmentService.assignOrganizationRole(
                    testOrg.getId(), testMember.getId(), Role.ORGANIZATION_MEMBER, testOwner.getId(), null);
            roleAssignmentService.assignOrganizationRole(
                    testOrg.getId(), testMember.getId(), Role.ORGANIZATION_BILLING, testOwner.getId(), null);

            entityManager.flush();
            entityManager.clear();

            OrganizationAuthorizationContext context = OrganizationAuthorizationContext.of(testOrg.getId());

            // User now holds both ORGANIZATION_MEMBER and ORGANIZATION_BILLING
            Set<Role> roles = databaseRoleResolver.resolveRoles(testMember.getId(), context);
            assertThat(roles).contains(Role.ORGANIZATION_BILLING);

            // Member can perform engineering tasks (from ORGANIZATION_MEMBER)
            AuthorizationDecision taskCreate = authApi.authorize(AuthorizationRequest.organization(
                    testMember.getId(), testOrg.getId(), PermissionRegistry.TASK_CREATE));
            assertThat(taskCreate.allowed()).isTrue();

            // Member can also manage billing (from ORGANIZATION_BILLING union)
            AuthorizationDecision billingManage = authApi.authorize(AuthorizationRequest.organization(
                    testMember.getId(), testOrg.getId(), PermissionRegistry.BILLING_MANAGE));
            assertThat(billingManage.allowed()).isTrue();

            // Member CANNOT manage organization or delete it (owner-only)
            AuthorizationDecision orgDelete = authApi.authorize(AuthorizationRequest.organization(
                    testMember.getId(), testOrg.getId(), PermissionRegistry.ORG_DELETE));
            assertThat(orgDelete.allowed()).isFalse();
            assertThat(orgDelete.denialReason()).isEqualTo(DenialReason.INSUFFICIENT_PERMISSIONS);
        }
    }

    // ── 3. SUSPENDED MEMBERSHIP FAIL-CLOSED ────────────────────────────────────

    @Nested
    @DisplayName("Active vs Suspended Membership")
    class MembershipStateTests {

        @Test
        @DisplayName("Suspended organization member is denied all operations immediately")
        void suspendedMember_failsClosed() {
            // Suspend member
            OrganizationMember memberEntity = organizationMemberRepository
                    .findByOrganizationIdAndUserId(testOrg.getId(), testMember.getId()).orElseThrow();
            memberEntity.setStatus(OrganizationMembershipStatus.SUSPENDED);
            organizationMemberRepository.save(memberEntity);

            entityManager.flush();
            entityManager.clear();

            // Evaluator checks fail closed with MEMBERSHIP_INACTIVE
            AuthorizationDecision decision = authApi.authorize(AuthorizationRequest.organization(
                    testMember.getId(), testOrg.getId(), PermissionRegistry.PROJECT_READ));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denialReason()).isEqualTo(DenialReason.MEMBERSHIP_INACTIVE);

            // Audit record logged for membership denial
            List<AuthorizationAuditEvent> events = auditRecorder.getRecordedEvents();
            assertThat(events).anyMatch(e -> e.eventType() == AuthorizationAuditEventType.MEMBERSHIP_DENIAL
                    && e.userId().equals(testMember.getId()));
        }
    }

    // ── 4. BILLING ISOLATION ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Billing Privilege Isolation")
    class BillingIsolationTests {

        @Test
        @DisplayName("Organization ADMIN is strictly denied billing management permissions")
        void adminCannotAccessBilling() {
            AuthorizationDecision decision = authApi.authorize(AuthorizationRequest.organization(
                    testAdmin.getId(), testOrg.getId(), PermissionRegistry.BILLING_MANAGE));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denialReason()).isEqualTo(DenialReason.INSUFFICIENT_PERMISSIONS);
        }

        @Test
        @DisplayName("Organization OWNER holds full billing management permissions")
        void ownerCanAccessBilling() {
            AuthorizationDecision decision = authApi.authorize(AuthorizationRequest.organization(
                    testOwner.getId(), testOrg.getId(), PermissionRegistry.BILLING_MANAGE));

            assertThat(decision.allowed()).isTrue();
        }

        @Test
        @DisplayName("Dedicated ORGANIZATION_BILLING role allows billing operations but denies engineering modifications")
        void dedicatedBillingRole_isolatedFromEngineering() {
            roleAssignmentService.assignOrganizationRole(
                    testOrg.getId(), testBillingUser.getId(), Role.ORGANIZATION_BILLING, testOwner.getId(), null);

            entityManager.flush();
            entityManager.clear();

            // Billing access allowed
            AuthorizationDecision billingDecision = authApi.authorize(AuthorizationRequest.organization(
                    testBillingUser.getId(), testOrg.getId(), PermissionRegistry.BILLING_VIEW));
            assertThat(billingDecision.allowed()).isTrue();

            // Engineering write denied
            AuthorizationDecision taskDecision = authApi.authorize(AuthorizationRequest.organization(
                    testBillingUser.getId(), testOrg.getId(), PermissionRegistry.TASK_CREATE));
            assertThat(taskDecision.allowed()).isFalse();
        }
    }

    // ── 5. PLATFORM SECURITY GATES ────────────────────────────────────────────

    @Nested
    @DisplayName("Platform Support & Emergency Access Gates")
    class PlatformSecurityGatesTests {

        @Test
        @DisplayName("Platform Support Case Gate: unverified support access is denied; verified is granted")
        void platformSupportCaseGate() {
            UUID supportEngineerId = UUID.randomUUID();
            String caseId = "CASE-12345";

            // Unverified support access attempt fails closed
            AuthorizationRequest unverifiedReq = new AuthorizationRequest(
                    supportEngineerId,
                    PlatformAuthorizationContext.instance(),
                    PermissionRegistry.PLATFORM_SUPPORT_CASE_READ,
                    caseId,
                    Map.of("targetOrganizationId", testOrg.getId(), "supportCaseId", caseId)
            );

            AuthorizationDecision unverifiedDecision = authApi.authorize(unverifiedReq);
            assertThat(unverifiedDecision.allowed()).isFalse();
            assertThat(unverifiedDecision.denialReason()).isEqualTo(DenialReason.SUPPORT_CASE_UNAUTHORIZED);

            // Register verified support case
            platformSupportValidator.registerVerifiedSupportCase(
                    supportEngineerId, testOrg.getId(), caseId, Instant.now().plus(Duration.ofHours(2)));

            // Note: In addition to the gate check, RoleResolver must resolve a platform role.
            // Using AuthorizationEvaluator builder directly to test gate isolation:
            AuthorizationEvaluator gateEvaluator = AuthorizationEvaluator.builder()
                    .platformSupportValidator(platformSupportValidator)
                    .roleResolver((userId, ctx) -> Set.of(Role.PLATFORM_SUPPORT))
                    .build();

            AuthorizationDecision verifiedDecision = gateEvaluator.authorize(unverifiedReq);
            assertThat(verifiedDecision.allowed()).isTrue();
        }

        @Test
        @DisplayName("Emergency Access Gate: unapproved fails; approved within 4h succeeds; expired (>4h) fails")
        void emergencyAccessGate() {
            UUID adminId = UUID.randomUUID();
            UUID securityOfficerId = UUID.randomUUID();

            AuthorizationRequest emergencyReq = new AuthorizationRequest(
                    adminId,
                    PlatformAuthorizationContext.instance(),
                    PermissionRegistry.PLATFORM_EMERGENCY_ACCESS_EXECUTE,
                    null,
                    Collections.emptyMap()
            );

            // 1. Unapproved emergency access fails closed
            AuthorizationDecision unapproved = authApi.authorize(emergencyReq);
            assertThat(unapproved.allowed()).isFalse();
            assertThat(unapproved.denialReason()).isEqualTo(DenialReason.EMERGENCY_ACCESS_NOT_APPROVED);

            // 2. Grant 3-hour emergency access
            Instant approvalTime = Instant.now();
            emergencyAccessValidator.grantEmergencyAccess(
                    adminId, securityOfficerId, approvalTime, Duration.ofHours(3));

            AuthorizationEvaluator emergencyEvaluator = AuthorizationEvaluator.builder()
                    .emergencyAccessValidator(emergencyAccessValidator)
                    .roleResolver((userId, ctx) -> Set.of(Role.PLATFORM_ADMIN))
                    .build();

            AuthorizationDecision approvedDecision = emergencyEvaluator.authorize(emergencyReq);
            assertThat(approvedDecision.allowed()).isTrue();

            // 3. Evaluation after window expiration fails closed
            assertThat(emergencyAccessValidator.isEmergencyAccessAuthorized(
                    adminId, approvalTime.plus(Duration.ofHours(3).plusSeconds(1)))).isFalse();
        }
    }

    // ── 6. MISSING VALIDATOR FAIL-CLOSED ──────────────────────────────────────

    @Nested
    @DisplayName("Fail-Closed Security Invariants")
    class FailClosedTests {

        @Test
        @DisplayName("Missing security-critical validators default to failing closed")
        void missingValidatorsFailClosed() {
            // Construct minimal evaluator with zero explicit validator implementations
            AuthorizationEvaluator minimalEvaluator = AuthorizationEvaluator.builder()
                    .build();

            // Membership check fails closed
            AuthorizationDecision membershipDecision = minimalEvaluator.authorize(
                    AuthorizationRequest.organization(UUID.randomUUID(), testOrg.getId(), PermissionRegistry.PROJECT_READ));
            assertThat(membershipDecision.allowed()).isFalse();

            // Unknown permission check fails closed
            Permission unknownPerm = Permission.of("nonexistent", "action");
            AuthorizationDecision unknownDecision = minimalEvaluator.authorize(
                    AuthorizationRequest.organization(testOwner.getId(), testOrg.getId(), unknownPerm));
            assertThat(unknownDecision.allowed()).isFalse();
            assertThat(unknownDecision.denialReason()).isEqualTo(DenialReason.UNKNOWN_PERMISSION);
        }
    }
}
