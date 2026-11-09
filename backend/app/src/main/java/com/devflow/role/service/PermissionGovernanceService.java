package com.devflow.role.service;

import com.devflow.modules.auth.context.OrganizationAuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.domain.RoleScope;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.modules.auth.registry.RolePermissionMapping;
import com.devflow.permission.domain.PermissionEntity;
import com.devflow.permission.repository.PermissionRepository;
import com.devflow.role.adapter.DatabaseRoleResolver;
import com.devflow.role.domain.CustomRoleAssignment;
import com.devflow.role.exception.CustomRoleAccessDeniedException;
import com.devflow.role.exception.PermissionGovernanceException;
import com.devflow.role.repository.CustomRoleAssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service enforcing permission governance, delegation boundaries, and composite policies (Stage 7).
 *
 * <h2>Governance Invariants</h2>
 * <ul>
 *   <li><strong>Canonical Registry Authority:</strong> Only registered canonical permissions are permitted.</li>
 *   <li><strong>Scope Confinement:</strong> Platform-scoped permissions are strictly rejected.</li>
 *   <li><strong>Privileged Permission Protection:</strong> {@code org.delete} is strictly prohibited from custom roles.</li>
 *   <li><strong>Delegation Boundary:</strong> Principals cannot delegate privileges they do not hold.</li>
 *   <li><strong>Composite Permission Validation:</strong> Validates prerequisite combinations (e.g. {@code analytics.export} requires {@code analytics.view}).</li>
 * </ul>
 */
