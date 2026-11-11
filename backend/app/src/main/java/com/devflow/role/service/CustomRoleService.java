package com.devflow.role.service;

import com.devflow.modules.auth.context.OrganizationAuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.exception.OrganizationNotFoundException;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.permission.domain.PermissionEntity;
import com.devflow.role.adapter.DatabaseRoleResolver;
import com.devflow.role.domain.CustomRoleEntity;
import com.devflow.role.domain.CustomRolePermissionEntity;
import com.devflow.role.dto.CreateCustomRoleRequest;
import com.devflow.role.dto.UpdateCustomRoleRequest;
import com.devflow.role.exception.CustomRoleAccessDeniedException;
import com.devflow.role.exception.CustomRoleAlreadyExistsException;
import com.devflow.role.exception.CustomRoleDeletionException;
import com.devflow.role.exception.CustomRoleNotFoundException;
import com.devflow.role.exception.InvalidCustomRoleException;
import com.devflow.role.repository.CustomRoleAssignmentRepository;
import com.devflow.role.repository.CustomRolePermissionRepository;
import com.devflow.role.repository.CustomRoleRepository;
import com.devflow.security.authorization.cache.AuthorizationCacheService;
import com.devflow.user.domain.User;
import com.devflow.user.exception.UserProfileNotFoundException;
import com.devflow.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Application service managing the lifecycle and definition of organization-scoped custom roles (Stage 7).
 */
@Service
public class CustomRoleService {

    private static final Logger log = LoggerFactory.getLogger(CustomRoleService.class);

    private final CustomRoleRepository customRoleRepository;
    private final CustomRolePermissionRepository customRolePermissionRepository;
    private final CustomRoleAssignmentRepository customRoleAssignmentRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final PermissionGovernanceService permissionGovernanceService;
    private final DatabaseRoleResolver databaseRoleResolver;
    private final AuthorizationCacheService cacheService;

    @Autowired(required = false)
    private com.devflow.modules.auth.audit.AuthorizationAuditRecorder auditRecorder;

