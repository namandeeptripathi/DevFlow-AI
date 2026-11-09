package com.devflow.role;

import com.devflow.modules.auth.api.AuthApi;
import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.AuthorizationRequest;
import com.devflow.modules.auth.context.OrganizationAuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.role.adapter.DatabaseCustomRolePermissionResolver;
import com.devflow.role.domain.CustomRoleAssignment;
import com.devflow.role.domain.CustomRoleEntity;
import com.devflow.role.dto.CreateCustomRoleRequest;
import com.devflow.role.dto.UpdateCustomRoleRequest;
import com.devflow.role.service.CustomRoleService;
import com.devflow.role.service.RoleAssignmentService;
import com.devflow.security.authorization.cache.AuthorizationCacheService;
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
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Stage 7: Central Authorization Engine & Custom Role Resolution Integration Tests")
class CustomRoleAuthorizationEvaluationTest {

    @Autowired
    private AuthApi authApi;

    @Autowired
    private CustomRoleService customRoleService;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private DatabaseCustomRolePermissionResolver customRolePermissionResolver;

    @Autowired
    private AuthorizationCacheService cacheService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private Organization orgA;
    private Organization orgB;
    private User ownerA;
    private User devA;
    private User devB;

    @BeforeEach
    void setUp() {
        if (cacheService != null) {
            cacheService.clear();
        }

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

        addMember(orgA, ownerA, OrganizationRole.OWNER);
        addMember(orgA, devA, OrganizationRole.DEVELOPER);

        addMember(orgB, devB, OrganizationRole.OWNER);

        entityManager.flush();
        entityManager.clear();
    }

    @Nested
    @DisplayName("Custom Role Permission Resolution via Central AuthApi")
    class CustomRolePermissionResolutionTests {

        @Test
        @DisplayName("Custom role grants permissions beyond canonical developer role")
        void customRoleGrantsAssignedPermissions() {
            // Before custom role assignment: regular DEVELOPER does not have analytics.export
            AuthorizationDecision beforeDecision = authApi.authorize(AuthorizationRequest.organization(
                    devA.getId(), orgA.getId(), PermissionRegistry.ANALYTICS_EXPORT
            ));
            assertThat(beforeDecision.allowed()).isFalse();

            // Create custom role with analytics.view and analytics.export
            CreateCustomRoleRequest createRequest = CreateCustomRoleRequest.builder()
                    .name("ANALYTICS_SPECIALIST")
                    .displayName("Analytics Specialist")
                    .permissions(Set.of("analytics.view", "analytics.export"))
                    .build();

            CustomRoleEntity customRole = customRoleService.createCustomRole(orgA.getId(), ownerA.getId(), createRequest);

            // Assign custom role to devA
            roleAssignmentService.assignCustomRole(orgA.getId(), devA.getId(), customRole.getId(), ownerA.getId(), null);

            entityManager.flush();
            entityManager.clear();

            // After custom role assignment: evaluate should PERMIT analytics.export
            AuthorizationDecision afterDecision = authApi.authorize(AuthorizationRequest.organization(
                    devA.getId(), orgA.getId(), PermissionRegistry.ANALYTICS_EXPORT
            ));
            assertThat(afterDecision.allowed()).isTrue();
        }

        @Test
        @DisplayName("Disabled custom role does not grant permissions")
        void disabledCustomRoleDoesNotGrantPermissions() {
            CreateCustomRoleRequest createRequest = CreateCustomRoleRequest.builder()
                    .name("RELEASE_ENGINEER")
                    .displayName("Release Engineer")
                    .permissions(Set.of("cycle.manage"))
                    .build();

            CustomRoleEntity customRole = customRoleService.createCustomRole(orgA.getId(), ownerA.getId(), createRequest);

            roleAssignmentService.assignCustomRole(orgA.getId(), devA.getId(), customRole.getId(), ownerA.getId(), null);

            // Deactivate custom role
            UpdateCustomRoleRequest updateRequest = UpdateCustomRoleRequest.builder()
                    .active(false)
                    .build();
            customRoleService.updateCustomRole(orgA.getId(), customRole.getId(), ownerA.getId(), updateRequest);

            entityManager.flush();
            entityManager.clear();

            AuthorizationDecision decision = authApi.authorize(AuthorizationRequest.organization(
                    devA.getId(), orgA.getId(), PermissionRegistry.CYCLE_MANAGE
            ));
            assertThat(decision.allowed()).isFalse();
        }

        @Test
        @DisplayName("Revoked custom role assignment does not grant permissions")
        void revokedCustomRoleAssignmentDoesNotGrantPermissions() {
            CreateCustomRoleRequest createRequest = CreateCustomRoleRequest.builder()
                    .name("KB_MANAGER")
                    .displayName("Knowledge Base Manager")
                    .permissions(Set.of("kb.manage"))
                    .build();

            CustomRoleEntity customRole = customRoleService.createCustomRole(orgA.getId(), ownerA.getId(), createRequest);

            CustomRoleAssignment assignment = roleAssignmentService.assignCustomRole(
                    orgA.getId(), devA.getId(), customRole.getId(), ownerA.getId(), null
            );

            // Revoke assignment
            roleAssignmentService.revokeCustomRoleAssignment(orgA.getId(), assignment.getId(), ownerA.getId(), "Role decommissioned");

            entityManager.flush();
            entityManager.clear();

            AuthorizationDecision decision = authApi.authorize(AuthorizationRequest.organization(
                    devA.getId(), orgA.getId(), PermissionRegistry.KB_MANAGE
            ));
            assertThat(decision.allowed()).isFalse();
        }

