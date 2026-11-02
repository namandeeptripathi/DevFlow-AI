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
import com.devflow.role.domain.OrganizationRoleAssignment;
import com.devflow.role.domain.ProjectRoleAssignment;
import com.devflow.role.domain.RoleEntity;
import com.devflow.role.exception.InvalidRoleAssignmentException;
import com.devflow.role.exception.RoleAssignmentAlreadyExistsException;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({
    JpaAuditingConfiguration.class,
    RoleAssignmentService.class,
    DatabaseRoleResolver.class,
    DatabaseMembershipValidator.class,
    DatabaseProjectAssignmentValidator.class
})
@ActiveProfiles("test")
@DisplayName("Scoped Role Assignment Persistence & Security Invariants Integration Tests")
class ScopedRoleAssignmentDatabaseIntegrationTest {

    @Autowired
    private RoleAssignmentService roleAssignmentService;

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

    private User owner;
    private User adminUser;
    private User devUser;
    private User outsiderUser;
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
}
