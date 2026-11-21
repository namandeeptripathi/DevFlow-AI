package com.devflow.role;

import com.devflow.config.JpaAuditingConfiguration;
import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.AuthorizationRequest;
import com.devflow.modules.auth.api.DenialReason;
import com.devflow.modules.auth.context.OrganizationAuthorizationContext;
import com.devflow.modules.auth.context.ProjectAuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.domain.RoleScope;
import com.devflow.modules.auth.evaluator.AuthorizationEvaluator;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.role.adapter.DatabaseMembershipValidator;
import com.devflow.role.adapter.DatabaseProjectAssignmentValidator;
import com.devflow.role.adapter.DatabaseRoleResolver;
import com.devflow.role.adapter.DatabaseTenantBoundaryValidator;
import com.devflow.role.domain.OrganizationRoleAssignment;
import com.devflow.role.domain.ProjectRoleAssignment;
import com.devflow.role.domain.RoleEntity;
import com.devflow.role.exception.InvalidProjectRegistrationException;
import com.devflow.role.exception.InvalidRoleAssignmentException;
import com.devflow.role.exception.RoleAssignmentAccessDeniedException;
import com.devflow.role.exception.RoleAssignmentAlreadyExistsException;
import com.devflow.modules.auth.evaluator.TenantBoundaryValidator;
import com.devflow.role.registry.DatabaseProjectRegistry;
import com.devflow.role.registry.ProjectRegistry;
import com.devflow.role.repository.OrganizationRoleAssignmentRepository;
import com.devflow.role.repository.ProjectRoleAssignmentRepository;
import com.devflow.role.repository.RoleRepository;
import com.devflow.role.service.RoleAssignmentService;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({
    JpaAuditingConfiguration.class,
    RoleAssignmentService.class,
    DatabaseRoleResolver.class,
    DatabaseMembershipValidator.class,
    DatabaseProjectAssignmentValidator.class,
    DatabaseTenantBoundaryValidator.class,
    DatabaseProjectRegistry.class
})
@ActiveProfiles("test")
@DisplayName("Scoped Role Assignment Persistence & Security Invariants Integration Tests")
class ScopedRoleAssignmentDatabaseIntegrationTest {

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private DatabaseTenantBoundaryValidator databaseTenantBoundaryValidator;

    @Autowired(required = false)
    private ProjectRegistry projectRegistry;

    @Autowired
    private OrganizationRoleAssignmentRepository orgAssignmentRepository;

    @Autowired
    private ProjectRoleAssignmentRepository projectAssignmentRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseRoleResolver databaseRoleResolver;

    @Autowired
    private DatabaseMembershipValidator databaseMembershipValidator;

    @Autowired
    private DatabaseProjectAssignmentValidator databaseProjectAssignmentValidator;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User owner;
    private User adminUser;
    private User devUser;
    private User outsiderUser;
    private User adminB;
    private Organization orgA;
    private Organization orgB;
    private UUID projectA1;
    private UUID projectA2;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.builder()
                .email("owner-" + UUID.randomUUID() + "@devflow.com")
                .username("owner_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        adminUser = userRepository.save(User.builder()
                .email("admin-" + UUID.randomUUID() + "@devflow.com")
                .username("admin_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        devUser = userRepository.save(User.builder()
                .email("dev-" + UUID.randomUUID() + "@devflow.com")
                .username("dev_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        outsiderUser = userRepository.save(User.builder()
                .email("outsider-" + UUID.randomUUID() + "@devflow.com")
                .username("out_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        orgA = organizationRepository.save(Organization.builder()
                .name("Acme Engineering")
                .slug("acme-" + UUID.randomUUID().toString().substring(0, 8))
                .owner(owner)
                .build());

        orgB = organizationRepository.save(Organization.builder()
                .name("Beta Global")
                .slug("beta-" + UUID.randomUUID().toString().substring(0, 8))
                .owner(owner)
                .build());

        projectA1 = UUID.randomUUID();
        projectA2 = UUID.randomUUID();

        if (projectRegistry != null) {
            projectRegistry.clear();
            projectRegistry.registerProject(orgA.getId(), projectA1);
            projectRegistry.registerProject(orgA.getId(), projectA2);
        }

        roleAssignmentService.setTenantBoundaryValidator(databaseTenantBoundaryValidator);
        if (projectRegistry != null) {
            roleAssignmentService.setProjectRegistry(projectRegistry);
        }

        // Establish organization memberships for Org A
        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA)
                .user(owner)
                .role(OrganizationRole.OWNER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA)
                .user(adminUser)
                .role(OrganizationRole.ADMIN)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA)
                .user(devUser)
                .role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        // betaUser in Org B
        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgB)
                .user(outsiderUser)
                .role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        adminB = userRepository.save(User.builder()
                .email("adminB-" + UUID.randomUUID() + "@devflow.com")
                .username("adminB_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgB)
                .user(adminB)
                .role(OrganizationRole.ADMIN)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("Create organization role assignment successfully")
    void shouldCreateOrganizationRoleAssignment() {
        OrganizationRoleAssignment assignment = roleAssignmentService.assignOrganizationRole(
                orgA.getId(),
                devUser.getId(),
                Role.ORGANIZATION_ADMIN,
                owner.getId(),
                null
        );

        assertThat(assignment.getId()).isNotNull();
        assertThat(assignment.getRole().getName()).isEqualTo("ORGANIZATION_ADMIN");
        assertThat(assignment.isActive()).isTrue();
        assertThat(assignment.getAssignedBy().getId()).isEqualTo(owner.getId());
    }

    @Test
    @DisplayName("Support multiple active organization role assignments for same user (e.g. MEMBER + BILLING)")
    void shouldSupportMultipleOrganizationRoleAssignments() {
        // Assign MEMBER
        roleAssignmentService.assignOrganizationRole(
                orgA.getId(), devUser.getId(), Role.ORGANIZATION_MEMBER, owner.getId(), null);

        // Assign BILLING
        roleAssignmentService.assignOrganizationRole(
                orgA.getId(), devUser.getId(), Role.ORGANIZATION_BILLING, owner.getId(), null);

        entityManager.flush();
        entityManager.clear();

        Set<Role> roles = roleAssignmentService.getActiveOrganizationRoles(orgA.getId(), devUser.getId());
        assertThat(roles).containsExactlyInAnyOrder(Role.ORGANIZATION_MEMBER, Role.ORGANIZATION_BILLING);
    }

    @Test
    @DisplayName("Create project role assignments across multiple projects")
    void shouldCreateProjectRoleAssignmentsAcrossMultipleProjects() {
        ProjectRoleAssignment assignP1 = roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_LEAD, adminUser.getId(), null);

        ProjectRoleAssignment assignP2 = roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA2, devUser.getId(), Role.PROJECT_MEMBER, adminUser.getId(), null);

        assertThat(assignP1.getRole().getName()).isEqualTo("PROJECT_LEAD");
        assertThat(assignP2.getRole().getName()).isEqualTo("PROJECT_MEMBER");

        Set<Role> p1Roles = roleAssignmentService.getActiveProjectRoles(orgA.getId(), projectA1, devUser.getId());
        assertThat(p1Roles).containsExactly(Role.PROJECT_LEAD);

        Set<Role> p2Roles = roleAssignmentService.getActiveProjectRoles(orgA.getId(), projectA2, devUser.getId());
        assertThat(p2Roles).containsExactly(Role.PROJECT_MEMBER);
    }

