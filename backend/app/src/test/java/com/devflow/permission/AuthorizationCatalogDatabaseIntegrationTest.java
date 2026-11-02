package com.devflow.permission;

import com.devflow.config.JpaAuditingConfiguration;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.domain.RoleScope;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.modules.auth.registry.RolePermissionMapping;
import com.devflow.permission.domain.PermissionEntity;
import com.devflow.permission.domain.RolePermissionEntity;
import com.devflow.permission.repository.PermissionRepository;
import com.devflow.permission.repository.RolePermissionRepository;
import com.devflow.permission.service.CanonicalAuthorizationCatalog;
import com.devflow.permission.service.CatalogConsistencyValidator;
import com.devflow.role.domain.RoleEntity;
import com.devflow.role.repository.RoleRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({JpaAuditingConfiguration.class, CatalogConsistencyValidator.class})
@ActiveProfiles("test")
@DisplayName("Canonical Authorization Catalog Database Integration Tests")
class AuthorizationCatalogDatabaseIntegrationTest {

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private CatalogConsistencyValidator catalogValidator;

    @Autowired
    private EntityManager entityManager;

    private final RolePermissionMapping canonicalMapping = RolePermissionMapping.canonical();
    private final PermissionRegistry canonicalPermissions = PermissionRegistry.canonical();

    @Test
    @DisplayName("Verify all 10 canonical roles are seeded with stable UUIDs and correct scopes")
    void shouldVerifyAllTenCanonicalRolesSeeded() {
        List<RoleEntity> allRoles = roleRepository.findAll();
        assertThat(allRoles).hasSize(10);

        for (Role canonicalRole : Role.values()) {
            Optional<RoleEntity> roleOpt = roleRepository.findByName(canonicalRole.name());
            assertThat(roleOpt).isPresent();

            RoleEntity entity = roleOpt.get();
            assertThat(entity.getScope())
                    .as("Role %s scope must match canonical definition", canonicalRole.name())
                    .isEqualTo(canonicalRole.getScope());

            UUID expectedStableId = CanonicalAuthorizationCatalog.getRoleId(canonicalRole);
            assertThat(entity.getId())
                    .as("Role %s must match deterministic UUID", canonicalRole.name())
                    .isEqualTo(expectedStableId);

            assertThat(entity.isSystem()).isTrue();
            assertThat(entity.toDomainRole()).isEqualTo(canonicalRole);
        }
    }

    @Test
    @DisplayName("Verify all 48 canonical permissions are seeded with stable UUIDs and correct grammar")
    void shouldVerifyAllFortyEightCanonicalPermissionsSeeded() {
        List<PermissionEntity> allPermissions = permissionRepository.findAll();
        assertThat(allPermissions).hasSize(48);

        for (Permission canonicalPerm : canonicalPermissions.getAll()) {
            Optional<PermissionEntity> permOpt = permissionRepository.findByName(canonicalPerm.value());
            assertThat(permOpt).isPresent();

            PermissionEntity entity = permOpt.get();
            assertThat(entity.getResource()).isEqualTo(canonicalPerm.resource());
            assertThat(entity.getAction()).isEqualTo(canonicalPerm.action());

            UUID expectedStableId = CanonicalAuthorizationCatalog.getPermissionId(canonicalPerm);
            assertThat(entity.getId())
                    .as("Permission %s must match deterministic UUID", canonicalPerm.value())
                    .isEqualTo(expectedStableId);

            assertThat(entity.toDomainPermission()).isEqualTo(canonicalPerm);
        }
    }

    @Test
    @DisplayName("Verify database role-permission mappings match canonical policy matrix exactly (145 mappings)")
    void shouldVerifyRolePermissionMappingsMatchCanonicalMatrix() {
        long totalMappings = rolePermissionRepository.count();
        assertThat(totalMappings).isEqualTo(145);

        for (Role role : Role.values()) {
            Set<Permission> expectedPermissions = canonicalMapping.getPermissions(role);
            List<RolePermissionEntity> roleMappings = rolePermissionRepository.findByRoleName(role.name());

            Set<String> persistedPermValues = roleMappings.stream()
                    .map(m -> m.getPermission().getName())
                    .collect(Collectors.toSet());

            Set<String> expectedPermValues = expectedPermissions.stream()
                    .map(Permission::value)
                    .collect(Collectors.toSet());

            assertThat(persistedPermValues)
                    .as("Permissions for role %s must match canonical policy matrix", role.name())
                    .containsExactlyInAnyOrderElementsOf(expectedPermValues);
        }
    }