    public void setAuditRecorder(com.devflow.modules.auth.audit.AuthorizationAuditRecorder auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    @Autowired
    public CustomRoleService(
            CustomRoleRepository customRoleRepository,
            CustomRolePermissionRepository customRolePermissionRepository,
            CustomRoleAssignmentRepository customRoleAssignmentRepository,
            OrganizationRepository organizationRepository,
            UserRepository userRepository,
            PermissionGovernanceService permissionGovernanceService,
            DatabaseRoleResolver databaseRoleResolver,
            @Autowired(required = false) AuthorizationCacheService cacheService
    ) {
        this.customRoleRepository = Objects.requireNonNull(customRoleRepository, "customRoleRepository must not be null");
        this.customRolePermissionRepository = Objects.requireNonNull(customRolePermissionRepository, "customRolePermissionRepository must not be null");
        this.customRoleAssignmentRepository = Objects.requireNonNull(customRoleAssignmentRepository, "customRoleAssignmentRepository must not be null");
        this.organizationRepository = Objects.requireNonNull(organizationRepository, "organizationRepository must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.permissionGovernanceService = Objects.requireNonNull(permissionGovernanceService, "permissionGovernanceService must not be null");
        this.databaseRoleResolver = Objects.requireNonNull(databaseRoleResolver, "databaseRoleResolver must not be null");
        this.cacheService = cacheService;
    }

    /**
     * Creates a new organization-scoped custom role.
     */
    @Transactional
    public CustomRoleEntity createCustomRole(
            UUID organizationId,
            UUID callerUserId,
            CreateCustomRoleRequest request
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new OrganizationNotFoundException("Organization not found: " + organizationId));

        User callerUser = userRepository.findById(callerUserId)
                .orElseThrow(() -> new UserProfileNotFoundException("User not found: " + callerUserId));

        // 1. Permission Governance & Delegation Validation
        Set<Permission> validatedPermissions = permissionGovernanceService.validatePermissions(request.getPermissions());
        permissionGovernanceService.validateDelegationAuthority(organizationId, callerUserId, validatedPermissions);

        // 2. Programmatic name normalization & uniqueness check
        String normalizedName = request.getName().trim().toUpperCase(Locale.ROOT);
        if (customRoleRepository.existsByOrganizationIdAndName(organizationId, normalizedName)) {
            throw new CustomRoleAlreadyExistsException(
                    "Custom role with name [" + normalizedName + "] already exists in organization [" + organizationId + "]"
            );
        }

        // 3. Resolve permission entities
        Set<PermissionEntity> permissionEntities = permissionGovernanceService.resolvePermissionEntities(validatedPermissions);

        // 4. Build and save custom role entity
        CustomRoleEntity customRole = CustomRoleEntity.builder()
                .organization(organization)
                .name(normalizedName)
                .displayName(request.getDisplayName().trim())
                .description(request.getDescription() != null ? request.getDescription().trim() : null)
                .active(true)
                .createdBy(callerUser)
                .build();

        // 5. Associate permission mappings
        for (PermissionEntity permEntity : permissionEntities) {
            CustomRolePermissionEntity mapping = CustomRolePermissionEntity.builder()
                    .customRole(customRole)
                    .permission(permEntity)
                    .build();
            customRole.getRolePermissions().add(mapping);
        }

        CustomRoleEntity savedRole = customRoleRepository.save(customRole);

        // 6. Cache invalidation
        if (cacheService != null) {
            cacheService.evictCustomRole(organizationId, savedRole.getId());
        }

        // 7. Security audit recording
        if (auditRecorder != null) {
            auditRecorder.record(com.devflow.modules.auth.audit.AuthorizationAuditEvent.builder()
                    .eventType(com.devflow.modules.auth.audit.AuthorizationAuditEventType.CUSTOM_ROLE_CREATED)
                    .userId(callerUserId)
                    .scope(com.devflow.modules.auth.domain.RoleScope.ORGANIZATION)
                    .organizationId(organizationId)
                    .targetId(savedRole.getId())
                    .targetType("CUSTOM_ROLE")
                    .newState(savedRole.getName())
                    .decision(com.devflow.modules.auth.api.AuthorizationDecision.allow())
                    .build());

            for (PermissionEntity permEntity : permissionEntities) {
                auditRecorder.record(com.devflow.modules.auth.audit.AuthorizationAuditEvent.builder()
                        .eventType(com.devflow.modules.auth.audit.AuthorizationAuditEventType.CUSTOM_ROLE_PERMISSION_ADDED)
                        .userId(callerUserId)
                        .scope(com.devflow.modules.auth.domain.RoleScope.ORGANIZATION)
                        .organizationId(organizationId)
                        .targetId(savedRole.getId())
                        .targetType("CUSTOM_ROLE")
                        .newState(permEntity.getName())
                        .decision(com.devflow.modules.auth.api.AuthorizationDecision.allow())
                        .build());
            }
        }

        log.info("Created custom role [{}] ({}) in organization [{}] by [{}]",
                savedRole.getName(), savedRole.getId(), organizationId, callerUserId);

        return savedRole;
    }

    /**
     * Retrieves an existing custom role with permissions, validating tenant isolation and caller authorization.
     */
    @Transactional(readOnly = true)
    public CustomRoleEntity getCustomRole(UUID organizationId, UUID roleId, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(roleId, "roleId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        verifyCallerIsAdminOrOwner(organizationId, callerUserId);

        return customRoleRepository.findByIdAndOrganizationIdWithPermissions(roleId, organizationId)
                .orElseThrow(() -> new CustomRoleNotFoundException(
                        "Custom role [" + roleId + "] not found in organization [" + organizationId + "]"
                ));
    }

    /**
     * Lists custom roles belonging to an organization, validating caller authorization.
     */
    @Transactional(readOnly = true)
    public List<CustomRoleEntity> listCustomRoles(UUID organizationId, UUID callerUserId, Boolean activeOnly) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        verifyCallerIsAdminOrOwner(organizationId, callerUserId);

        if (!organizationRepository.existsById(organizationId)) {
            throw new OrganizationNotFoundException("Organization not found: " + organizationId);
        }

        List<CustomRoleEntity> roles = customRoleRepository.findByOrganizationIdWithPermissions(organizationId);
        if (Boolean.TRUE.equals(activeOnly)) {
            return roles.stream().filter(CustomRoleEntity::isActive).toList();
        }
        return roles;
    }

    /**
     * Updates an existing custom role definition.
     */
    @Transactional
    public CustomRoleEntity updateCustomRole(
            UUID organizationId,
            UUID roleId,
            UUID callerUserId,
            UpdateCustomRoleRequest request
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(roleId, "roleId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        // Verify caller authorization before probing role existence to prevent cross-tenant enumeration
        verifyCallerIsAdminOrOwner(organizationId, callerUserId);

        CustomRoleEntity role = customRoleRepository.findByIdAndOrganizationIdWithPermissions(roleId, organizationId)
                .orElseThrow(() -> new CustomRoleNotFoundException(
                        "Custom role [" + roleId + "] not found in organization [" + organizationId + "]"
                ));

        if (request.getDisplayName() != null && !request.getDisplayName().isBlank()) {
            role.setDisplayName(request.getDisplayName().trim());
        }

        if (request.getDescription() != null) {
            role.setDescription(request.getDescription().trim());
        }

        if (request.getActive() != null) {
            role.setActive(request.getActive());
        }

        // Update permissions if provided
        if (request.getPermissions() != null) {
            Set<Permission> validatedPermissions = permissionGovernanceService.validatePermissions(request.getPermissions());
            permissionGovernanceService.validateDelegationAuthority(organizationId, callerUserId, validatedPermissions);

            Set<PermissionEntity> targetEntities = permissionGovernanceService.resolvePermissionEntities(validatedPermissions);
            Set<UUID> targetPermIds = targetEntities.stream().map(PermissionEntity::getId).collect(Collectors.toSet());

            // 1. Remove permissions that are not in targetPermissions (triggers orphanRemoval)
            role.getRolePermissions().removeIf(mapping -> !targetPermIds.contains(mapping.getPermission().getId()));

            // 2. Identify existing permission IDs still attached
            Set<UUID> currentPermIds = role.getRolePermissions().stream()
                    .map(mapping -> mapping.getPermission().getId())
                    .collect(Collectors.toSet());

            // 3. Add only newly granted permissions
            for (PermissionEntity permEntity : targetEntities) {
                if (!currentPermIds.contains(permEntity.getId())) {
                    CustomRolePermissionEntity mapping = CustomRolePermissionEntity.builder()
                            .customRole(role)
                            .permission(permEntity)
                            .build();
                    role.getRolePermissions().add(mapping);
                }
            }
        }

        CustomRoleEntity updated = customRoleRepository.save(role);

        // Cache invalidation across organization
        if (cacheService != null) {
            cacheService.evictCustomRole(organizationId, roleId);
        }

        if (auditRecorder != null) {
            auditRecorder.record(com.devflow.modules.auth.audit.AuthorizationAuditEvent.builder()
                    .eventType(com.devflow.modules.auth.audit.AuthorizationAuditEventType.CUSTOM_ROLE_UPDATED)
                    .userId(callerUserId)
                    .scope(com.devflow.modules.auth.domain.RoleScope.ORGANIZATION)
                    .organizationId(organizationId)
                    .targetId(roleId)
                    .targetType("CUSTOM_ROLE")
                    .newState(updated.getName())
                    .decision(com.devflow.modules.auth.api.AuthorizationDecision.allow())
                    .build());

            if (request.getActive() != null && !request.getActive()) {
                auditRecorder.record(com.devflow.modules.auth.audit.AuthorizationAuditEvent.builder()
                        .eventType(com.devflow.modules.auth.audit.AuthorizationAuditEventType.CUSTOM_ROLE_DISABLED)
                        .userId(callerUserId)
                        .scope(com.devflow.modules.auth.domain.RoleScope.ORGANIZATION)
                        .organizationId(organizationId)
                        .targetId(roleId)
                        .targetType("CUSTOM_ROLE")
                        .reason("Custom role deactivated via update")
                        .decision(com.devflow.modules.auth.api.AuthorizationDecision.allow())
                        .build());
            }
        }

        log.info("Updated custom role [{}] ({}) in organization [{}] by [{}]",
                updated.getName(), roleId, organizationId, callerUserId);

        return updated;
    }

    /**
     * Deletes or safely deactivates a custom role.
     *
     * <p>If active assignments still depend on it, deletion is rejected.
     * If historical assignments exist, the role is safely deactivated/archived to preserve audit integrity.
     * If no assignments have ever been created, it is hard deleted.
     */
    @Transactional
    public void deleteCustomRole(UUID organizationId, UUID roleId, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(roleId, "roleId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        // Verify caller authorization before probing role existence to prevent cross-tenant enumeration
        verifyCallerIsAdminOrOwner(organizationId, callerUserId);

        CustomRoleEntity role = customRoleRepository.findByIdAndOrganizationIdWithPermissions(roleId, organizationId)
                .orElseThrow(() -> new CustomRoleNotFoundException(
                        "Custom role [" + roleId + "] not found in organization [" + organizationId + "]"
                ));

        // 1. Prevent deletion if active assignments exist
        Instant now = Instant.now();
        long activeAssignments = customRoleAssignmentRepository.countActiveByCustomRoleId(roleId, now);
        if (activeAssignments > 0) {
            throw new CustomRoleDeletionException(
                    "Cannot delete custom role [" + role.getName() + "]: " + activeAssignments
                            + " active assignment(s) still depend on it. Deactivate the role or revoke active assignments first."
            );
        }

        // 2. Safe deactivation vs hard deletion
        long historicalAssignments = customRoleAssignmentRepository.countByCustomRoleId(roleId);
        if (historicalAssignments > 0) {
            // Safely deactivate / archive to preserve historical assignment audit records
            role.setActive(false);
            customRoleRepository.save(role);
            if (auditRecorder != null) {
                auditRecorder.record(com.devflow.modules.auth.audit.AuthorizationAuditEvent.builder()
                        .eventType(com.devflow.modules.auth.audit.AuthorizationAuditEventType.CUSTOM_ROLE_DISABLED)
                        .userId(callerUserId)
                        .scope(com.devflow.modules.auth.domain.RoleScope.ORGANIZATION)
                        .organizationId(organizationId)
                        .targetId(roleId)
                        .targetType("CUSTOM_ROLE")
                        .reason("Archived custom role with historical assignments")
                        .decision(com.devflow.modules.auth.api.AuthorizationDecision.allow())
                        .build());
            }
            log.info("Safely deactivated custom role [{}] ({}) in organization [{}] to preserve historical assignment data",
                    role.getName(), roleId, organizationId);
        } else {
            // Safe to hard delete because no historical references exist
            customRoleRepository.delete(role);
            if (auditRecorder != null) {
                auditRecorder.record(com.devflow.modules.auth.audit.AuthorizationAuditEvent.builder()
                        .eventType(com.devflow.modules.auth.audit.AuthorizationAuditEventType.CUSTOM_ROLE_DELETED)
                        .userId(callerUserId)
                        .scope(com.devflow.modules.auth.domain.RoleScope.ORGANIZATION)
                        .organizationId(organizationId)
                        .targetId(roleId)
                        .targetType("CUSTOM_ROLE")
                        .reason("Permanently deleted unused custom role")
                        .decision(com.devflow.modules.auth.api.AuthorizationDecision.allow())
                        .build());
            }
            log.info("Deleted unused custom role [{}] ({}) in organization [{}]",
                    role.getName(), roleId, organizationId);
        }

        // 3. Invalidate cache
        if (cacheService != null) {
            cacheService.evictCustomRole(organizationId, roleId);
        }
    }

    private void verifyCallerIsAdminOrOwner(UUID organizationId, UUID callerUserId) {
        Set<Role> callerRoles = databaseRoleResolver.resolveRoles(
                callerUserId,
                OrganizationAuthorizationContext.of(organizationId)
        );

        if (!callerRoles.contains(Role.ORGANIZATION_OWNER) && !callerRoles.contains(Role.ORGANIZATION_ADMIN)) {
            throw new CustomRoleAccessDeniedException(
                    "User [" + callerUserId + "] is not authorized to manage custom roles in organization [" + organizationId + "]"
            );
        }
    }
}