    @Test
    @DisplayName("Security invariant 1: Reject cross-tenant project assignment at database constraint level")
    void shouldRejectCrossTenantProjectAssignmentAtDatabaseLevel() {
        // devUser belongs to Org A, NOT Org B
        RoleEntity projectMemberRole = roleRepository.findByName("PROJECT_MEMBER").orElseThrow();
        Organization managedOrgB = organizationRepository.findById(orgB.getId()).orElseThrow();
        User managedDevUser = userRepository.findById(devUser.getId()).orElseThrow();
        User managedOwner = userRepository.findById(owner.getId()).orElseThrow();

        ProjectRoleAssignment crossTenantAssignment = ProjectRoleAssignment.builder()
                .projectId(UUID.randomUUID())
                .organization(managedOrgB) // Org B!
                .user(managedDevUser)      // Member of Org A!
                .role(projectMemberRole)
                .roleScope(RoleScope.PROJECT)
                .assignedBy(managedOwner)
                .assignedAt(Instant.now())
                .build();

        // Foreign key constraint fk_project_role_assignments_org_user must reject this insert!
        assertThatThrownBy(() -> {
            projectAssignmentRepository.saveAndFlush(crossTenantAssignment);
        }).isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("fk_project_role_assignments_org_user");
    }

    @Test
    @DisplayName("Security invariant 1: Reject cross-tenant organization assignment at database constraint level")
    void shouldRejectCrossTenantOrganizationAssignmentAtDatabaseLevel() {
        RoleEntity orgMemberRole = roleRepository.findByName("ORGANIZATION_MEMBER").orElseThrow();
        Organization managedOrgB = organizationRepository.findById(orgB.getId()).orElseThrow();
        User managedDevUser = userRepository.findById(devUser.getId()).orElseThrow();
        User managedOwner = userRepository.findById(owner.getId()).orElseThrow();

        OrganizationRoleAssignment crossTenantOrgAssignment = OrganizationRoleAssignment.builder()
                .organization(managedOrgB) // Org B!
                .user(managedDevUser)      // Member of Org A!
                .role(orgMemberRole)
                .roleScope(RoleScope.ORGANIZATION)
                .assignedBy(managedOwner)
                .assignedAt(Instant.now())
                .build();

        assertThatThrownBy(() -> {
            orgAssignmentRepository.saveAndFlush(crossTenantOrgAssignment);
        }).isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("fk_org_role_assignments_org_user");
    }

    @Test
    @DisplayName("Security invariant 2: Reject project assignment when organization member is SUSPENDED")
    void shouldRejectProjectAssignmentWhenMemberIsSuspended() {
        // Suspend devUser
        OrganizationMember member = organizationMemberRepository
                .findByOrganizationIdAndUserId(orgA.getId(), devUser.getId()).orElseThrow();
        member.setStatus(OrganizationMembershipStatus.SUSPENDED);
        organizationMemberRepository.saveAndFlush(member);

        assertThatThrownBy(() -> {
            roleAssignmentService.assignProjectRole(
                    orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_MEMBER, adminUser.getId(), null);
        }).isInstanceOf(InvalidRoleAssignmentException.class)
          .hasMessageContaining("ACTIVE required");
    }

    @Test
    @DisplayName("Security invariants 3 & 4: Reject assigning platform role to organization at database constraint level")
    void shouldRejectAssigningPlatformRoleToOrganizationAtDatabaseLevel() {
        RoleEntity platformAdminRole = roleRepository.findByName("PLATFORM_ADMIN").orElseThrow();
        Organization managedOrgA = organizationRepository.findById(orgA.getId()).orElseThrow();
        User managedDevUser = userRepository.findById(devUser.getId()).orElseThrow();
        User managedOwner = userRepository.findById(owner.getId()).orElseThrow();

        // Attempting to assign PLATFORM_ADMIN to organization_role_assignments fails constraint
        OrganizationRoleAssignment invalidOrgAssign = OrganizationRoleAssignment.builder()
                .organization(managedOrgA)
                .user(managedDevUser)
                .role(platformAdminRole) // Scope is PLATFORM!
                .roleScope(RoleScope.ORGANIZATION)
                .assignedBy(managedOwner)
                .assignedAt(Instant.now())
                .build();

        assertThatThrownBy(() -> {
            orgAssignmentRepository.saveAndFlush(invalidOrgAssign);
        }).isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("fk_org_role_assignments_role_scope");
    }

    @Test
    @DisplayName("Security invariants 3 & 4: Reject assigning organization role to project at database constraint level")
    void shouldRejectAssigningOrganizationRoleToProjectAtDatabaseLevel() {
        RoleEntity orgAdminRole = roleRepository.findByName("ORGANIZATION_ADMIN").orElseThrow();
        Organization managedOrgA = organizationRepository.findById(orgA.getId()).orElseThrow();
        User managedDevUser = userRepository.findById(devUser.getId()).orElseThrow();
        User managedOwner = userRepository.findById(owner.getId()).orElseThrow();

        // Attempting to assign ORGANIZATION_ADMIN to project_role_assignments fails constraint
        ProjectRoleAssignment invalidProjectAssign = ProjectRoleAssignment.builder()
                .projectId(projectA1)
                .organization(managedOrgA)
                .user(managedDevUser)
                .role(orgAdminRole) // Scope is ORGANIZATION!
                .roleScope(RoleScope.PROJECT)
                .assignedBy(managedOwner)
                .assignedAt(Instant.now())
                .build();

        assertThatThrownBy(() -> {
            projectAssignmentRepository.saveAndFlush(invalidProjectAssign);
        }).isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("fk_project_role_assignments_role_scope");
    }

    @Test
    @DisplayName("Prevent duplicate active organization role assignments")
    void shouldPreventDuplicateActiveOrganizationRoleAssignments() {
        roleAssignmentService.assignOrganizationRole(
                orgA.getId(), devUser.getId(), Role.ORGANIZATION_ADMIN, owner.getId(), null);

        assertThatThrownBy(() -> {
            roleAssignmentService.assignOrganizationRole(
                    orgA.getId(), devUser.getId(), Role.ORGANIZATION_ADMIN, owner.getId(), null);
        }).isInstanceOf(RoleAssignmentAlreadyExistsException.class)
          .hasMessageContaining("already holds an active [ORGANIZATION_ADMIN]");
    }

    @Test
    @DisplayName("Prevent duplicate active project role assignments")
    void shouldPreventDuplicateActiveProjectRoleAssignments() {
        roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_LEAD, adminUser.getId(), null);

