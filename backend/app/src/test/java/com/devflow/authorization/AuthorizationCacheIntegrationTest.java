package com.devflow.authorization;

import com.devflow.modules.auth.context.OrganizationAuthorizationContext;
import com.devflow.modules.auth.context.ProjectAuthorizationContext;
import com.devflow.modules.auth.domain.Role;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.organization.service.OrganizationMembershipService;
import com.devflow.role.adapter.DatabaseMembershipValidator;
import com.devflow.role.adapter.DatabaseRoleResolver;
import com.devflow.role.domain.OrganizationRoleAssignment;
import com.devflow.role.domain.ProjectRoleAssignment;
import com.devflow.role.service.RoleAssignmentService;
import com.devflow.security.authorization.cache.AuthorizationCacheService;
import com.devflow.security.authorization.cache.RedisAuthorizationCacheService;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Stage 6: Authorization Cache & Revocation Invalidation Integration Tests")
class AuthorizationCacheIntegrationTest {

    @Autowired
    private AuthorizationCacheService cacheService;

    @Autowired
    private DatabaseRoleResolver databaseRoleResolver;

    @Autowired
    private DatabaseMembershipValidator databaseMembershipValidator;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private OrganizationMembershipService organizationMembershipService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private User ownerA;
    private User devA;
    private User devB;
    private Organization orgA;
    private Organization orgB;
    private UUID projectA1;

    @BeforeEach
    void setUp() {
        cacheService.clear();

        ownerA = createUser("owner-a");
        devA = createUser("dev-a");
        devB = createUser("dev-b");

        orgA = organizationRepository.save(Organization.builder()
                .name("Tenant Alpha")
                .slug("alpha-" + UUID.randomUUID())
                .owner(ownerA)
                .build());

        orgB = organizationRepository.save(Organization.builder()
                .name("Tenant Beta")
                .slug("beta-" + UUID.randomUUID())
                .owner(devB)
                .build());

        createOrgMember(orgA, ownerA, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE);
        createOrgMember(orgA, devA, OrganizationRole.DEVELOPER, OrganizationMembershipStatus.ACTIVE);
        createOrgMember(orgB, devB, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE);

        projectA1 = UUID.randomUUID();

        entityManager.flush();
        entityManager.clear();
    }

