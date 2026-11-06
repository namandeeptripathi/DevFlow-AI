package com.devflow.authorization;

import com.devflow.modules.auth.api.AuthApi;
import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.AuthorizationRequest;
import com.devflow.modules.auth.context.OrganizationAuthorizationContext;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.exception.OrganizationAccessDeniedException;
import com.devflow.organization.exception.OrganizationMembershipRequiredException;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.organization.service.OrganizationAuthorizationService;
import com.devflow.role.adapter.DatabaseRoleResolver;
import com.devflow.role.domain.OrganizationRoleAssignment;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Stage 5: Organization Authorization Integration Tests")
class OrganizationAuthorizationIntegrationTest {

    @Autowired
    private OrganizationAuthorizationService authorizationService;

    @Autowired
    private AuthApi authApi;

    @Autowired
    private DatabaseRoleResolver databaseRoleResolver;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private EntityManager entityManager;

    private User ownerA;
    private User adminA;
    private User devA;
    private User viewerA;
    private User userB;

    private Organization orgA;
    private Organization orgB;

    @BeforeEach
    void setUp() {
        ownerA = userRepository.save(User.builder()
                .email("owner-a-" + UUID.randomUUID() + "@devflow.com")
                .username("ownera_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed_pw")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        adminA = userRepository.save(User.builder()
                .email("admin-a-" + UUID.randomUUID() + "@devflow.com")
                .username("admina_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed_pw")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        devA = userRepository.save(User.builder()
                .email("dev-a-" + UUID.randomUUID() + "@devflow.com")
                .username("deva_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed_pw")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        viewerA = userRepository.save(User.builder()
                .email("viewer-a-" + UUID.randomUUID() + "@devflow.com")
                .username("viewera_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed_pw")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        userB = userRepository.save(User.builder()
                .email("user-b-" + UUID.randomUUID() + "@devflow.com")
                .username("userb_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed_pw")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        orgA = organizationRepository.save(Organization.builder()
                .name("Tenant Alpha")
                .slug("tenant-alpha-" + UUID.randomUUID().toString().substring(0, 8))
                .owner(ownerA)
                .build());

        orgB = organizationRepository.save(Organization.builder()
                .name("Tenant Beta")
                .slug("tenant-beta-" + UUID.randomUUID().toString().substring(0, 8))
                .owner(userB)
                .build());

        // Org A memberships
        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA).user(ownerA).role(OrganizationRole.OWNER)
                .status(OrganizationMembershipStatus.ACTIVE).joinedAt(Instant.now()).build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA).user(adminA).role(OrganizationRole.ADMIN)
                .status(OrganizationMembershipStatus.ACTIVE).joinedAt(Instant.now()).build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA).user(devA).role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE).joinedAt(Instant.now()).build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA).user(viewerA).role(OrganizationRole.VIEWER)
                .status(OrganizationMembershipStatus.ACTIVE).joinedAt(Instant.now()).build());