        assertThatThrownBy(() -> {
            roleAssignmentService.assignProjectRole(
                    orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_LEAD, adminUser.getId(), null);
        }).isInstanceOf(RoleAssignmentAlreadyExistsException.class)
          .hasMessageContaining("already holds an active [PROJECT_LEAD]");
    }

    @Test
    @DisplayName("Support role assignment revocation and subsequent re-assignment")
    void shouldSupportRoleAssignmentRevocationAndReassignment() {
        OrganizationRoleAssignment assignment = roleAssignmentService.assignOrganizationRole(
                orgA.getId(), devUser.getId(), Role.ORGANIZATION_ADMIN, owner.getId(), null);

        assertThat(assignment.isActive()).isTrue();

        // Revoke the role
        OrganizationRoleAssignment revoked = roleAssignmentService.revokeOrganizationRole(
                orgA.getId(), devUser.getId(), Role.ORGANIZATION_ADMIN, owner.getId(), "Role rotation");

        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.isActive()).isFalse();
        assertThat(revoked.getRevocationReason()).isEqualTo("Role rotation");
        assertThat(revoked.getRevokedBy().getId()).isEqualTo(owner.getId());

        // DatabaseRoleResolver no longer returns the revoked role
        Set<Role> activeRoles = databaseRoleResolver.resolveRoles(
                devUser.getId(), OrganizationAuthorizationContext.of(orgA.getId()));
        assertThat(activeRoles).doesNotContain(Role.ORGANIZATION_ADMIN);

        // Verify that after revocation, a new active assignment of the same role can be created
        OrganizationRoleAssignment reassigned = roleAssignmentService.assignOrganizationRole(
                orgA.getId(), devUser.getId(), Role.ORGANIZATION_ADMIN, owner.getId(), null);
        assertThat(reassigned.isActive()).isTrue();
    }

    @Test
    @DisplayName("Support assignment expiration: expired assignment is excluded from active evaluations")
    void shouldExcludeExpiredRoleAssignments() {
        Instant pastExpiry = Instant.now().minus(1, ChronoUnit.HOURS);

        // Save expired assignment directly
        RoleEntity projectLeadRole = roleRepository.findByName("PROJECT_LEAD").orElseThrow();
        Organization managedOrgA = organizationRepository.findById(orgA.getId()).orElseThrow();
        User managedDevUser = userRepository.findById(devUser.getId()).orElseThrow();
        User managedOwner = userRepository.findById(owner.getId()).orElseThrow();

        projectAssignmentRepository.saveAndFlush(ProjectRoleAssignment.builder()
                .projectId(projectA1)
                .organization(managedOrgA)
                .user(managedDevUser)
                .role(projectLeadRole)
                .roleScope(RoleScope.PROJECT)
                .assignedBy(managedOwner)
                .assignedAt(Instant.now().minus(2, ChronoUnit.HOURS))
                .expiresAt(pastExpiry)
                .build());

        // isAssignedToProject validator returns false for expired assignment
        boolean isAssigned = databaseProjectAssignmentValidator.isAssignedToProject(
                devUser.getId(), orgA.getId(), projectA1);
        assertThat(isAssigned).isFalse();

        // DatabaseRoleResolver excludes expired project role
        Set<Role> resolvedRoles = databaseRoleResolver.resolveRoles(
                devUser.getId(), ProjectAuthorizationContext.of(orgA.getId(), projectA1));
        assertThat(resolvedRoles).doesNotContain(Role.PROJECT_LEAD);

        // Active project roles query returns empty
        Set<Role> activeProjectRoles = roleAssignmentService.getActiveProjectRoles(
                orgA.getId(), projectA1, devUser.getId());
        assertThat(activeProjectRoles).isEmpty();
    }

    @Test
    @DisplayName("Verify legacy DEVELOPER role in organization_members maps to canonical ORGANIZATION_MEMBER")
    void shouldMapLegacyDeveloperRoleToOrganizationMember() {
        // devUser has OrganizationRole.DEVELOPER in organization_members, with no new assignment records yet
        Set<Role> resolvedRoles = databaseRoleResolver.resolveRoles(
                devUser.getId(), OrganizationAuthorizationContext.of(orgA.getId()));

        assertThat(resolvedRoles).contains(Role.ORGANIZATION_MEMBER);
    }

    @Test
    @DisplayName("Verify suspended organization member resolves to zero roles")
    void shouldResolveZeroRolesForSuspendedMember() {
        // Assign active role
        roleAssignmentService.assignOrganizationRole(
                orgA.getId(), devUser.getId(), Role.ORGANIZATION_ADMIN, owner.getId(), null);

        // Suspend user in organization_members
        OrganizationMember member = organizationMemberRepository
                .findByOrganizationIdAndUserId(orgA.getId(), devUser.getId()).orElseThrow();
        member.setStatus(OrganizationMembershipStatus.SUSPENDED);
        organizationMemberRepository.saveAndFlush(member);

        Set<Role> orgRoles = databaseRoleResolver.resolveRoles(
                devUser.getId(), OrganizationAuthorizationContext.of(orgA.getId()));
        assertThat(orgRoles).isEmpty();

        Set<Role> projRoles = databaseRoleResolver.resolveRoles(
                devUser.getId(), ProjectAuthorizationContext.of(orgA.getId(), projectA1));
        assertThat(projRoles).isEmpty();
    }

    @Test
    @DisplayName("Cascade delete removes role assignments when organization member is deleted")
    void shouldCascadeDeleteAssignmentsWhenMemberIsRemoved() {
        roleAssignmentService.assignOrganizationRole(
                orgA.getId(), devUser.getId(), Role.ORGANIZATION_BILLING, owner.getId(), null);

        roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_MEMBER, adminUser.getId(), null);

        entityManager.flush();
        entityManager.clear();

        assertThat(orgAssignmentRepository.findByOrganizationIdAndUserId(orgA.getId(), devUser.getId())).hasSize(1);
        assertThat(projectAssignmentRepository.findByProjectIdAndUserId(projectA1, devUser.getId())).hasSize(1);

        // Remove the member
        OrganizationMember member = organizationMemberRepository
                .findByOrganizationIdAndUserId(orgA.getId(), devUser.getId()).orElseThrow();
        organizationMemberRepository.delete(member);
        organizationMemberRepository.flush();
        entityManager.clear();

        // Foreign keys ON DELETE CASCADE ensure clean removal
        assertThat(orgAssignmentRepository.findByOrganizationIdAndUserId(orgA.getId(), devUser.getId())).isEmpty();
        assertThat(projectAssignmentRepository.findByProjectIdAndUserId(projectA1, devUser.getId())).isEmpty();
    }

    @Test
    @DisplayName("End-to-End: Evaluate authorization requests using database-backed evaluator")
    void shouldEvaluateAuthorizationDecisionsWithDatabaseState() {
        // Build centralized Stage 3 AuthorizationEvaluator wired to our Stage 4 database adapters
        AuthorizationEvaluator evaluator = AuthorizationEvaluator.builder()
                .roleResolver(databaseRoleResolver)
                .membershipValidator(databaseMembershipValidator)
                .projectAssignmentValidator(databaseProjectAssignmentValidator)
                .tenantBoundaryValidator((orgId, projId) -> true)
                .build();

        // 1. devUser has ORGANIZATION_MEMBER via legacy membership, but NO project assignment yet
        AuthorizationRequest requestBeforeAssignment = AuthorizationRequest.project(
                devUser.getId(), orgA.getId(), projectA1, Permission.of("task.create"));

        AuthorizationDecision decisionBefore = evaluator.authorize(requestBeforeAssignment);
        assertThat(decisionBefore.allowed()).isFalse();
        assertThat(decisionBefore.getDenialReason()).contains(DenialReason.PROJECT_ASSIGNMENT_REQUIRED);

        // 2. Assign devUser PROJECT_MEMBER in projectA1
        roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_MEMBER, adminUser.getId(), null);

        AuthorizationDecision decisionAfter = evaluator.authorize(requestBeforeAssignment);
        assertThat(decisionAfter.allowed()).isTrue();

        // 3. Organization Owner inherits project admin rights without explicit project assignment
        AuthorizationRequest ownerProjectReq = AuthorizationRequest.project(
                owner.getId(), orgA.getId(), projectA1, Permission.of("project.delete"));

        AuthorizationDecision ownerDecision = evaluator.authorize(ownerProjectReq);
        assertThat(ownerDecision.allowed()).isTrue();
    }

    // ── Stage 4.1 Security Hardening Tests ────────────────────────────────────

    @Test
    @DisplayName("Stage 4.1 §1: Revocation must not restore revoked privilege through legacy fallback")
    void shouldNotRestoreRevokedPrivilegeThroughLegacyFallback() {
        // 1. User has active legacy ADMIN role in organization_members
        User legacyAdmin = userRepository.save(User.builder()
                .email("legacyadmin-" + UUID.randomUUID() + "@devflow.com")
                .username("legadm_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA)
                .user(legacyAdmin)
                .role(OrganizationRole.ADMIN)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        // Prior to explicit assignment, legacy ADMIN resolves to ORGANIZATION_ADMIN
        Set<Role> rolesInitial = databaseRoleResolver.resolveRoles(
                legacyAdmin.getId(), OrganizationAuthorizationContext.of(orgA.getId()));
        assertThat(rolesInitial).containsExactly(Role.ORGANIZATION_ADMIN);

        // 2. An explicit role assignment is created
        OrganizationRoleAssignment assignment = roleAssignmentService.assignOrganizationRole(
                orgA.getId(), legacyAdmin.getId(), Role.ORGANIZATION_ADMIN, owner.getId(), null);
        assertThat(assignment.isActive()).isTrue();

        // 3. That explicit assignment is revoked
        roleAssignmentService.revokeOrganizationRole(
                orgA.getId(), legacyAdmin.getId(), Role.ORGANIZATION_ADMIN, owner.getId(), "Demotion");

        entityManager.flush();
        entityManager.clear();

        // 4. The resolver is called again: must NOT fall back to legacy ADMIN!
        Set<Role> rolesAfterRevocation = databaseRoleResolver.resolveRoles(
                legacyAdmin.getId(), OrganizationAuthorizationContext.of(orgA.getId()));

        assertThat(rolesAfterRevocation)
                .as("Revocation must not silently restore revoked privilege via legacy membership role fallback")
                .isEmpty();
    }

    @Test
    @DisplayName("Stage 4.1 §3: Expired assignment followed by reassignment supersedes old record and succeeds")
    void shouldReassignRoleWhenPreviousAssignmentIsExpired() {
        Instant pastExpiry = Instant.now().minus(1, ChronoUnit.HOURS);

        // Create an expired assignment directly
        RoleEntity memberRole = roleRepository.findByName("ORGANIZATION_MEMBER").orElseThrow();
        Organization managedOrgA = organizationRepository.findById(orgA.getId()).orElseThrow();
        User managedDevUser = userRepository.findById(devUser.getId()).orElseThrow();
        User managedOwner = userRepository.findById(owner.getId()).orElseThrow();

        OrganizationRoleAssignment expiredAssignment = orgAssignmentRepository.saveAndFlush(
                OrganizationRoleAssignment.builder()
                        .organization(managedOrgA)
                        .user(managedDevUser)
                        .role(memberRole)
                        .roleScope(RoleScope.ORGANIZATION)
                        .assignedBy(managedOwner)
                        .assignedAt(Instant.now().minus(2, ChronoUnit.HOURS))
                        .expiresAt(pastExpiry)
                        .build()
        );

        assertThat(expiredAssignment.isExpired(Instant.now())).isTrue();
        assertThat(expiredAssignment.isRevoked()).isFalse();

        // Reassign the same role: should supersede the expired record and create an active assignment
        OrganizationRoleAssignment reassigned = roleAssignmentService.assignOrganizationRole(
                orgA.getId(), devUser.getId(), Role.ORGANIZATION_MEMBER, owner.getId(), null);

        assertThat(reassigned.isActive()).isTrue();

        // Verify the old assignment was marked EXPIRED_SUPERSEDED to release the partial unique index slot
        OrganizationRoleAssignment oldArchived = orgAssignmentRepository.findById(expiredAssignment.getId()).orElseThrow();
        assertThat(oldArchived.isRevoked()).isTrue();
        assertThat(oldArchived.getRevocationReason()).isEqualTo("EXPIRED_SUPERSEDED");
    }

    @Test
    @DisplayName("Stage 4.1 §3: Expired project assignment followed by reassignment supersedes old record")
    void shouldReassignProjectRoleWhenPreviousAssignmentIsExpired() {
        Instant pastExpiry = Instant.now().minus(30, ChronoUnit.MINUTES);

        RoleEntity projectMemberRole = roleRepository.findByName("PROJECT_MEMBER").orElseThrow();
        Organization managedOrgA = organizationRepository.findById(orgA.getId()).orElseThrow();
        User managedDevUser = userRepository.findById(devUser.getId()).orElseThrow();
        User managedOwner = userRepository.findById(owner.getId()).orElseThrow();

        ProjectRoleAssignment expiredAssignment = projectAssignmentRepository.saveAndFlush(
                ProjectRoleAssignment.builder()
                        .projectId(projectA1)
                        .organization(managedOrgA)
                        .user(managedDevUser)
                        .role(projectMemberRole)
                        .roleScope(RoleScope.PROJECT)
                        .assignedBy(managedOwner)
                        .assignedAt(Instant.now().minus(2, ChronoUnit.HOURS))
                        .expiresAt(pastExpiry)
                        .build()
        );

        // Reassign the same role in the project
        ProjectRoleAssignment reassigned = roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_MEMBER, adminUser.getId(), null);

        assertThat(reassigned.isActive()).isTrue();

        ProjectRoleAssignment oldArchived = projectAssignmentRepository.findById(expiredAssignment.getId()).orElseThrow();
        assertThat(oldArchived.isRevoked()).isTrue();
        assertThat(oldArchived.getRevocationReason()).isEqualTo("EXPIRED_SUPERSEDED");
    }

    @Test
    @DisplayName("Stage 4.1 §3: Maintenance operation archives stale expired assignments")
    void shouldArchiveStaleExpiredAssignments() {
        Instant pastExpiry = Instant.now().minus(10, ChronoUnit.MINUTES);

        RoleEntity billingRole = roleRepository.findByName("ORGANIZATION_BILLING").orElseThrow();
        Organization managedOrgA = organizationRepository.findById(orgA.getId()).orElseThrow();
        User managedDevUser = userRepository.findById(devUser.getId()).orElseThrow();
        User managedOwner = userRepository.findById(owner.getId()).orElseThrow();

        OrganizationRoleAssignment expired = orgAssignmentRepository.saveAndFlush(
                OrganizationRoleAssignment.builder()
                        .organization(managedOrgA)
                        .user(managedDevUser)
                        .role(billingRole)
                        .roleScope(RoleScope.ORGANIZATION)
                        .assignedBy(managedOwner)
                        .assignedAt(Instant.now().minus(1, ChronoUnit.HOURS))
                        .expiresAt(pastExpiry)
                        .build()
        );

        int archivedCount = roleAssignmentService.expireStaleAssignments(Instant.now());
        assertThat(archivedCount).isGreaterThanOrEqualTo(1);

        OrganizationRoleAssignment updated = orgAssignmentRepository.findById(expired.getId()).orElseThrow();
        assertThat(updated.isRevoked()).isTrue();
        assertThat(updated.getRevocationReason()).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("Stage 4.1 §4: Organization Admin cannot grant ORGANIZATION_BILLING")
    void shouldDenyAdminFromGrantingBillingRole() {
        assertThatThrownBy(() -> {
            roleAssignmentService.assignOrganizationRole(
                    orgA.getId(), devUser.getId(), Role.ORGANIZATION_BILLING, adminUser.getId(), null);
        }).isInstanceOf(RoleAssignmentAccessDeniedException.class)
          .hasMessageContaining("Only ORGANIZATION_OWNER can grant ORGANIZATION_BILLING");
    }

    @Test
    @DisplayName("Stage 4.1 §4: Organization Admin cannot revoke ORGANIZATION_BILLING")
    void shouldDenyAdminFromRevokingBillingRole() {
        // Owner grants billing
        roleAssignmentService.assignOrganizationRole(
                orgA.getId(), devUser.getId(), Role.ORGANIZATION_BILLING, owner.getId(), null);

        // Admin attempts to revoke billing
        assertThatThrownBy(() -> {
            roleAssignmentService.revokeOrganizationRole(
                    orgA.getId(), devUser.getId(), Role.ORGANIZATION_BILLING, adminUser.getId(), "Admin revocation");
        }).isInstanceOf(RoleAssignmentAccessDeniedException.class)
          .hasMessageContaining("Only ORGANIZATION_OWNER can revoke ORGANIZATION_BILLING");
    }

    @Test
    @DisplayName("Stage 4.1 §4: Organization Admin cannot grant ORGANIZATION_OWNER role")
    void shouldDenyAdminFromGrantingOwnerRole() {
        assertThatThrownBy(() -> {
            roleAssignmentService.assignOrganizationRole(
                    orgA.getId(), devUser.getId(), Role.ORGANIZATION_OWNER, adminUser.getId(), null);
        }).isInstanceOf(RoleAssignmentAccessDeniedException.class)
          .hasMessageContaining("Only ORGANIZATION_OWNER can grant ORGANIZATION_OWNER");
    }

    @Test
    @DisplayName("Stage 4.1 §4: Organization Admin cannot revoke ORGANIZATION_OWNER role")
    void shouldDenyAdminFromRevokingOwnerRole() {
        // Add a second owner
        User owner2 = userRepository.save(User.builder()
                .email("owner2-" + UUID.randomUUID() + "@devflow.com")
                .username("own2_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA)
                .user(owner2)
                .role(OrganizationRole.OWNER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        roleAssignmentService.assignOrganizationRole(
                orgA.getId(), owner2.getId(), Role.ORGANIZATION_OWNER, owner.getId(), null);

        // Admin attempts to revoke owner2
        assertThatThrownBy(() -> {
            roleAssignmentService.revokeOrganizationRole(
                    orgA.getId(), owner2.getId(), Role.ORGANIZATION_OWNER, adminUser.getId(), "Takeover attempt");
        }).isInstanceOf(RoleAssignmentAccessDeniedException.class)
          .hasMessageContaining("Only ORGANIZATION_OWNER can revoke ORGANIZATION_OWNER");
    }

    @Test
    @DisplayName("Stage 4.1 §4: Cannot revoke sole active ORGANIZATION_OWNER")
    void shouldPreventRevokingSoleActiveOwner() {
        // Explicitly assign owner role to owner
        roleAssignmentService.assignOrganizationRole(
                orgA.getId(), owner.getId(), Role.ORGANIZATION_OWNER, owner.getId(), null);

        assertThatThrownBy(() -> {
            roleAssignmentService.revokeOrganizationRole(
                    orgA.getId(), owner.getId(), Role.ORGANIZATION_OWNER, owner.getId(), "Self revoke");
        }).isInstanceOf(RoleAssignmentAccessDeniedException.class)
          .hasMessageContaining("Cannot revoke sole active ORGANIZATION_OWNER");
    }

    @Test
    @DisplayName("Stage 4.1 §4: Non-lead member cannot assign project roles")
    void shouldDenyRegularMemberFromAssigningProjectRoles() {
        assertThatThrownBy(() -> {
            roleAssignmentService.assignProjectRole(
                    orgA.getId(), projectA1, adminUser.getId(), Role.PROJECT_MEMBER, devUser.getId(), null);
        }).isInstanceOf(RoleAssignmentAccessDeniedException.class)
          .hasMessageContaining("Requires ORGANIZATION_OWNER, ORGANIZATION_ADMIN, or PROJECT_LEAD");
    }

    @Test
    @DisplayName("Stage 4.1 §4: Project Lead can assign roles in their project but not another project")
    void shouldAllowProjectLeadToAssignInOwnProjectOnly() {
        User projectLeadUser = userRepository.save(User.builder()
                .email("lead-" + UUID.randomUUID() + "@devflow.com")
                .username("lead_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA)
                .user(projectLeadUser)
                .role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        // Assign PROJECT_LEAD in projectA1
        roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA1, projectLeadUser.getId(), Role.PROJECT_LEAD, adminUser.getId(), null);

        // Project lead assigns PROJECT_MEMBER in projectA1 -> SUCCESS
        ProjectRoleAssignment success = roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_MEMBER, projectLeadUser.getId(), null);
        assertThat(success.isActive()).isTrue();

        // Project lead attempts to assign PROJECT_MEMBER in projectA2 (where they have no role) -> ACCESS DENIED
        assertThatThrownBy(() -> {
            roleAssignmentService.assignProjectRole(
                    orgA.getId(), projectA2, devUser.getId(), Role.PROJECT_MEMBER, projectLeadUser.getId(), null);
        }).isInstanceOf(RoleAssignmentAccessDeniedException.class)
          .hasMessageContaining("Requires ORGANIZATION_OWNER, ORGANIZATION_ADMIN, or PROJECT_LEAD");
    }

    @Test
    @DisplayName("Stage 4.1 §2: Reject project assignment when TenantBoundaryValidator fails")
    void shouldRejectProjectAssignmentWhenTenantBoundaryValidatorRejectsProject() {
        UUID foreignProjectId = UUID.randomUUID();

        // Configure mock or lambda tenantBoundaryValidator that rejects foreignProjectId
        TenantBoundaryValidator validator = (orgId, projId) -> !projId.equals(foreignProjectId);
        roleAssignmentService.setTenantBoundaryValidator(validator);

        try {
            assertThatThrownBy(() -> {
                roleAssignmentService.assignProjectRole(
                        orgA.getId(), foreignProjectId, devUser.getId(), Role.PROJECT_MEMBER, adminUser.getId(), null);
            }).isInstanceOf(InvalidRoleAssignmentException.class)
              .hasMessageContaining("Tenant boundary violation");
        } finally {
            roleAssignmentService.setTenantBoundaryValidator(databaseTenantBoundaryValidator);
        }
    }

    // ── Security Tests: Implementation 2 — Fail-Closed Tenant Boundaries ────

    private AuthorizationEvaluator buildDatabaseWiredEvaluator() {
        return AuthorizationEvaluator.builder()
                .roleResolver(databaseRoleResolver)
                .membershipValidator(databaseMembershipValidator)
                .projectAssignmentValidator(databaseProjectAssignmentValidator)
                .tenantBoundaryValidator(databaseTenantBoundaryValidator)
                .build();
    }

    @Test
    @DisplayName("Security 1: Valid project and organization ownership authorizes successfully")
    void shouldAuthorizeValidProjectAndOrganizationOwnership() {
        roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_MEMBER, adminUser.getId(), null);

        assertThat(databaseTenantBoundaryValidator.isProjectInOrganization(orgA.getId(), projectA1)).isTrue();
        assertThat(databaseTenantBoundaryValidator.getResourceOrganizationId("project", projectA1.toString()))
                .contains(orgA.getId());

        AuthorizationEvaluator evaluator = buildDatabaseWiredEvaluator();
        AuthorizationDecision decision = evaluator.authorize(
                AuthorizationRequest.project(devUser.getId(), orgA.getId(), projectA1, Permission.of("task.create")));

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("Security 2: Project with no role assignments strictly fails closed (default-deny)")
    void shouldFailClosedWhenProjectHasNoRoleAssignments() {
        UUID unassignedProject = UUID.randomUUID();

        // 1. Validator must return false for unassigned project
        assertThat(databaseTenantBoundaryValidator.isProjectInOrganization(orgA.getId(), unassignedProject)).isFalse();

        // 2. Project assignment validator must fail closed
        assertThat(databaseProjectAssignmentValidator.isAssignedToProject(owner.getId(), orgA.getId(), unassignedProject)).isFalse();

        // 3. Central evaluator must deny even for organization owner
        AuthorizationEvaluator evaluator = buildDatabaseWiredEvaluator();
        AuthorizationDecision decision = evaluator.authorize(
                AuthorizationRequest.project(owner.getId(), orgA.getId(), unassignedProject, Permission.of("project.read")));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.getDenialReason()).contains(DenialReason.TENANT_MISMATCH);
    }

    @Test
    @DisplayName("Security 3: Project with no resolvable organization fails closed")
    void shouldFailClosedWhenProjectHasNoResolvableOrganization() {
        UUID nonExistentOrg = UUID.randomUUID();
        UUID unrecordedProject = UUID.randomUUID();

        assertThat(databaseTenantBoundaryValidator.isProjectInOrganization(nonExistentOrg, projectA1)).isFalse();
        assertThat(databaseTenantBoundaryValidator.getResourceOrganizationId("project", unrecordedProject.toString())).isEmpty();
        assertThat(databaseTenantBoundaryValidator.getResourceOrganizationId("organization", nonExistentOrg.toString())).isEmpty();
    }

    @Test
    @DisplayName("Security 4: User belonging to another organization is denied project access")
    void shouldDenyAccessWhenUserBelongsToAnotherOrganization() {
        roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_MEMBER, adminUser.getId(), null);

        AuthorizationEvaluator evaluator = buildDatabaseWiredEvaluator();
        // outsiderUser belongs to orgB, attempts to access orgA's projectA1 under orgA context
        AuthorizationDecision decision = evaluator.authorize(
                AuthorizationRequest.project(outsiderUser.getId(), orgA.getId(), projectA1, Permission.of("task.read")));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.getDenialReason()).contains(DenialReason.MEMBERSHIP_INACTIVE);
    }

    @Test
    @DisplayName("Security 5: User with organization membership but insufficient project permissions is denied")
    void shouldDenyAccessWhenUserHasInsufficientProjectPermissions() {
        roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_VIEWER, adminUser.getId(), null);

        AuthorizationEvaluator evaluator = buildDatabaseWiredEvaluator();
        AuthorizationDecision decision = evaluator.authorize(
                AuthorizationRequest.project(devUser.getId(), orgA.getId(), projectA1, Permission.of("cycle", "manage")));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.getDenialReason()).contains(DenialReason.INSUFFICIENT_PERMISSIONS);
    }

    @Test
    @DisplayName("Security 6: Revoked and expired project assignments are rejected (fail closed)")
    void shouldDenyAccessWhenProjectAssignmentIsRevokedOrExpired() {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_MEMBER, adminUser.getId(), past);

        AuthorizationEvaluator evaluator = buildDatabaseWiredEvaluator();

        // Expired assignment fails project assignment validator and evaluator
        assertThat(databaseProjectAssignmentValidator.isAssignedToProject(devUser.getId(), orgA.getId(), projectA1)).isFalse();
        AuthorizationDecision expiredDecision = evaluator.authorize(
                AuthorizationRequest.project(devUser.getId(), orgA.getId(), projectA1, Permission.of("task.create")));
        assertThat(expiredDecision.allowed()).isFalse();

        // Reassign valid, then revoke
        roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_MEMBER, adminUser.getId(), null);
        assertThat(databaseProjectAssignmentValidator.isAssignedToProject(devUser.getId(), orgA.getId(), projectA1)).isTrue();

        roleAssignmentService.revokeProjectRole(
                orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_MEMBER, adminUser.getId(), "Security testing");

        assertThat(databaseProjectAssignmentValidator.isAssignedToProject(devUser.getId(), orgA.getId(), projectA1)).isFalse();
        AuthorizationDecision revokedDecision = evaluator.authorize(
                AuthorizationRequest.project(devUser.getId(), orgA.getId(), projectA1, Permission.of("task.create")));
        assertThat(revokedDecision.allowed()).isFalse();
    }

    @Test
    @DisplayName("Security 7: Valid project lead and organization owner access is authorized")
    void shouldAuthorizeValidProjectLeadAndOwnerAccess() {
        roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_LEAD, adminUser.getId(), null);

        AuthorizationEvaluator evaluator = buildDatabaseWiredEvaluator();

        // Project lead has task.assign in project
        AuthorizationDecision leadDecision = evaluator.authorize(
                AuthorizationRequest.project(devUser.getId(), orgA.getId(), projectA1, Permission.of("task.assign")));
        assertThat(leadDecision.allowed()).isTrue();

        // Organization owner inherits project read authority
        AuthorizationDecision ownerDecision = evaluator.authorize(
                AuthorizationRequest.project(owner.getId(), orgA.getId(), projectA1, Permission.of("project.read")));
        assertThat(ownerDecision.allowed()).isTrue();
    }

    @Test
    @DisplayName("Security 8: Cross-organization project access and role assignment attempts are rejected")
    void shouldRejectCrossOrganizationProjectAccessAttempts() {
        roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_MEMBER, adminUser.getId(), null);

        // Project belongs to Org A, not Org B
        assertThat(databaseTenantBoundaryValidator.isProjectInOrganization(orgB.getId(), projectA1)).isFalse();

        AuthorizationEvaluator evaluator = buildDatabaseWiredEvaluator();
        AuthorizationDecision crossOrgDecision = evaluator.authorize(
                AuthorizationRequest.project(outsiderUser.getId(), orgB.getId(), projectA1, Permission.of("task.read")));

        assertThat(crossOrgDecision.allowed()).isFalse();
        assertThat(crossOrgDecision.getDenialReason()).contains(DenialReason.TENANT_MISMATCH);

        // Cannot assign roles across tenant boundary
        assertThatThrownBy(() -> roleAssignmentService.assignProjectRole(
                orgB.getId(), projectA1, outsiderUser.getId(), Role.PROJECT_MEMBER, outsiderUser.getId(), null))
                .isInstanceOf(InvalidRoleAssignmentException.class)
                .hasMessageContaining("Tenant boundary violation");
    }

    @Test
    @DisplayName("Security 9: Legacy role fallback strictly respects tenant boundaries")
    void shouldEnforceTenantIsolationUnderLegacyRoleFallback() {
        roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_MEMBER, adminUser.getId(), null);

        AuthorizationEvaluator evaluator = buildDatabaseWiredEvaluator();

        // devUser has legacy DEVELOPER role in Org A -> allows organization-scoped repo.read
        AuthorizationDecision orgDecision = evaluator.authorize(
                AuthorizationRequest.organization(devUser.getId(), orgA.getId(), Permission.of("repo", "read")));
        assertThat(orgDecision.allowed()).isTrue();

        // Legacy role in Org A provides zero access in Org B
        AuthorizationDecision crossOrgDecision = evaluator.authorize(
                AuthorizationRequest.organization(devUser.getId(), orgB.getId(), Permission.of("repo", "read")));
        assertThat(crossOrgDecision.allowed()).isFalse();
        assertThat(crossOrgDecision.getDenialReason()).contains(DenialReason.MEMBERSHIP_INACTIVE);

        // Unassigned project fails closed even for members with legacy roles
        UUID unassignedProject = UUID.randomUUID();
        AuthorizationDecision unassignedDecision = evaluator.authorize(
                AuthorizationRequest.project(devUser.getId(), orgA.getId(), unassignedProject, Permission.of("task.read")));
        assertThat(unassignedDecision.allowed()).isFalse();
        assertThat(unassignedDecision.getDenialReason()).contains(DenialReason.TENANT_MISMATCH);
    }

    @Test
    @DisplayName("Security 10: Incomplete, null, or unknown authorization requests default-deny")
    void shouldDefaultDenyForIncompleteOrUnknownAuthorizationData() {
        AuthorizationEvaluator evaluator = buildDatabaseWiredEvaluator();

        assertThat(evaluator.authorize((AuthorizationRequest) null).allowed()).isFalse();
        assertThat(evaluator.authorize((AuthorizationRequest) null).getDenialReason()).contains(DenialReason.GENERAL_DENIED);

        AuthorizationRequest unknownPermissionRequest = AuthorizationRequest.project(
                owner.getId(), orgA.getId(), projectA1, Permission.of("custom.unknown_perm"));
        AuthorizationDecision unknownDecision = evaluator.authorize(unknownPermissionRequest);
        assertThat(unknownDecision.allowed()).isFalse();
        assertThat(unknownDecision.getDenialReason()).contains(DenialReason.UNKNOWN_PERMISSION);

        UUID unassignedProject = UUID.randomUUID();

        // Revoking or listing unassigned project strictly fails closed
        assertThatThrownBy(() -> roleAssignmentService.revokeProjectRole(
                orgA.getId(), unassignedProject, devUser.getId(), Role.PROJECT_MEMBER, adminUser.getId(), "Reason"))
                .isInstanceOf(InvalidRoleAssignmentException.class)
                .hasMessageContaining("Tenant boundary violation");

        assertThatThrownBy(() -> roleAssignmentService.listProjectRoleAssignments(
                orgA.getId(), unassignedProject, adminUser.getId(), true))
                .isInstanceOf(InvalidRoleAssignmentException.class)
                .hasMessageContaining("Tenant boundary violation");
    }

    // ── Implementation 2.1 — Authoritative Project Binding Security Tests ─────

    @Test
    @DisplayName("Implementation 2.1 - Test 1: Arbitrary unregistered project UUID cannot be bound without authoritative ownership verification")
    void shouldDenyBindingUnregisteredProjectWithoutAuthoritativeVerification() {
        UUID unregisteredProjectId = UUID.randomUUID();

        // Database tenant boundary validator fails closed for unregistered project with no assignments
        assertThat(databaseTenantBoundaryValidator.isProjectInOrganization(orgA.getId(), unregisteredProjectId)).isFalse();

        // Ordinary assignment attempt fails closed
        assertThatThrownBy(() -> roleAssignmentService.assignProjectRole(
                orgA.getId(), unregisteredProjectId, devUser.getId(), Role.PROJECT_MEMBER, adminUser.getId(), null))
                .isInstanceOf(InvalidRoleAssignmentException.class)
                .hasMessageContaining("Tenant boundary violation");

        // Evaluator denies access with TENANT_MISMATCH
        AuthorizationEvaluator evaluator = buildDatabaseWiredEvaluator();
        AuthorizationDecision decision = evaluator.authorize(
                AuthorizationRequest.project(devUser.getId(), orgA.getId(), unregisteredProjectId, Permission.of("task.read")));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.getDenialReason()).contains(DenialReason.TENANT_MISMATCH);
    }

    @Test
    @DisplayName("Implementation 2.1 - Test 2: Valid registered project can be bound to its actual organization")
    void shouldAllowBindingValidRegisteredProjectToActualOrganization() {
        UUID validProjectId = UUID.randomUUID();

        // Project is authoritatively registered in the project registry
        projectRegistry.registerProject(orgA.getId(), validProjectId);
        assertThat(databaseTenantBoundaryValidator.isProjectInOrganization(orgA.getId(), validProjectId)).isTrue();

        // Initial binding succeeds via bindInitialProjectRole
        ProjectRoleAssignment assignment = roleAssignmentService.bindInitialProjectRole(
                orgA.getId(), validProjectId, devUser.getId(), Role.PROJECT_LEAD, adminUser.getId(), null);

        assertThat(assignment).isNotNull();
        assertThat(assignment.getRole().toDomainRole()).isEqualTo(Role.PROJECT_LEAD);
        assertThat(assignment.getOrganization().getId()).isEqualTo(orgA.getId());

        // Evaluator authorizes the assigned user on the bound project
        AuthorizationEvaluator evaluator = buildDatabaseWiredEvaluator();
        AuthorizationDecision decision = evaluator.authorize(
                AuthorizationRequest.project(devUser.getId(), orgA.getId(), validProjectId, Permission.of("task.read")));
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("Implementation 2.1 - Test 3: Project cannot be registered or bound to two organizations (concurrent/conflict check)")
    void shouldPreventConcurrentOrConflictingRegistrationOfSameProjectToMultipleOrganizations() {
        UUID sharedProjectId = UUID.randomUUID();

        // Register under Org A
        projectRegistry.registerProject(orgA.getId(), sharedProjectId);

        // Attempting to register under Org B throws InvalidProjectRegistrationException
        assertThatThrownBy(() -> projectRegistry.registerProject(orgB.getId(), sharedProjectId))
                .isInstanceOf(InvalidProjectRegistrationException.class)
                .hasMessageContaining("already bound to organization");

        // Concurrent registration simulation: exactly one thread succeeds when two orgs compete for the same UUID
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Organization raceOrgA = txTemplate.execute(status -> {
            User ownerU = userRepository.save(User.builder()
                    .email("raceOwnerA-" + UUID.randomUUID() + "@devflow.com")
                    .username("raceA_" + UUID.randomUUID().toString().substring(0, 8))
                    .passwordHash("hashed")
                    .accountStatus(AccountStatus.ACTIVE)
                    .emailVerified(true)
                    .build());
            return organizationRepository.save(Organization.builder()
                    .name("Race Tenant Alpha")
                    .slug("race-alpha-" + UUID.randomUUID().toString().substring(0, 8))
                    .owner(ownerU)
                    .build());
        });

        Organization raceOrgB = txTemplate.execute(status -> {
            User ownerU = userRepository.save(User.builder()
                    .email("raceOwnerB-" + UUID.randomUUID() + "@devflow.com")
                    .username("raceB_" + UUID.randomUUID().toString().substring(0, 8))
                    .passwordHash("hashed")
                    .accountStatus(AccountStatus.ACTIVE)
                    .emailVerified(true)
                    .build());
            return organizationRepository.save(Organization.builder()
                    .name("Race Tenant Beta")
                    .slug("race-beta-" + UUID.randomUUID().toString().substring(0, 8))
                    .owner(ownerU)
                    .build());
        });

        UUID raceProjectId = UUID.randomUUID();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        CompletableFuture<Void> taskA = CompletableFuture.runAsync(() -> {
            try {
                projectRegistry.registerProject(raceOrgA.getId(), raceProjectId);
                successCount.incrementAndGet();
            } catch (InvalidProjectRegistrationException e) {
                conflictCount.incrementAndGet();
            }
        });

        CompletableFuture<Void> taskB = CompletableFuture.runAsync(() -> {
            try {
                projectRegistry.registerProject(raceOrgB.getId(), raceProjectId);
                successCount.incrementAndGet();
            } catch (InvalidProjectRegistrationException e) {
                conflictCount.incrementAndGet();
            }
        });

        CompletableFuture.allOf(taskA, taskB).join();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);
        assertThat(projectRegistry.getProjectOrganizationId(raceProjectId)).isPresent();
    }

    @Test
    @DisplayName("Implementation 2.1 - Test 4: Cross-organization initial binding is rejected")
    void shouldRejectCrossOrganizationInitialBinding() {
        UUID projectRegisteredInOrgA = UUID.randomUUID();
        projectRegistry.registerProject(orgA.getId(), projectRegisteredInOrgA);

        // Attempting initial binding under Org B is rejected
        assertThatThrownBy(() -> roleAssignmentService.bindInitialProjectRole(
                orgB.getId(), projectRegisteredInOrgA, outsiderUser.getId(), Role.PROJECT_LEAD, adminB.getId(), null))
                .isInstanceOf(InvalidRoleAssignmentException.class);

        // No role assignments were created for Org B
        assertThat(projectAssignmentRepository.findByProjectId(projectRegisteredInOrgA)).isEmpty();
    }

    @Test
    @DisplayName("Implementation 2.1 - Test 5: Existing project assignments cannot be moved across tenants")
    void shouldPreventMovingExistingProjectAssignmentsAcrossTenants() {
        // projectA1 is registered and bound to Org A
        roleAssignmentService.assignProjectRole(
                orgA.getId(), projectA1, devUser.getId(), Role.PROJECT_MEMBER, adminUser.getId(), null);

        // Attempting to bind projectA1 into Org B fails
        assertThatThrownBy(() -> roleAssignmentService.bindInitialProjectRole(
                orgB.getId(), projectA1, outsiderUser.getId(), Role.PROJECT_MEMBER, adminB.getId(), null))
                .isInstanceOf(InvalidRoleAssignmentException.class);

        // Attempting to assign projectA1 in Org B fails closed
        assertThatThrownBy(() -> roleAssignmentService.assignProjectRole(
                orgB.getId(), projectA1, outsiderUser.getId(), Role.PROJECT_MEMBER, adminB.getId(), null))
                .isInstanceOf(InvalidRoleAssignmentException.class)
                .hasMessageContaining("Tenant boundary violation");

        // Existing assignments in Org A remain completely unaffected
        List<ProjectRoleAssignment> assignments = projectAssignmentRepository.findByProjectId(projectA1);
        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).getOrganization().getId()).isEqualTo(orgA.getId());
    }

    @Test
    @DisplayName("Implementation 2.1 - Test 6: Ordinary authorized project-role assignment works for correctly registered projects")
    void shouldAllowOrdinaryAuthorizedProjectRoleAssignmentForCorrectlyRegisteredProject() {
        UUID registeredProject = UUID.randomUUID();
        projectRegistry.registerProject(orgA.getId(), registeredProject);

        // Lead assignment via assignProjectRole works
        ProjectRoleAssignment leadAssign = roleAssignmentService.assignProjectRole(
                orgA.getId(), registeredProject, adminUser.getId(), Role.PROJECT_LEAD, adminUser.getId(), null);
        assertThat(leadAssign).isNotNull();

        // Subsequent member assignment by the project lead works
        ProjectRoleAssignment memberAssign = roleAssignmentService.assignProjectRole(
                orgA.getId(), registeredProject, devUser.getId(), Role.PROJECT_MEMBER, adminUser.getId(), null);
        assertThat(memberAssign).isNotNull();
        assertThat(memberAssign.getRole().toDomainRole()).isEqualTo(Role.PROJECT_MEMBER);

        List<ProjectRoleAssignment> active = projectAssignmentRepository.findActiveByOrganizationIdAndProjectId(orgA.getId(), registeredProject, Instant.now());
        assertThat(active).hasSize(2);
    }

    @Test
    @DisplayName("Implementation 2.1 - Test 7: Unknown and incomplete project ownership data defaults to deny")
    void shouldDefaultDenyWhenProjectOwnershipDataIsUnknownOrIncomplete() {
        UUID unknownProjectId = UUID.randomUUID();

        // Null checks
        assertThat(databaseTenantBoundaryValidator.isProjectInOrganization(null, unknownProjectId)).isFalse();
        assertThat(databaseTenantBoundaryValidator.isProjectInOrganization(orgA.getId(), null)).isFalse();
        assertThat(databaseTenantBoundaryValidator.isProjectInOrganization(null, null)).isFalse();

        // Non-existent organization
        UUID nonExistentOrg = UUID.randomUUID();
        assertThat(databaseTenantBoundaryValidator.isProjectInOrganization(nonExistentOrg, unknownProjectId)).isFalse();

        // Unregistered project with no assignments
        assertThat(databaseTenantBoundaryValidator.isProjectInOrganization(orgA.getId(), unknownProjectId)).isFalse();
        assertThat(databaseTenantBoundaryValidator.getResourceOrganizationId("project", unknownProjectId.toString())).isEmpty();

        // Evaluator denies unknown project access
        AuthorizationEvaluator evaluator = buildDatabaseWiredEvaluator();
        AuthorizationDecision decision = evaluator.authorize(
                AuthorizationRequest.project(devUser.getId(), orgA.getId(), unknownProjectId, Permission.of("task.read")));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.getDenialReason()).contains(DenialReason.TENANT_MISMATCH);
    }
}