        @Test
        @DisplayName("Expired custom role assignment does not grant permissions")
        void expiredCustomRoleAssignmentDoesNotGrantPermissions() {
            CreateCustomRoleRequest createRequest = CreateCustomRoleRequest.builder()
                    .name("TEMPORARY_MODERATOR")
                    .displayName("Temporary Moderator")
                    .permissions(Set.of("comment.moderate"))
                    .build();

            CustomRoleEntity customRole = customRoleService.createCustomRole(orgA.getId(), ownerA.getId(), createRequest);

            // Assign with expiration in past
            Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
            roleAssignmentService.assignCustomRole(orgA.getId(), devA.getId(), customRole.getId(), ownerA.getId(), past);

            entityManager.flush();
            entityManager.clear();

            AuthorizationDecision decision = authApi.authorize(AuthorizationRequest.organization(
                    devA.getId(), orgA.getId(), PermissionRegistry.COMMENT_MODERATE
            ));
            assertThat(decision.allowed()).isFalse();
        }

        @Test
        @DisplayName("Tenant isolation: custom role permissions in Org A do not grant access in Org B")
        void customRoleRespectsTenantIsolation() {
            CreateCustomRoleRequest createRequest = CreateCustomRoleRequest.builder()
                    .name("TENANT_A_ROLE")
                    .displayName("Tenant A Specific Role")
                    .permissions(Set.of("repo.connect"))
                    .build();

            CustomRoleEntity customRole = customRoleService.createCustomRole(orgA.getId(), ownerA.getId(), createRequest);
            roleAssignmentService.assignCustomRole(orgA.getId(), devA.getId(), customRole.getId(), ownerA.getId(), null);

            entityManager.flush();
            entityManager.clear();

            // Request for Org B context should be rejected by tenant boundaries / no membership
            AuthorizationDecision decision = authApi.authorize(AuthorizationRequest.organization(
                    devA.getId(), orgB.getId(), PermissionRegistry.REPO_CONNECT
            ));
            assertThat(decision.allowed()).isFalse();
        }
    }

    @Nested
    @DisplayName("Authorization Cache Integration & Invalidation")
    class AuthorizationCacheIntegrationTests {

        @Test
        @DisplayName("Cache invalidation on role permission update takes effect immediately")
        void cacheInvalidationOnRoleUpdateTakesEffectImmediately() {
            CreateCustomRoleRequest createRequest = CreateCustomRoleRequest.builder()
                    .name("DYNAMIC_ROLE")
                    .displayName("Dynamic Role")
                    .permissions(Set.of("project.read", "automation.configure"))
                    .build();

            CustomRoleEntity customRole = customRoleService.createCustomRole(orgA.getId(), ownerA.getId(), createRequest);
            roleAssignmentService.assignCustomRole(orgA.getId(), devA.getId(), customRole.getId(), ownerA.getId(), null);

            entityManager.flush();
            entityManager.clear();

            // 1. Initial evaluation
            AuthorizationDecision dec1 = authApi.authorize(AuthorizationRequest.organization(
                    devA.getId(), orgA.getId(), PermissionRegistry.AUTOMATION_CONFIGURE
            ));
            assertThat(dec1.allowed()).isTrue();

            // 2. Update role: remove automation.configure, keep only project.read
            UpdateCustomRoleRequest updateRequest = UpdateCustomRoleRequest.builder()
                    .permissions(Set.of("project.read"))
                    .build();
            customRoleService.updateCustomRole(orgA.getId(), customRole.getId(), ownerA.getId(), updateRequest);

            entityManager.flush();
            entityManager.clear();

            // 3. Subsequent evaluation must immediately reflect eviction and deny automation.configure
            AuthorizationDecision dec2 = authApi.authorize(AuthorizationRequest.organization(
                    devA.getId(), orgA.getId(), PermissionRegistry.AUTOMATION_CONFIGURE
            ));
            assertThat(dec2.allowed()).isFalse();
        }

        @Test
        @DisplayName("Cache invalidation on assignment revocation takes effect immediately")
        void cacheInvalidationOnAssignmentRevocationTakesEffectImmediately() {
            CreateCustomRoleRequest createRequest = CreateCustomRoleRequest.builder()
                    .name("REVOCABLE_ROLE")
                    .displayName("Revocable Role")
                    .permissions(Set.of("notification.configure"))
                    .build();

            CustomRoleEntity customRole = customRoleService.createCustomRole(orgA.getId(), ownerA.getId(), createRequest);
            CustomRoleAssignment assignment = roleAssignmentService.assignCustomRole(
                    orgA.getId(), devA.getId(), customRole.getId(), ownerA.getId(), null
            );

            entityManager.flush();
            entityManager.clear();

            AuthorizationDecision dec1 = authApi.authorize(AuthorizationRequest.organization(
                    devA.getId(), orgA.getId(), PermissionRegistry.NOTIFICATION_CONFIGURE
            ));
            assertThat(dec1.allowed()).isTrue();

            // Revoke assignment
            roleAssignmentService.revokeCustomRoleAssignment(orgA.getId(), assignment.getId(), ownerA.getId(), "Revoked");

            entityManager.flush();
            entityManager.clear();

            // Must deny immediately without stale cached permissions
            AuthorizationDecision dec2 = authApi.authorize(AuthorizationRequest.organization(
                    devA.getId(), orgA.getId(), PermissionRegistry.NOTIFICATION_CONFIGURE
            ));
            assertThat(dec2.allowed()).isFalse();
        }
    }

    private User createUser(String username) {
        return userRepository.save(User.builder()
                .username(username + "-" + UUID.randomUUID().toString().substring(0, 8))
                .email(username + "@devflow.io")
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