    @Test
    @DisplayName("Verify catalog consistency validator succeeds against seeded database")
    void shouldValidateDatabaseCatalogSuccessfully() {
        catalogValidator.validateDatabaseCatalog();
    }

    @Test
    @DisplayName("Security invariant: ORGANIZATION_BILLING is strictly isolated to billing view and manage")
    void shouldEnforceBillingRoleIsolationInCatalog() {
        List<RolePermissionEntity> billingMappings = rolePermissionRepository.findByRoleName("ORGANIZATION_BILLING");
        Set<String> permNames = billingMappings.stream()
                .map(m -> m.getPermission().getName())
                .collect(Collectors.toSet());

        assertThat(permNames).containsExactlyInAnyOrder("billing.view", "billing.manage");
        assertThat(permNames).noneMatch(name -> name.startsWith("project.") || name.startsWith("task.") || name.startsWith("repo."));
    }

    @Test
    @DisplayName("Security invariant: PLATFORM_SUPPORT is strictly isolated to read-only customer support permissions")
    void shouldEnforcePlatformSupportRoleIsolationInCatalog() {
        List<RolePermissionEntity> supportMappings = rolePermissionRepository.findByRoleName("PLATFORM_SUPPORT");
        Set<String> permNames = supportMappings.stream()
                .map(m -> m.getPermission().getName())
                .collect(Collectors.toSet());

        assertThat(permNames).containsExactlyInAnyOrder("platform.support.case.read", "platform.support.tenant.read");
        assertThat(permNames).noneMatch(name -> name.contains("provision") || name.contains("execute") || name.contains("manage"));
    }

    @Test
    @DisplayName("Database uniqueness constraint rejects duplicate role name")
    void shouldRejectDuplicateRoleName() {
        RoleEntity duplicateRole = RoleEntity.builder()
                .id(UUID.randomUUID())
                .name("ORGANIZATION_OWNER") // Already seeded
                .scope(RoleScope.ORGANIZATION)
                .description("Duplicate owner")
                .system(true)
                .build();

        assertThatThrownBy(() -> {
            roleRepository.saveAndFlush(duplicateRole);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Database uniqueness constraint rejects duplicate permission name")
    void shouldRejectDuplicatePermissionName() {
        PermissionEntity duplicatePerm = PermissionEntity.builder()
                .id(UUID.randomUUID())
                .name("project.read") // Already seeded
                .resource("project")
                .action("read")
                .description("Duplicate read permission")
                .build();

        assertThatThrownBy(() -> {
            permissionRepository.saveAndFlush(duplicatePerm);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Database uniqueness constraint rejects duplicate role-permission mapping")
    void shouldRejectDuplicateRolePermissionMapping() {
        RoleEntity ownerRole = roleRepository.findByName("ORGANIZATION_OWNER").orElseThrow();
        PermissionEntity orgManage = permissionRepository.findByName("org.manage").orElseThrow();

        // Already exists in seed data
        assertThat(rolePermissionRepository.existsByRoleIdAndPermissionId(ownerRole.getId(), orgManage.getId())).isTrue();

        RolePermissionEntity duplicateMapping = RolePermissionEntity.builder()
                .role(ownerRole)
                .permission(orgManage)
                .build();

        assertThatThrownBy(() -> {
            rolePermissionRepository.saveAndFlush(duplicateMapping);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Idempotent seeding helper does not create duplicates")
    void shouldEnsureSeedingIsIdempotent() {
        catalogValidator.seedCanonicalDataIfMissing();

        assertThat(roleRepository.count()).isEqualTo(10);
        assertThat(permissionRepository.count()).isEqualTo(48);
        assertThat(rolePermissionRepository.count()).isEqualTo(145);
    }
}