    private User createUser(String prefix) {
        return userRepository.save(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@devflow.io")
                .username(prefix + "_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());
    }

    private OrganizationMember createOrgMember(
            Organization org, User user, OrganizationRole role, OrganizationMembershipStatus status) {
        return organizationMemberRepository.save(OrganizationMember.builder()
                .organization(org)
                .user(user)
                .role(role)
                .status(status)
                .build());
    }

    // ── 1. Cache Hit & Miss Behavior ──────────────────────────────────────────

    @Nested
    @DisplayName("Cache Hit and Miss Behavior")
    class CacheHitAndMiss {

        @Test
        @DisplayName("Initial role resolution populates cache; subsequent lookup hits cache")
        void roleResolution_cachesAndHits() {
            OrganizationAuthorizationContext context = OrganizationAuthorizationContext.of(orgA.getId());

            // 1. Initial resolution (cache miss -> database query)
            assertThat(cacheService.getOrganizationRoles(orgA.getId(), devA.getId())).isEmpty();

            Set<Role> roles1 = databaseRoleResolver.resolveRoles(devA.getId(), context);
            assertThat(roles1).contains(Role.ORGANIZATION_MEMBER);

            // 2. Cache is now populated
            Optional<Set<Role>> cached = cacheService.getOrganizationRoles(orgA.getId(), devA.getId());
            assertThat(cached).isPresent();
            assertThat(cached.get()).contains(Role.ORGANIZATION_MEMBER);

            // 3. Second resolution returns cached instance
            Set<Role> roles2 = databaseRoleResolver.resolveRoles(devA.getId(), context);
            assertThat(roles2).isEqualTo(cached.get());
        }

        @Test
        @DisplayName("Initial membership validation populates cache; subsequent check hits cache")
        void membershipValidation_cachesAndHits() {
            assertThat(cacheService.isMemberActive(orgA.getId(), devA.getId())).isEmpty();

            boolean active1 = databaseMembershipValidator.isActiveMember(devA.getId(), orgA.getId());
            assertThat(active1).isTrue();

            assertThat(cacheService.isMemberActive(orgA.getId(), devA.getId())).contains(true);

            boolean active2 = databaseMembershipValidator.isActiveMember(devA.getId(), orgA.getId());
            assertThat(active2).isTrue();
        }
    }

    // ── 2. Invalidation on Assignment Mutations ───────────────────────────────

    @Nested
    @DisplayName("Targeted Invalidation on Role Grant and Revocation")
    class InvalidationOnMutations {

        @Test
        @DisplayName("Granting organization role invalidates cache and reflects new role immediately")
        void grantRole_invalidatesCache() {
            OrganizationAuthorizationContext context = OrganizationAuthorizationContext.of(orgA.getId());

            // Pre-warm cache with legacy DEVELOPER (ORGANIZATION_MEMBER)
            Set<Role> initial = databaseRoleResolver.resolveRoles(devA.getId(), context);
            assertThat(initial).containsExactly(Role.ORGANIZATION_MEMBER);
            assertThat(cacheService.getOrganizationRoles(orgA.getId(), devA.getId())).isPresent();

            // Grant ORGANIZATION_ADMIN
            roleAssignmentService.assignOrganizationRole(
                    orgA.getId(), devA.getId(), Role.ORGANIZATION_ADMIN, ownerA.getId(), null);

            entityManager.flush();
            entityManager.clear();

            // Cache was evicted during grant
            assertThat(cacheService.getOrganizationRoles(orgA.getId(), devA.getId())).isEmpty();

            // Next resolution reflects newly granted role
            Set<Role> updated = databaseRoleResolver.resolveRoles(devA.getId(), context);
            assertThat(updated).contains(Role.ORGANIZATION_ADMIN);
        }

        @Test
        @DisplayName("Revoking organization role invalidates cache and revokes privileges immediately")
        void revokeRole_invalidatesCacheImmediately() {
            OrganizationAuthorizationContext context = OrganizationAuthorizationContext.of(orgA.getId());

            // Grant and resolve role
            roleAssignmentService.assignOrganizationRole(
                    orgA.getId(), devA.getId(), Role.ORGANIZATION_ADMIN, ownerA.getId(), null);
            entityManager.flush();
            entityManager.clear();

            Set<Role> active = databaseRoleResolver.resolveRoles(devA.getId(), context);
            assertThat(active).contains(Role.ORGANIZATION_ADMIN);
            assertThat(cacheService.getOrganizationRoles(orgA.getId(), devA.getId())).isPresent();

            // Revoke role
            roleAssignmentService.revokeOrganizationRole(
                    orgA.getId(), devA.getId(), Role.ORGANIZATION_ADMIN, ownerA.getId(), "Deprovisioning");
            entityManager.flush();
            entityManager.clear();

            // Cache was evicted
            assertThat(cacheService.getOrganizationRoles(orgA.getId(), devA.getId())).isEmpty();

            // Fresh resolution sees revoked state immediately (legacy fallback does not resurrect)
            Set<Role> revokedRoles = databaseRoleResolver.resolveRoles(devA.getId(), context);
            assertThat(revokedRoles).doesNotContain(Role.ORGANIZATION_ADMIN);
        }

        @Test
        @DisplayName("Project role grant and revocation invalidate project cache accurately")
        void projectRole_grantAndRevoke_invalidatesProjectCache() {
            ProjectAuthorizationContext projectContext = ProjectAuthorizationContext.of(orgA.getId(), projectA1);

            // Grant project role
            roleAssignmentService.assignProjectRole(
                    orgA.getId(), projectA1, devA.getId(), Role.PROJECT_MEMBER, ownerA.getId(), null);
            entityManager.flush();
            entityManager.clear();

            // Resolve and cache
            Set<Role> projectRoles = databaseRoleResolver.resolveRoles(devA.getId(), projectContext);
            assertThat(projectRoles).contains(Role.PROJECT_MEMBER);
            assertThat(cacheService.getProjectRoles(orgA.getId(), projectA1, devA.getId())).isPresent();

            // Revoke project role
            roleAssignmentService.revokeProjectRole(
                    orgA.getId(), projectA1, devA.getId(), Role.PROJECT_MEMBER, ownerA.getId(), "Task completion");
            entityManager.flush();
            entityManager.clear();

            // Cache was evicted
            assertThat(cacheService.getProjectRoles(orgA.getId(), projectA1, devA.getId())).isEmpty();

            // Fresh lookup reflects revoked status
            Set<Role> afterRevocation = databaseRoleResolver.resolveRoles(devA.getId(), projectContext);
            assertThat(afterRevocation).doesNotContain(Role.PROJECT_MEMBER);
        }

        @Test
        @DisplayName("Removing member from organization invalidates user cache entries")
        void removeMember_invalidatesCache() {
            databaseMembershipValidator.isActiveMember(devA.getId(), orgA.getId());
            assertThat(cacheService.isMemberActive(orgA.getId(), devA.getId())).contains(true);

            organizationMembershipService.removeMember(orgA.getId(), devA.getId(), ownerA.getId());
            entityManager.flush();
            entityManager.clear();

            // Cache was evicted
            assertThat(cacheService.isMemberActive(orgA.getId(), devA.getId())).isEmpty();

            // Fresh check evaluates database and returns false
            assertThat(databaseMembershipValidator.isActiveMember(devA.getId(), orgA.getId())).isFalse();
        }
    }

    // ── 3. Cross-Tenant Isolation ─────────────────────────────────────────────

    @Nested
    @DisplayName("Cross-Tenant Cache Isolation")
    class CrossTenantIsolation {

        @Test
        @DisplayName("Cache keys for Tenant Alpha cannot collide with or leak to Tenant Beta")
        void tenantIsolation_enforcedInCache() {
            cacheService.putOrganizationRoles(orgA.getId(), devA.getId(), Set.of(Role.ORGANIZATION_ADMIN));
            cacheService.putOrganizationRoles(orgB.getId(), devA.getId(), Set.of(Role.ORGANIZATION_VIEWER));

            assertThat(cacheService.getOrganizationRoles(orgA.getId(), devA.getId()))
                    .contains(Set.of(Role.ORGANIZATION_ADMIN));
            assertThat(cacheService.getOrganizationRoles(orgB.getId(), devA.getId()))
                    .contains(Set.of(Role.ORGANIZATION_VIEWER));

            // Evicting tenant A does not affect tenant B
            cacheService.evictUser(orgA.getId(), devA.getId());
            assertThat(cacheService.getOrganizationRoles(orgA.getId(), devA.getId())).isEmpty();
            assertThat(cacheService.getOrganizationRoles(orgB.getId(), devA.getId())).isPresent();
        }
    }
}