        // Org B membership
        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgB).user(userB).role(OrganizationRole.OWNER)
                .status(OrganizationMembershipStatus.ACTIVE).joinedAt(Instant.now()).build());

        entityManager.flush();
    }

    // ── 1. ROLE HIERARCHY & PERMISSIONS ───────────────────────────────────────

    @Nested
    @DisplayName("Role Hierarchy & Canonical Permissions")
    class RoleHierarchyTests {

        @Test
        @DisplayName("Owner holds isMember, isAdminOrOwner, and isOwner privileges")
        void ownerPrivileges() {
            assertThat(authorizationService.isMember(orgA.getId(), ownerA.getId())).isTrue();
            assertThat(authorizationService.isAdminOrOwner(orgA.getId(), ownerA.getId())).isTrue();
            assertThat(authorizationService.isOwner(orgA.getId(), ownerA.getId())).isTrue();

            assertThatCode(() -> authorizationService.requireOwner(orgA.getId(), ownerA.getId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Admin holds isMember and isAdminOrOwner, but is strictly denied isOwner")
        void adminPrivileges_deniedOwner() {
            assertThat(authorizationService.isMember(orgA.getId(), adminA.getId())).isTrue();
            assertThat(authorizationService.isAdminOrOwner(orgA.getId(), adminA.getId())).isTrue();
            assertThat(authorizationService.isOwner(orgA.getId(), adminA.getId())).isFalse();

            assertThatThrownBy(() -> authorizationService.requireOwner(orgA.getId(), adminA.getId()))
                    .isInstanceOf(OrganizationAccessDeniedException.class)
                    .hasMessageContaining("OWNER role required");
        }

        @Test
        @DisplayName("Legacy DEVELOPER maps to canonical ORGANIZATION_MEMBER: holds member privileges, denied admin and owner")
        void developerMapping_memberPrivileges() {
            // DEVELOPER maps to ORGANIZATION_MEMBER
            Set<Role> resolvedRoles = databaseRoleResolver.resolveRoles(
                    devA.getId(), OrganizationAuthorizationContext.of(orgA.getId()));
            assertThat(resolvedRoles).contains(Role.ORGANIZATION_MEMBER);

            assertThat(authorizationService.isMember(orgA.getId(), devA.getId())).isTrue();
            assertThat(authorizationService.isAdminOrOwner(orgA.getId(), devA.getId())).isFalse();
            assertThat(authorizationService.isOwner(orgA.getId(), devA.getId())).isFalse();

            assertThatCode(() -> authorizationService.requireMember(orgA.getId(), devA.getId()))
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> authorizationService.requireAdminOrOwner(orgA.getId(), devA.getId()))
                    .isInstanceOf(OrganizationAccessDeniedException.class);
        }

        @Test
        @DisplayName("Viewer holds isMember, denied admin and owner")
        void viewerPrivileges() {
            assertThat(authorizationService.isMember(orgA.getId(), viewerA.getId())).isTrue();
            assertThat(authorizationService.isAdminOrOwner(orgA.getId(), viewerA.getId())).isFalse();
            assertThat(authorizationService.isOwner(orgA.getId(), viewerA.getId())).isFalse();
        }
    }

    // ── 2. ROLE REVOCATION BEHAVIOR ───────────────────────────────────────────

    @Nested
    @DisplayName("Immediate Role Revocation Effect")
    class RoleRevocationTests {

        @Test
        @DisplayName("Revoking explicit role assignment immediately revokes access; legacy fallback does not resurrect privileges")
        void roleRevocation_immediatelyAffectsAuthorization() {
            // Assign explicit persistent ORGANIZATION_ADMIN to devA
            roleAssignmentService.assignOrganizationRole(
                    orgA.getId(), devA.getId(), Role.ORGANIZATION_ADMIN, ownerA.getId(), null);

            entityManager.flush();
            entityManager.clear();

            // Verify devA now has admin access
            assertThat(authorizationService.isAdminOrOwner(orgA.getId(), devA.getId())).isTrue();

            // Revoke the role assignment
            roleAssignmentService.revokeOrganizationRole(
                    orgA.getId(), devA.getId(), Role.ORGANIZATION_ADMIN, ownerA.getId(), "Demotion to regular member");

            entityManager.flush();
            entityManager.clear();

            // Immediate revocation: admin access is immediately denied
            assertThat(authorizationService.isAdminOrOwner(orgA.getId(), devA.getId())).isFalse();

            // Security invariant: Legacy fallback does NOT grant admin access
            Set<Role> activeRoles = databaseRoleResolver.resolveRoles(
                    devA.getId(), OrganizationAuthorizationContext.of(orgA.getId()));
            assertThat(activeRoles).doesNotContain(Role.ORGANIZATION_ADMIN);
        }
    }

    // ── 3. CROSS-TENANT ACCESS DENIAL ─────────────────────────────────────────

    @Nested
    @DisplayName("Cross-Tenant Isolation")
    class CrossTenantTests {

        @Test
        @DisplayName("User from Tenant Beta is denied all access to Tenant Alpha")
        void crossTenantAccess_denied() {
            assertThat(authorizationService.isMember(orgA.getId(), userB.getId())).isFalse();
            assertThat(authorizationService.isAdminOrOwner(orgA.getId(), userB.getId())).isFalse();
            assertThat(authorizationService.isOwner(orgA.getId(), userB.getId())).isFalse();

            assertThatThrownBy(() -> authorizationService.requireMember(orgA.getId(), userB.getId()))
                    .isInstanceOf(OrganizationMembershipRequiredException.class);

            assertThatThrownBy(() -> authorizationService.requireAdminOrOwner(orgA.getId(), userB.getId()))
                    .isInstanceOf(OrganizationAccessDeniedException.class);

            assertThatThrownBy(() -> authorizationService.requireOwner(orgA.getId(), userB.getId()))
                    .isInstanceOf(OrganizationAccessDeniedException.class);
        }
    }
}
