package com.devflow.role.service;

import com.devflow.modules.auth.context.OrganizationAuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.modules.auth.registry.RolePermissionMapping;
import com.devflow.permission.domain.PermissionEntity;
import com.devflow.permission.repository.PermissionRepository;
import com.devflow.role.adapter.DatabaseRoleResolver;
import com.devflow.role.domain.CustomRoleAssignment;
import com.devflow.role.domain.CustomRoleEntity;
import com.devflow.role.domain.CustomRolePermissionEntity;
import com.devflow.role.exception.CustomRoleAccessDeniedException;
import com.devflow.role.exception.PermissionGovernanceException;
import com.devflow.role.repository.CustomRoleAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Stage 7: Permission Governance & Delegation Policy Tests")
class PermissionGovernanceServiceTest {

    private PermissionRegistry permissionRegistry;
    private RolePermissionMapping rolePermissionMapping;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private DatabaseRoleResolver databaseRoleResolver;

    @Mock
    private CustomRoleAssignmentRepository customRoleAssignmentRepository;

    private PermissionGovernanceService governanceService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        permissionRegistry = PermissionRegistry.canonical();
        rolePermissionMapping = new RolePermissionMapping();

        governanceService = new PermissionGovernanceService(
                permissionRegistry,
                rolePermissionMapping,
                permissionRepository,
                databaseRoleResolver,
                customRoleAssignmentRepository
        );
    }

    @Nested
    @DisplayName("Permission Validation Invariants")
    class PermissionValidationTests {

        @Test
        @DisplayName("Valid canonical organization permissions are accepted")
        void validPermissionsAreAccepted() {
            Set<String> validPermissions = Set.of("project.read", "project.write", "member.invite");

            Set<Permission> validated = governanceService.validatePermissions(validPermissions);

            assertThat(validated)
                    .hasSize(3)
                    .contains(
                            PermissionRegistry.PROJECT_READ,
                            PermissionRegistry.PROJECT_WRITE,
                            PermissionRegistry.MEMBER_INVITE
                    );
        }

        @Test
        @DisplayName("Rejects null or empty permission set")
        void rejectsNullOrEmptyPermissions() {
            assertThatThrownBy(() -> governanceService.validatePermissions(null))
                    .isInstanceOf(PermissionGovernanceException.class)
                    .hasMessageContaining("at least one valid permission");

            assertThatThrownBy(() -> governanceService.validatePermissions(Collections.emptySet()))
                    .isInstanceOf(PermissionGovernanceException.class)
                    .hasMessageContaining("at least one valid permission");
        }

        @Test
        @DisplayName("Rejects blank or malformed permission strings")
        void rejectsMalformedPermissionStrings() {
            assertThatThrownBy(() -> governanceService.validatePermissions(Set.of("   ")))
                    .isInstanceOf(PermissionGovernanceException.class)
                    .hasMessageContaining("cannot be blank");

            assertThatThrownBy(() -> governanceService.validatePermissions(Set.of("invalidformat")))
                    .isInstanceOf(PermissionGovernanceException.class)
                    .hasMessageContaining("grammar");
        }

        @Test
        @DisplayName("Rejects unknown permissions not registered in canonical registry")
        void rejectsUnregisteredPermissions() {
            assertThatThrownBy(() -> governanceService.validatePermissions(Set.of("custom.do_something")))
                    .isInstanceOf(PermissionGovernanceException.class)
                    .hasMessageContaining("not registered in the canonical permission registry");
        }

        @Test
        @DisplayName("Rejects platform-scoped permissions in organization custom roles")
        void rejectsPlatformScopedPermissions() {
            assertThatThrownBy(() -> governanceService.validatePermissions(Set.of("platform.audit.read", "project.read")))
                    .isInstanceOf(PermissionGovernanceException.class)
                    .hasMessageContaining("Platform-scoped permission cannot be included");
        }

        @Test
        @DisplayName("Rejects privileged org.delete permission")
        void rejectsOrgDeletePermission() {
            assertThatThrownBy(() -> governanceService.validatePermissions(Set.of("org.delete", "project.read")))
                    .isInstanceOf(PermissionGovernanceException.class)
                    .hasMessageContaining("Privileged permission 'org.delete' is reserved exclusively");
        }

        @Test
        @DisplayName("Enforces composite permission dependency (analytics.export requires analytics.view)")
        void enforcesCompositePermissionDependency() {
            assertThatThrownBy(() -> governanceService.validatePermissions(Set.of("analytics.export")))
                    .isInstanceOf(PermissionGovernanceException.class)
                    .hasMessageContaining("'analytics.export' requires 'analytics.view'");

            // When both are present, validation succeeds
            Set<Permission> validated = governanceService.validatePermissions(Set.of("analytics.export", "analytics.view"));
            assertThat(validated).contains(Permission.of("analytics.export"), Permission.of("analytics.view"));
        }
    }

    @Nested
    @DisplayName("Delegation Authority Tests")
    class DelegationAuthorityTests {

        @Test
        @DisplayName("Organization Owner can delegate any valid organization-scoped permission")
        void ownerCanDelegateAnyOrgPermission() {
            when(databaseRoleResolver.resolveRoles(eq(ownerId), any(OrganizationAuthorizationContext.class)))
                    .thenReturn(Set.of(Role.ORGANIZATION_OWNER));

            Set<Permission> permissions = Set.of(
                    PermissionRegistry.ORG_MANAGE,
                    PermissionRegistry.BILLING_VIEW,
                    PermissionRegistry.BILLING_MANAGE
            );

            // Should complete without exception
            governanceService.validateDelegationAuthority(orgId, ownerId, permissions);
        }

        @Test
        @DisplayName("Non-admin/non-owner member cannot manage custom roles")
        void regularMemberCannotManageCustomRoles() {
            when(databaseRoleResolver.resolveRoles(eq(memberId), any(OrganizationAuthorizationContext.class)))
                    .thenReturn(Set.of(Role.ORGANIZATION_MEMBER));

            Set<Permission> permissions = Set.of(PermissionRegistry.PROJECT_READ);

            assertThatThrownBy(() -> governanceService.validateDelegationAuthority(orgId, memberId, permissions))
                    .isInstanceOf(CustomRoleAccessDeniedException.class)
                    .hasMessageContaining("Requires ORGANIZATION_OWNER or ORGANIZATION_ADMIN");
        }

        @Test
        @DisplayName("Admin can delegate permissions they actively possess")
        void adminCanDelegatePossessedPermissions() {
            when(databaseRoleResolver.resolveRoles(eq(adminId), any(OrganizationAuthorizationContext.class)))
                    .thenReturn(Set.of(Role.ORGANIZATION_ADMIN));

            // ORGANIZATION_ADMIN has project.read, project.write, member.invite, etc.
            Set<Permission> permissions = Set.of(
                    PermissionRegistry.PROJECT_READ,
                    PermissionRegistry.PROJECT_WRITE
            );

            governanceService.validateDelegationAuthority(orgId, adminId, permissions);
        }

        @Test
        @DisplayName("Admin cannot delegate permissions they do not possess (e.g. billing.manage)")
        void adminCannotDelegateUnpossessedPermissions() {
            when(databaseRoleResolver.resolveRoles(eq(adminId), any(OrganizationAuthorizationContext.class)))
                    .thenReturn(Set.of(Role.ORGANIZATION_ADMIN));

            // ORGANIZATION_ADMIN does not have billing.manage
            Set<Permission> permissions = Set.of(
                    PermissionRegistry.PROJECT_READ,
                    PermissionRegistry.BILLING_MANAGE
            );

            assertThatThrownBy(() -> governanceService.validateDelegationAuthority(orgId, adminId, permissions))
                    .isInstanceOf(CustomRoleAccessDeniedException.class)
                    .hasMessageContaining("lacks permission to delegate [billing.manage]");
        }

        @Test
        @DisplayName("Admin can delegate permissions held through their active custom roles")
        void adminCanDelegatePermissionsHeldViaCustomRole() {
            when(databaseRoleResolver.resolveRoles(eq(adminId), any(OrganizationAuthorizationContext.class)))
                    .thenReturn(Set.of(Role.ORGANIZATION_ADMIN));

            // Admin is also assigned an active custom role that grants billing.view
            PermissionEntity billingViewEntity = PermissionEntity.fromDomainPermission(
                    PermissionRegistry.BILLING_VIEW,
                    UUID.randomUUID(),
                    "View billing info"
            );

            CustomRoleEntity customRole = CustomRoleEntity.builder()
                    .name("BILLING_VIEWER")
                    .active(true)
                    .build();

            CustomRolePermissionEntity permMapping = CustomRolePermissionEntity.builder()
                    .customRole(customRole)
                    .permission(billingViewEntity)
                    .build();

            customRole.setRolePermissions(Set.of(permMapping));

            CustomRoleAssignment assignment = CustomRoleAssignment.builder()
                    .customRole(customRole)
                    .build();

            when(customRoleAssignmentRepository.findActiveByOrganizationIdAndUserId(eq(orgId), eq(adminId), any(Instant.class)))
                    .thenReturn(List.of(assignment));

            Set<Permission> permissions = Set.of(
                    PermissionRegistry.PROJECT_READ,
                    PermissionRegistry.BILLING_VIEW
            );

            // Should succeed because billing.view is held via active custom role
            governanceService.validateDelegationAuthority(orgId, adminId, permissions);
        }
    }

    @Nested
    @DisplayName("Catalog Entity Resolution")
    class CatalogEntityResolutionTests {

        @Test
        @DisplayName("Resolves persistent permission entities when all exist")
        void resolvesExistingPermissionEntities() {
            PermissionEntity readEntity = PermissionEntity.builder().name("project.read").build();
            PermissionEntity writeEntity = PermissionEntity.builder().name("project.write").build();

            when(permissionRepository.findByNameIn(Set.of("project.read", "project.write")))
                    .thenReturn(List.of(readEntity, writeEntity));

            Set<PermissionEntity> entities = governanceService.resolvePermissionEntities(
                    Set.of(PermissionRegistry.PROJECT_READ, PermissionRegistry.PROJECT_WRITE)
            );

            assertThat(entities).hasSize(2);
        }

        @Test
        @DisplayName("Fails closed when requested permission entity is missing in database catalog")
        void failsClosedWhenEntityMissing() {
            PermissionEntity readEntity = PermissionEntity.builder().name("project.read").build();

            when(permissionRepository.findByNameIn(Set.of("project.read", "project.write")))
                    .thenReturn(List.of(readEntity));

            assertThatThrownBy(() -> governanceService.resolvePermissionEntities(
                    Set.of(PermissionRegistry.PROJECT_READ, PermissionRegistry.PROJECT_WRITE)
            ))
                    .isInstanceOf(PermissionGovernanceException.class)
                    .hasMessageContaining("Database catalog missing permissions: [project.write]");
        }
    }
}
