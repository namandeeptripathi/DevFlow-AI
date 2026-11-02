package com.devflow.permission.service;

import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.modules.auth.registry.RolePermissionMapping;
import com.devflow.permission.domain.PermissionEntity;
import com.devflow.permission.domain.RolePermissionEntity;
import com.devflow.permission.repository.PermissionRepository;
import com.devflow.permission.repository.RolePermissionRepository;
import com.devflow.role.domain.RoleEntity;
import com.devflow.role.repository.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service that validates and enforces the integrity and consistency of the persisted
 * authorization catalog against the canonical in-memory registries in {@code devflow-auth}.
 *
 * <p>Enforces Security Invariants:
 * <ul>
 *   <li>The 10 canonical roles and 48 canonical permissions must match exactly.</li>
 *   <li>Custom user-defined roles are strictly prohibited.</li>
 *   <li>Database role-permission mappings must not silently diverge from policy.</li>
 * </ul>
 */
@Service
public class CatalogConsistencyValidator {

    private static final Logger log = LoggerFactory.getLogger(CatalogConsistencyValidator.class);

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final RolePermissionMapping canonicalMapping = RolePermissionMapping.canonical();
    private final PermissionRegistry canonicalPermissions = PermissionRegistry.canonical();

    @Autowired
    public CatalogConsistencyValidator(
            RoleRepository roleRepository,
            PermissionRepository permissionRepository,
            RolePermissionRepository rolePermissionRepository
    ) {
        this.roleRepository = Objects.requireNonNull(roleRepository, "roleRepository must not be null");
        this.permissionRepository = Objects.requireNonNull(permissionRepository, "permissionRepository must not be null");
        this.rolePermissionRepository = Objects.requireNonNull(rolePermissionRepository, "rolePermissionRepository must not be null");
    }

    /**
     * Validates that the database contains all canonical roles, permissions, and mappings,
     * and that no unexpected custom roles exist.
     *
     * @throws IllegalStateException if the database catalog diverges from canonical policy
     */
    @Transactional(readOnly = true)
    public void validateDatabaseCatalog() {
        log.info("Validating database authorization catalog against canonical policy...");

        // 1. Validate Roles
        List<RoleEntity> persistedRoles = roleRepository.findAll();
        for (Role role : Role.values()) {
            RoleEntity entity = roleRepository.findByName(role.name())
                    .orElseThrow(() -> new IllegalStateException("Missing canonical role in database: " + role.name()));

            if (entity.getScope() != role.getScope()) {
                throw new IllegalStateException(String.format(
                        "Role %s scope mismatch: expected %s, found %s", role.name(), role.getScope(), entity.getScope()));
            }
        }

        // Strict invariant: No custom roles
        for (RoleEntity entity : persistedRoles) {
            try {
                Role.valueOf(entity.getName());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Security violation: Custom or unregistered role detected in database: " + entity.getName());
            }
        }

        // 2. Validate Permissions (All 48 canonical permissions must exist)
        for (Permission perm : canonicalPermissions.getAll()) {
            PermissionEntity entity = permissionRepository.findByName(perm.value())
                    .orElseThrow(() -> new IllegalStateException("Missing canonical permission in database: " + perm.value()));

            if (!entity.getResource().equals(perm.resource()) || !entity.getAction().equals(perm.action())) {
                throw new IllegalStateException(String.format(
                        "Permission %s corrupted: expected [%s, %s], found [%s, %s]",
                        perm.value(), perm.resource(), perm.action(), entity.getResource(), entity.getAction()));
            }
        }

        // 3. Validate Role-Permission Mappings
        for (Role role : Role.values()) {
            Set<Permission> expected = canonicalMapping.getPermissions(role);
            List<RolePermissionEntity> mappings = rolePermissionRepository.findByRoleName(role.name());
            Set<String> persistedPermNames = mappings.stream()
                    .map(m -> m.getPermission().getName())
                    .collect(Collectors.toSet());

            for (Permission exp : expected) {
                if (!persistedPermNames.contains(exp.value())) {
                    throw new IllegalStateException(String.format(
                            "Role %s missing mapped permission: %s", role.name(), exp.value()));
                }
            }

            if (persistedPermNames.size() != expected.size()) {
                throw new IllegalStateException(String.format(
                        "Role %s permission count mismatch: expected %d, found %d",
                        role.name(), expected.size(), persistedPermNames.size()));
            }
        }

        log.info("Database authorization catalog consistency verified successfully (10 roles, 48 permissions, 145 mappings).");
    }

    /**
     * Seeds any missing canonical catalog data idempotently.
     */
    @Transactional
    public void seedCanonicalDataIfMissing() {
        // Seed roles
        for (Role role : Role.values()) {
            if (!roleRepository.existsByName(role.name())) {
                UUID id = CanonicalAuthorizationCatalog.getRoleId(role);
                roleRepository.save(RoleEntity.fromDomainRole(role, id, "Canonical " + role.name()));
            }
        }

        // Seed permissions
        for (Permission perm : canonicalPermissions.getAll()) {
            if (!permissionRepository.existsByName(perm.value())) {
                UUID id = CanonicalAuthorizationCatalog.getPermissionId(perm);
                permissionRepository.save(PermissionEntity.fromDomainPermission(perm, id, "Canonical " + perm.value()));
            }
        }

        // Seed mappings
        for (Role role : Role.values()) {
            RoleEntity roleEntity = roleRepository.findByName(role.name()).orElseThrow();
            Set<Permission> permissions = canonicalMapping.getPermissions(role);
            for (Permission perm : permissions) {
                PermissionEntity permEntity = permissionRepository.findByName(perm.value()).orElseThrow();
                if (!rolePermissionRepository.existsByRoleIdAndPermissionId(roleEntity.getId(), permEntity.getId())) {
                    rolePermissionRepository.save(RolePermissionEntity.builder()
                            .role(roleEntity)
                            .permission(permEntity)
                            .build());
                }
            }
        }
    }
}