@Service
public class PermissionGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(PermissionGovernanceService.class);

    private final PermissionRegistry permissionRegistry;
    private final RolePermissionMapping rolePermissionMapping;
    private final PermissionRepository permissionRepository;
    private final DatabaseRoleResolver databaseRoleResolver;
    private final CustomRoleAssignmentRepository customRoleAssignmentRepository;

    @Autowired
    public PermissionGovernanceService(
            PermissionRegistry permissionRegistry,
            RolePermissionMapping rolePermissionMapping,
            PermissionRepository permissionRepository,
            DatabaseRoleResolver databaseRoleResolver,
            @Autowired(required = false) CustomRoleAssignmentRepository customRoleAssignmentRepository
    ) {
        this.permissionRegistry = Objects.requireNonNull(permissionRegistry, "permissionRegistry must not be null");
        this.rolePermissionMapping = Objects.requireNonNull(rolePermissionMapping, "rolePermissionMapping must not be null");
        this.permissionRepository = Objects.requireNonNull(permissionRepository, "permissionRepository must not be null");
        this.databaseRoleResolver = Objects.requireNonNull(databaseRoleResolver, "databaseRoleResolver must not be null");
        this.customRoleAssignmentRepository = customRoleAssignmentRepository;
    }

    /**
     * Validates a set of raw permission strings against canonical catalog and governance invariants.
     *
     * @param permissionStrings raw permission names
     * @return validated set of immutable {@link Permission} value objects
     * @throws PermissionGovernanceException if any permission string violates governance invariants
     */
    public Set<Permission> validatePermissions(Set<String> permissionStrings) {
        if (permissionStrings == null || permissionStrings.isEmpty()) {
            throw new PermissionGovernanceException("Custom role must contain at least one valid permission");
        }

        Set<Permission> validatedPermissions = new HashSet<>();

        for (String permStr : permissionStrings) {
            if (permStr == null || permStr.isBlank()) {
                throw new PermissionGovernanceException("Permission string cannot be blank");
            }

            Permission permission;
            try {
                permission = Permission.of(permStr);
            } catch (IllegalArgumentException e) {
                throw new PermissionGovernanceException("Invalid permission format: " + permStr + ". Must follow '{resource}.{action}' grammar");
            }

            // 1. Canonical Permission Registry Check
            if (!permissionRegistry.isRegistered(permission)) {
                throw new PermissionGovernanceException("Permission '" + permStr + "' is not registered in the canonical permission registry");
            }

            // 2. Platform Scope Rejection
            if ("platform".equalsIgnoreCase(permission.resource()) || permStr.toLowerCase().startsWith("platform.")) {
                throw new PermissionGovernanceException("Platform-scoped permission cannot be included in an organization custom role: " + permStr);
            }

            // 3. Prohibited Privileged Permissions
            if (PermissionRegistry.ORG_DELETE.equals(permission)) {
                throw new PermissionGovernanceException("Privileged permission 'org.delete' is reserved exclusively for canonical ORGANIZATION_OWNER and cannot be delegated to custom roles");
            }

            validatedPermissions.add(permission);
        }

        // 4. Composite Permission Validation
        validateCompositePolicies(validatedPermissions);

        return Collections.unmodifiableSet(validatedPermissions);
    }

    /**
     * Enforces the delegation boundary: the calling principal must actively hold all permissions
     * being assigned to the custom role within the target organization tenant.
     *
     * @param organizationId tenant organization UUID
     * @param callerUserId principal creating or updating the custom role
     * @param targetPermissions permissions to be granted
     * @throws CustomRoleAccessDeniedException if caller lacks delegation authority
     */
    public void validateDelegationAuthority(UUID organizationId, UUID callerUserId, Set<Permission> targetPermissions) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");
        Objects.requireNonNull(targetPermissions, "targetPermissions must not be null");

        // 1. Resolve caller's active organization roles
        Set<Role> callerRoles = databaseRoleResolver.resolveRoles(
                callerUserId,
                OrganizationAuthorizationContext.of(organizationId)
        );

        boolean isCallerOwner = callerRoles.contains(Role.ORGANIZATION_OWNER);
        boolean isCallerAdmin = callerRoles.contains(Role.ORGANIZATION_ADMIN);

        if (!isCallerOwner && !isCallerAdmin) {
            throw new CustomRoleAccessDeniedException(
                    "User [" + callerUserId + "] is not authorized to manage custom roles in organization [" + organizationId + "]. Requires ORGANIZATION_OWNER or ORGANIZATION_ADMIN role."
            );
        }

        // Owners hold all non-platform permissions and can delegate any organization-scoped permission
        if (isCallerOwner) {
            return;
        }

        // 2. For non-owner administrators, compute caller's effective granted permissions
        Set<Permission> callerEffectivePermissions = new HashSet<>(
                rolePermissionMapping.getPermissionsForRoles(callerRoles, RoleScope.ORGANIZATION)
        );

        // Include any permissions held via caller's custom roles
        if (customRoleAssignmentRepository != null) {
            Instant now = Instant.now();
            List<CustomRoleAssignment> activeAssignments = customRoleAssignmentRepository
                    .findActiveByOrganizationIdAndUserId(organizationId, callerUserId, now);
            for (CustomRoleAssignment assignment : activeAssignments) {
                if (assignment.getCustomRole().isActive()) {
                    assignment.getCustomRole().getRolePermissions().forEach(rp ->
                            callerEffectivePermissions.add(rp.getPermission().toDomainPermission())
                    );
                }
            }
        }

        // 3. Verify that every requested permission is held by caller
        for (Permission requested : targetPermissions) {
            if (!callerEffectivePermissions.contains(requested)) {
                throw new CustomRoleAccessDeniedException(
                        "User [" + callerUserId + "] lacks permission to delegate [" + requested.value() + "] in organization [" + organizationId + "]"
                );
            }
        }
    }

    /**
     * Resolves and fetches persistent {@link PermissionEntity} records corresponding to the validated permissions.
     */
    public Set<PermissionEntity> resolvePermissionEntities(Set<Permission> permissions) {
        Set<String> names = permissions.stream().map(Permission::value).collect(Collectors.toSet());
        List<PermissionEntity> entities = permissionRepository.findByNameIn(names);

        if (entities.size() != permissions.size()) {
            Set<String> foundNames = entities.stream().map(PermissionEntity::getName).collect(Collectors.toSet());
            Set<String> missing = new HashSet<>(names);
            missing.removeAll(foundNames);
            throw new PermissionGovernanceException("Database catalog missing permissions: " + missing);
        }

        return new HashSet<>(entities);
    }

    /**
     * Validates that requested permissions respect canonical composite authorization rules (§6.4).
     */
    private void validateCompositePolicies(Set<Permission> permissions) {
        // analytics.export requires analytics.view
        if (permissions.contains(PermissionRegistry.ANALYTICS_EXPORT) && !permissions.contains(PermissionRegistry.ANALYTICS_VIEW)) {
            throw new PermissionGovernanceException("Composite permission requirement not met: 'analytics.export' requires 'analytics.view'");
        }
    }
}
