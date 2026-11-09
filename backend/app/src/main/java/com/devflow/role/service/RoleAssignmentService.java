package com.devflow.role.service;

import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.evaluator.TenantBoundaryValidator;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.exception.OrganizationNotFoundException;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.role.adapter.DatabaseRoleResolver;
import com.devflow.role.domain.CustomRoleAssignment;
import com.devflow.role.domain.CustomRoleEntity;
import com.devflow.role.domain.OrganizationRoleAssignment;
import com.devflow.role.domain.ProjectRoleAssignment;
import com.devflow.role.domain.RoleEntity;
import com.devflow.role.exception.InvalidRoleAssignmentException;
import com.devflow.role.exception.RoleAssignmentAccessDeniedException;
import com.devflow.role.exception.RoleAssignmentAlreadyExistsException;
import com.devflow.role.exception.RoleAssignmentNotFoundException;
import com.devflow.role.repository.CustomRoleAssignmentRepository;
import com.devflow.role.repository.CustomRoleRepository;
import com.devflow.role.repository.OrganizationRoleAssignmentRepository;
import com.devflow.security.authorization.cache.AuthorizationCacheService;
import com.devflow.role.repository.ProjectRoleAssignmentRepository;
import com.devflow.role.repository.RoleRepository;
import com.devflow.user.domain.User;
import com.devflow.user.exception.UserProfileNotFoundException;
import com.devflow.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Application service managing the lifecycle of scoped role assignments (Stage 4 & Stage 4.1).
 *
 * <p>Enforces core security invariants:
 * <ul>
 *   <li>Scope boundaries: Organization roles cannot be assigned to projects, and project roles cannot be assigned to organizations.</li>
 *   <li>Tenant isolation: Project roles require verified active organization membership in the enclosing tenant and optional {@link TenantBoundaryValidator}.</li>
 *   <li>Role assignment authorization: Only organization owners can grant/revoke BILLING and OWNER roles; admins cannot escalate.</li>
 *   <li>Project assignment authority: Requires organization owner/admin or project lead authority.</li>
 *   <li>Active uniqueness: Prevents duplicate active assignments for the same principal and scope.</li>
 *   <li>Expired assignment superseding: Automatically retires expired assignments to satisfy active partial unique indexes while maintaining audit trails.</li>
 *   <li>Audit preservation: Tracks assignment and revocation actors and timestamps.</li>
 * </ul>
 */
@Service
public class RoleAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(RoleAssignmentService.class);

    private final OrganizationRoleAssignmentRepository orgAssignmentRepository;
    private final ProjectRoleAssignmentRepository projectAssignmentRepository;
    private final RoleRepository roleRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final UserRepository userRepository;
    private TenantBoundaryValidator tenantBoundaryValidator;
    private AuthorizationCacheService cacheService;

    @Autowired(required = false)
    private CustomRoleRepository customRoleRepository;

    @Autowired(required = false)
    private CustomRoleAssignmentRepository customRoleAssignmentRepository;

    @Autowired(required = false)
    private PermissionGovernanceService permissionGovernanceService;

    @Autowired
    public RoleAssignmentService(
            OrganizationRoleAssignmentRepository orgAssignmentRepository,
            ProjectRoleAssignmentRepository projectAssignmentRepository,
            RoleRepository roleRepository,
            OrganizationRepository organizationRepository,
            OrganizationMemberRepository organizationMemberRepository,
            UserRepository userRepository,
            @Autowired(required = false) TenantBoundaryValidator tenantBoundaryValidator,
            @Autowired(required = false) AuthorizationCacheService cacheService
    ) {
        this.orgAssignmentRepository = Objects.requireNonNull(orgAssignmentRepository, "orgAssignmentRepository must not be null");
        this.projectAssignmentRepository = Objects.requireNonNull(projectAssignmentRepository, "projectAssignmentRepository must not be null");
        this.roleRepository = Objects.requireNonNull(roleRepository, "roleRepository must not be null");
        this.organizationRepository = Objects.requireNonNull(organizationRepository, "organizationRepository must not be null");
        this.organizationMemberRepository = Objects.requireNonNull(organizationMemberRepository, "organizationMemberRepository must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.tenantBoundaryValidator = tenantBoundaryValidator;
        this.cacheService = cacheService;
    }

    public RoleAssignmentService(
            OrganizationRoleAssignmentRepository orgAssignmentRepository,
            ProjectRoleAssignmentRepository projectAssignmentRepository,
            RoleRepository roleRepository,
            OrganizationRepository organizationRepository,
            OrganizationMemberRepository organizationMemberRepository,
            UserRepository userRepository,
            TenantBoundaryValidator tenantBoundaryValidator
    ) {
        this(orgAssignmentRepository, projectAssignmentRepository, roleRepository, organizationRepository,
                organizationMemberRepository, userRepository, tenantBoundaryValidator, null);
    }

    public void setTenantBoundaryValidator(TenantBoundaryValidator tenantBoundaryValidator) {
        this.tenantBoundaryValidator = tenantBoundaryValidator;
    }

    public void setCacheService(AuthorizationCacheService cacheService) {
        this.cacheService = cacheService;
    }

    public void setCustomRoleRepository(CustomRoleRepository customRoleRepository) {
        this.customRoleRepository = customRoleRepository;
    }

    public void setCustomRoleAssignmentRepository(CustomRoleAssignmentRepository customRoleAssignmentRepository) {
        this.customRoleAssignmentRepository = customRoleAssignmentRepository;
    }

    public void setPermissionGovernanceService(PermissionGovernanceService permissionGovernanceService) {
        this.permissionGovernanceService = permissionGovernanceService;
    }

    // ── Organization Role Assignments ─────────────────────────────────────────

    /**
     * Assigns an organization-scoped canonical role to a user.
     *
     * @param organizationId tenant organization UUID
     * @param targetUserId user receiving the role
     * @param role canonical organization role
     * @param assignedByUserId user performing the assignment
     * @param expiresAt optional expiration timestamp
     * @return the persisted {@link OrganizationRoleAssignment}
     */
    @Transactional
    public OrganizationRoleAssignment assignOrganizationRole(
            UUID organizationId,
            UUID targetUserId,
            Role role,
            UUID assignedByUserId,
            Instant expiresAt
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(assignedByUserId, "assignedByUserId must not be null");

        if (!role.isOrganizationRole()) {
            throw new InvalidRoleAssignmentException("Cannot assign non-organization role to organization: " + role);
        }

        Organization org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new OrganizationNotFoundException("Organization not found: " + organizationId));

        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new UserProfileNotFoundException("Target user not found: " + targetUserId));

        User assignedByUser = userRepository.findById(assignedByUserId)
                .orElseThrow(() -> new UserProfileNotFoundException("Assigned-by user not found: " + assignedByUserId));

        // Enforce active organization membership requirement for target user
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId)
                .orElseThrow(() -> new InvalidRoleAssignmentException(
                        "User [" + targetUserId + "] is not a member of organization [" + organizationId + "]"));

        if (member.getStatus() != OrganizationMembershipStatus.ACTIVE) {
            throw new InvalidRoleAssignmentException(
                    "User [" + targetUserId + "] membership status is [" + member.getStatus() + "]; must be ACTIVE to receive roles");
        }

        // ── Authorization Checks for assignedByUserId (ADR-001 §2.1, Stage 4.1 §4) ─
        Set<Role> callerRoles = getActiveOrganizationRoles(organizationId, assignedByUserId);

        boolean isCallerOwner = callerRoles.contains(Role.ORGANIZATION_OWNER);
        boolean isCallerAdmin = callerRoles.contains(Role.ORGANIZATION_ADMIN);

        if (!isCallerOwner && !isCallerAdmin) {
            throw new RoleAssignmentAccessDeniedException(
                    "User [" + assignedByUserId + "] is not authorized to assign roles in organization [" + organizationId + "]. Requires ORGANIZATION_OWNER or ORGANIZATION_ADMIN.");
        }

        if (role == Role.ORGANIZATION_BILLING && !isCallerOwner) {
            throw new RoleAssignmentAccessDeniedException(
                    "Only ORGANIZATION_OWNER can grant ORGANIZATION_BILLING role. User [" + assignedByUserId + "] is not an owner.");
        }

        if (role == Role.ORGANIZATION_OWNER && !isCallerOwner) {
            throw new RoleAssignmentAccessDeniedException(
                    "Only ORGANIZATION_OWNER can grant ORGANIZATION_OWNER role. User [" + assignedByUserId + "] is not an owner.");
        }

        RoleEntity roleEntity = roleRepository.findByName(role.name())
                .orElseThrow(() -> new IllegalStateException("Canonical role not found in catalog: " + role.name()));

        Instant now = Instant.now();
        if (orgAssignmentRepository.hasActiveAssignment(organizationId, targetUserId, roleEntity.getId(), now)) {
            throw new RoleAssignmentAlreadyExistsException(
                    "User [" + targetUserId + "] already holds an active [" + role.name() + "] assignment in organization [" + organizationId + "]");
        }

        // Supersede any expired unrevoked assignments for this role to satisfy unique index uk_org_role_assignments_active
        List<OrganizationRoleAssignment> expiredAssignments = orgAssignmentRepository.findExpiredUnrevoked(
                organizationId, targetUserId, roleEntity.getId(), now);
        for (OrganizationRoleAssignment expired : expiredAssignments) {
            expired.revoke(assignedByUser, "EXPIRED_SUPERSEDED", now);
            orgAssignmentRepository.save(expired);
        }

        OrganizationRoleAssignment assignment = OrganizationRoleAssignment.builder()
                .organization(org)
                .user(targetUser)
                .role(roleEntity)
                .roleScope(role.getScope())
                .assignedBy(assignedByUser)
                .assignedAt(now)
                .expiresAt(expiresAt)
                .build();

        OrganizationRoleAssignment saved = orgAssignmentRepository.save(assignment);
        if (cacheService != null) {
            cacheService.evictUser(organizationId, targetUserId);
        }
        log.info("Assigned role [{}] to user [{}] in organization [{}] by [{}]", role.name(), targetUserId, organizationId, assignedByUserId);
        return saved;
    }

    /**
     * Revokes an active organization-scoped role assignment.
     *
     * @param organizationId tenant organization UUID
     * @param targetUserId user whose role is being revoked
     * @param role canonical organization role
     * @param revokedByUserId user executing the revocation
     * @param reason business reason
     * @return the updated assignment
     */
    @Transactional
    public OrganizationRoleAssignment revokeOrganizationRole(
            UUID organizationId,
            UUID targetUserId,
            Role role,
            UUID revokedByUserId,
            String reason
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(revokedByUserId, "revokedByUserId must not be null");

        User revokedByUser = userRepository.findById(revokedByUserId)
                .orElseThrow(() -> new UserProfileNotFoundException("Revoked-by user not found: " + revokedByUserId));

        // ── Authorization Checks for revokedByUserId (ADR-001 §2.1, Stage 4.1 §4) ─
        Set<Role> callerRoles = getActiveOrganizationRoles(organizationId, revokedByUserId);

        boolean isCallerOwner = callerRoles.contains(Role.ORGANIZATION_OWNER);
        boolean isCallerAdmin = callerRoles.contains(Role.ORGANIZATION_ADMIN);

        if (!isCallerOwner && !isCallerAdmin) {
            throw new RoleAssignmentAccessDeniedException(
                    "User [" + revokedByUserId + "] is not authorized to revoke roles in organization [" + organizationId + "]. Requires ORGANIZATION_OWNER or ORGANIZATION_ADMIN.");
        }

        if (role == Role.ORGANIZATION_BILLING && !isCallerOwner) {
            throw new RoleAssignmentAccessDeniedException(
                    "Only ORGANIZATION_OWNER can revoke ORGANIZATION_BILLING role. User [" + revokedByUserId + "] is not an owner.");
        }

        if (role == Role.ORGANIZATION_OWNER) {
            if (!isCallerOwner) {
                throw new RoleAssignmentAccessDeniedException(
                        "Only ORGANIZATION_OWNER can revoke ORGANIZATION_OWNER role. User [" + revokedByUserId + "] is not an owner.");
            }
            Instant now = Instant.now();
            long activeOwners = orgAssignmentRepository.countActiveOwners(organizationId, now);
            if (activeOwners <= 1) {
                throw new RoleAssignmentAccessDeniedException(
                        "Cannot revoke sole active ORGANIZATION_OWNER from organization [" + organizationId + "]");
            }
        }

        RoleEntity roleEntity = roleRepository.findByName(role.name())
                .orElseThrow(() -> new IllegalStateException("Canonical role not found in catalog: " + role.name()));

        Instant now = Instant.now();
        OrganizationRoleAssignment assignment = orgAssignmentRepository
                .findActiveByOrganizationIdAndUserIdAndRoleId(organizationId, targetUserId, roleEntity.getId(), now)
                .orElseThrow(() -> new RoleAssignmentNotFoundException(
                        "No active assignment of role [" + role.name() + "] found for user [" + targetUserId + "] in organization [" + organizationId + "]"));

        assignment.revoke(revokedByUser, reason, now);
        OrganizationRoleAssignment updated = orgAssignmentRepository.save(assignment);
        if (cacheService != null) {
            cacheService.evictUser(organizationId, targetUserId);
        }
        log.info("Revoked role [{}] from user [{}] in organization [{}] by [{}] (reason: {})", role.name(), targetUserId, organizationId, revokedByUserId, reason);
        return updated;
    }

    /**
     * Retrieves all active canonical roles held by a user in an organization.
     *
     * @param organizationId tenant organization UUID
     * @param userId user UUID
     * @return set of active roles
     */
    @Transactional(readOnly = true)
    public Set<Role> getActiveOrganizationRoles(UUID organizationId, UUID userId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        // Verify active organization membership
        Optional<OrganizationMember> memberOpt = organizationMemberRepository
                .findByOrganizationIdAndUserId(organizationId, userId);

        if (memberOpt.isEmpty() || memberOpt.get().getStatus() != OrganizationMembershipStatus.ACTIVE) {
            return Collections.emptySet();
        }

        List<OrganizationRoleAssignment> active = orgAssignmentRepository.findActiveByOrganizationIdAndUserId(
                organizationId, userId, Instant.now());

        Set<Role> roles = active.stream()
                .map(a -> a.getRole().toDomainRole())
                .collect(Collectors.toSet());

        // Backward compatibility: legacy membership role fallback strictly for unmigrated members
        if (roles.isEmpty() && memberOpt.get().getRole() != null) {
            boolean hasTransitioned = orgAssignmentRepository.hasAnyAssignments(organizationId, userId);
            if (!hasTransitioned) {
                roles.add(DatabaseRoleResolver.mapLegacyOrganizationRole(memberOpt.get().getRole()));
            }
        }

        return Collections.unmodifiableSet(roles);
    }

    /**
     * Lists organization-scoped role assignments.
     *
     * <p>Requires the caller to hold {@link Role#ORGANIZATION_OWNER} or {@link Role#ORGANIZATION_ADMIN}.
     *
     * @param organizationId tenant organization UUID
     * @param callerUserId user making the request
     * @param activeOnly whether to filter only active assignments
     * @return list of assignments
     */
    @Transactional(readOnly = true)
    public List<OrganizationRoleAssignment> listOrganizationRoleAssignments(
            UUID organizationId,
            UUID callerUserId,
            boolean activeOnly
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        Set<Role> callerRoles = getActiveOrganizationRoles(organizationId, callerUserId);
        if (!callerRoles.contains(Role.ORGANIZATION_OWNER) && !callerRoles.contains(Role.ORGANIZATION_ADMIN)) {
            throw new RoleAssignmentAccessDeniedException(
                    "User [" + callerUserId + "] is not authorized to list roles in organization [" + organizationId + "]. Requires ORGANIZATION_OWNER or ORGANIZATION_ADMIN.");
        }

        if (activeOnly) {
            return orgAssignmentRepository.findActiveByOrganizationId(organizationId, Instant.now());
        } else {
            return orgAssignmentRepository.findByOrganizationId(organizationId);
        }
    }

    /**
     * Revokes an organization role assignment by its assignment UUID.
     *
     * @param organizationId tenant organization UUID
     * @param assignmentId assignment UUID to revoke
     * @param revokedByUserId user executing the revocation
     * @param reason business reason
     * @return updated assignment
     */
    @Transactional
    public OrganizationRoleAssignment revokeOrganizationRoleAssignment(
            UUID organizationId,
            UUID assignmentId,
            UUID revokedByUserId,
            String reason
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(revokedByUserId, "revokedByUserId must not be null");

        OrganizationRoleAssignment assignment = orgAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new RoleAssignmentNotFoundException(
                        "Role assignment not found with ID: " + assignmentId));

        if (!assignment.getOrganization().getId().equals(organizationId)) {
            throw new RoleAssignmentNotFoundException(
                    "Role assignment [" + assignmentId + "] does not belong to organization [" + organizationId + "]");
        }

        return revokeOrganizationRole(
                organizationId,
                assignment.getUser().getId(),
                assignment.getRole().toDomainRole(),
                revokedByUserId,
                reason != null && !reason.isBlank() ? reason : "ADMIN_REVOCATION"
        );
    }

    // ── Custom Role Assignments (Stage 7) ─────────────────────────────────────

    /**
     * Assigns an organization-scoped custom role to an active organization member.
     *
     * @param organizationId tenant organization UUID
     * @param targetUserId user receiving the custom role
     * @param customRoleId custom role UUID
     * @param assignedByUserId user performing the assignment
     * @param expiresAt optional expiration timestamp
     * @return the persisted {@link CustomRoleAssignment}
     */
    @Transactional
    public CustomRoleAssignment assignCustomRole(
            UUID organizationId,
            UUID targetUserId,
            UUID customRoleId,
            UUID assignedByUserId,
            Instant expiresAt
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Objects.requireNonNull(customRoleId, "customRoleId must not be null");
        Objects.requireNonNull(assignedByUserId, "assignedByUserId must not be null");

        Organization org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new OrganizationNotFoundException("Organization not found: " + organizationId));

        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new UserProfileNotFoundException("Target user not found: " + targetUserId));

        User assignedByUser = userRepository.findById(assignedByUserId)
                .orElseThrow(() -> new UserProfileNotFoundException("Assigned-by user not found: " + assignedByUserId));

        // Enforce active organization membership requirement for target user
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId)
                .orElseThrow(() -> new InvalidRoleAssignmentException(
                        "User [" + targetUserId + "] is not a member of organization [" + organizationId + "]"));

        if (member.getStatus() != OrganizationMembershipStatus.ACTIVE) {
            throw new InvalidRoleAssignmentException(
                    "User [" + targetUserId + "] membership status is [" + member.getStatus() + "]; must be ACTIVE to receive roles");
        }

        // Caller authorization check: requires OWNER or ADMIN
        Set<Role> callerRoles = getActiveOrganizationRoles(organizationId, assignedByUserId);
        boolean isCallerOwner = callerRoles.contains(Role.ORGANIZATION_OWNER);
        boolean isCallerAdmin = callerRoles.contains(Role.ORGANIZATION_ADMIN);

        if (!isCallerOwner && !isCallerAdmin) {
            throw new RoleAssignmentAccessDeniedException(
                    "User [" + assignedByUserId + "] is not authorized to assign custom roles in organization [" + organizationId + "]. Requires ORGANIZATION_OWNER or ORGANIZATION_ADMIN.");
        }

        // Custom role lookup and tenant isolation
        if (customRoleRepository == null) {
            throw new IllegalStateException("CustomRoleRepository is not available");
        }

        CustomRoleEntity customRole = customRoleRepository.findByIdAndOrganizationIdWithPermissions(customRoleId, organizationId)
                .orElseThrow(() -> new InvalidRoleAssignmentException(
                        "Custom role [" + customRoleId + "] not found in organization [" + organizationId + "]"));

        // Active state check: disabled custom roles cannot be assigned
        if (!customRole.isActive()) {
            throw new InvalidRoleAssignmentException(
                    "Cannot assign disabled custom role [" + customRole.getName() + "] in organization [" + organizationId + "]");
        }

        // Delegation authority check: caller must hold all permissions that this custom role grants
        if (permissionGovernanceService != null && !isCallerOwner) {
            Set<Permission> rolePermissions = customRole.getRolePermissions().stream()
                    .filter(rp -> rp.getPermission() != null)
                    .map(rp -> rp.getPermission().toDomainPermission())
                    .collect(Collectors.toSet());
            permissionGovernanceService.validateDelegationAuthority(organizationId, assignedByUserId, rolePermissions);
        }

        if (customRoleAssignmentRepository == null) {
            throw new IllegalStateException("CustomRoleAssignmentRepository is not available");
        }

        Instant now = Instant.now();
        if (customRoleAssignmentRepository.hasActiveAssignment(organizationId, targetUserId, customRoleId, now)) {
            throw new RoleAssignmentAlreadyExistsException(
                    "User [" + targetUserId + "] already holds an active assignment for custom role [" + customRole.getName() + "] in organization [" + organizationId + "]");
        }

        // Supersede expired unrevoked assignments for this custom role
        List<CustomRoleAssignment> expiredAssignments = customRoleAssignmentRepository.findExpiredUnrevoked(
                organizationId, targetUserId, customRoleId, now);
        for (CustomRoleAssignment expired : expiredAssignments) {
            expired.revoke(assignedByUser, "EXPIRED_SUPERSEDED", now);
            customRoleAssignmentRepository.save(expired);
        }

        CustomRoleAssignment assignment = CustomRoleAssignment.builder()
                .organization(org)
                .user(targetUser)
                .customRole(customRole)
                .assignedBy(assignedByUser)
                .assignedAt(now)
                .expiresAt(expiresAt)
                .build();

        CustomRoleAssignment saved = customRoleAssignmentRepository.save(assignment);
        if (cacheService != null) {
            cacheService.evictUser(organizationId, targetUserId);
        }
        log.info("Assigned custom role [{}] ({}) to user [{}] in organization [{}] by [{}]",
                customRole.getName(), customRoleId, targetUserId, organizationId, assignedByUserId);
        return saved;
    }

    /**
     * Revokes an active custom role assignment by assignment UUID.
     */
    @Transactional
    public CustomRoleAssignment revokeCustomRoleAssignment(
            UUID organizationId,
            UUID assignmentId,
            UUID revokedByUserId,
            String reason
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(revokedByUserId, "revokedByUserId must not be null");

        User revokedByUser = userRepository.findById(revokedByUserId)
                .orElseThrow(() -> new UserProfileNotFoundException("Revoked-by user not found: " + revokedByUserId));

        Set<Role> callerRoles = getActiveOrganizationRoles(organizationId, revokedByUserId);
        if (!callerRoles.contains(Role.ORGANIZATION_OWNER) && !callerRoles.contains(Role.ORGANIZATION_ADMIN)) {
            throw new RoleAssignmentAccessDeniedException(
                    "User [" + revokedByUserId + "] is not authorized to revoke roles in organization [" + organizationId + "]. Requires ORGANIZATION_OWNER or ORGANIZATION_ADMIN.");
        }

        if (customRoleAssignmentRepository == null) {
            throw new IllegalStateException("CustomRoleAssignmentRepository is not available");
        }

        CustomRoleAssignment assignment = customRoleAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new RoleAssignmentNotFoundException("Custom role assignment not found with ID: " + assignmentId));

        if (!assignment.getOrganization().getId().equals(organizationId)) {
            throw new RoleAssignmentNotFoundException("Custom role assignment [" + assignmentId + "] does not belong to organization [" + organizationId + "]");
        }

        Instant now = Instant.now();
        assignment.revoke(revokedByUser, reason != null && !reason.isBlank() ? reason : "ADMIN_REVOCATION", now);
        CustomRoleAssignment updated = customRoleAssignmentRepository.save(assignment);
        if (cacheService != null) {
            cacheService.evictUser(organizationId, assignment.getUser().getId());
        }
        log.info("Revoked custom role assignment [{}] for user [{}] in organization [{}] by [{}]",
                assignmentId, assignment.getUser().getId(), organizationId, revokedByUserId);
        return updated;
    }

    /**
     * Lists custom role assignments for an organization.
     */
    @Transactional(readOnly = true)
    public List<CustomRoleAssignment> listCustomRoleAssignments(
            UUID organizationId,
            UUID callerUserId,
            boolean activeOnly
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        Set<Role> callerRoles = getActiveOrganizationRoles(organizationId, callerUserId);
        if (!callerRoles.contains(Role.ORGANIZATION_OWNER) && !callerRoles.contains(Role.ORGANIZATION_ADMIN)) {
            throw new RoleAssignmentAccessDeniedException(
                    "User [" + callerUserId + "] is not authorized to list roles in organization [" + organizationId + "]. Requires ORGANIZATION_OWNER or ORGANIZATION_ADMIN.");
        }

        if (customRoleAssignmentRepository == null) {
            return Collections.emptyList();
        }

        if (activeOnly) {
            return customRoleAssignmentRepository.findActiveByOrganizationId(organizationId, Instant.now());
        } else {
            return customRoleAssignmentRepository.findAllWithDetailsByOrganizationId(organizationId);
        }
    }

    /**
     * Resolves a custom role by its programmatic name or UUID string within an organization.
     */
    @Transactional(readOnly = true)
    public Optional<CustomRoleEntity> findCustomRoleByNameOrId(UUID organizationId, String roleIdentifier) {
        if (customRoleRepository == null || roleIdentifier == null || roleIdentifier.isBlank()) {
            return Optional.empty();
        }
        String trimmed = roleIdentifier.trim();
        try {
            UUID roleId = UUID.fromString(trimmed);
            Optional<CustomRoleEntity> byId = customRoleRepository.findByIdAndOrganizationIdWithPermissions(roleId, organizationId);
            if (byId.isPresent()) {
                return byId;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return customRoleRepository.findByOrganizationIdAndName(organizationId, trimmed.toUpperCase(Locale.ROOT));
    }

    // ── Project Role Assignments ──────────────────────────────────────────────

    /**
     * Assigns a project-scoped canonical role to a user.
     *
     * @param organizationId tenant organization UUID
     * @param projectId target project UUID
     * @param targetUserId user receiving the project role
     * @param role canonical project role
     * @param assignedByUserId user performing the assignment
     * @param expiresAt optional expiration timestamp
     * @return the persisted {@link ProjectRoleAssignment}
     */
    @Transactional
    public ProjectRoleAssignment assignProjectRole(
            UUID organizationId,
            UUID projectId,
            UUID targetUserId,
            Role role,
            UUID assignedByUserId,
            Instant expiresAt
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(assignedByUserId, "assignedByUserId must not be null");

        if (!role.isProjectRole()) {
            throw new InvalidRoleAssignmentException("Cannot assign non-project role to project: " + role);
        }

        // Tenant boundary check: Project must belong to the organization (if validator present)
        if (tenantBoundaryValidator != null && !tenantBoundaryValidator.isProjectInOrganization(organizationId, projectId)) {
            throw new InvalidRoleAssignmentException(
                    "Tenant boundary violation: Project [" + projectId + "] does not belong to organization [" + organizationId + "]");
        }

        Organization org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new OrganizationNotFoundException("Organization not found: " + organizationId));

        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new UserProfileNotFoundException("Target user not found: " + targetUserId));

        User assignedByUser = userRepository.findById(assignedByUserId)
                .orElseThrow(() -> new UserProfileNotFoundException("Assigned-by user not found: " + assignedByUserId));

        // Invariant: Project role requires active organization membership
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId)
                .orElseThrow(() -> new InvalidRoleAssignmentException(
                        "Cannot assign project role: user [" + targetUserId + "] is not a member of organization [" + organizationId + "]"));

        if (member.getStatus() != OrganizationMembershipStatus.ACTIVE) {
            throw new InvalidRoleAssignmentException(
                    "Cannot assign project role: user [" + targetUserId + "] membership is [" + member.getStatus() + "] (ACTIVE required)");
        }

        // ── Authorization Checks for assignedByUserId ────────────────────────────
        Set<Role> callerOrgRoles = getActiveOrganizationRoles(organizationId, assignedByUserId);
        boolean isOrgAdmin = callerOrgRoles.contains(Role.ORGANIZATION_OWNER) || callerOrgRoles.contains(Role.ORGANIZATION_ADMIN);
        boolean isProjectLead = isOrgAdmin || getActiveProjectRoles(organizationId, projectId, assignedByUserId).contains(Role.PROJECT_LEAD);

        if (!isProjectLead) {
            throw new RoleAssignmentAccessDeniedException(
                    "User [" + assignedByUserId + "] is not authorized to assign project roles in project [" + projectId + "]. Requires ORGANIZATION_OWNER, ORGANIZATION_ADMIN, or PROJECT_LEAD.");
        }

        RoleEntity roleEntity = roleRepository.findByName(role.name())
                .orElseThrow(() -> new IllegalStateException("Canonical role not found in catalog: " + role.name()));

        Instant now = Instant.now();
        if (projectAssignmentRepository.findActiveByProjectIdAndUserIdAndRoleId(projectId, targetUserId, roleEntity.getId(), now).isPresent()) {
            throw new RoleAssignmentAlreadyExistsException(
                    "User [" + targetUserId + "] already holds an active [" + role.name() + "] assignment in project [" + projectId + "]");
        }

        // Supersede any expired unrevoked assignments for this role in the project
        List<ProjectRoleAssignment> expiredAssignments = projectAssignmentRepository.findExpiredUnrevoked(
                projectId, targetUserId, roleEntity.getId(), now);
        for (ProjectRoleAssignment expired : expiredAssignments) {
            expired.revoke(assignedByUser, "EXPIRED_SUPERSEDED", now);
            projectAssignmentRepository.save(expired);
        }

        ProjectRoleAssignment assignment = ProjectRoleAssignment.builder()
                .projectId(projectId)
                .organization(org)
                .user(targetUser)
                .role(roleEntity)
                .roleScope(role.getScope())
                .assignedBy(assignedByUser)
                .assignedAt(now)
                .expiresAt(expiresAt)
                .build();

        ProjectRoleAssignment saved = projectAssignmentRepository.save(assignment);
        if (cacheService != null) {
            cacheService.evictUserProject(organizationId, projectId, targetUserId);
        }
        log.info("Assigned project role [{}] to user [{}] in project [{}] (org: [{}]) by [{}]",
                role.name(), targetUserId, projectId, organizationId, assignedByUserId);
        return saved;
    }

    /**
     * Revokes an active project-scoped role assignment.
     *
     * @param organizationId tenant organization UUID
     * @param projectId target project UUID
     * @param targetUserId user whose role is being revoked
     * @param role canonical project role
     * @param revokedByUserId user executing revocation
     * @param reason business reason
     * @return the updated assignment
     */
    @Transactional
    public ProjectRoleAssignment revokeProjectRole(
            UUID organizationId,
            UUID projectId,
            UUID targetUserId,
            Role role,
            UUID revokedByUserId,
            String reason
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(revokedByUserId, "revokedByUserId must not be null");

        // Tenant boundary check: Project must belong to the organization (if validator present)
        if (tenantBoundaryValidator != null && !tenantBoundaryValidator.isProjectInOrganization(organizationId, projectId)) {
            throw new InvalidRoleAssignmentException(
                    "Tenant boundary violation: Project [" + projectId + "] does not belong to organization [" + organizationId + "]");
        }

        User revokedByUser = userRepository.findById(revokedByUserId)
                .orElseThrow(() -> new UserProfileNotFoundException("Revoked-by user not found: " + revokedByUserId));

        // ── Authorization Checks for revokedByUserId ─────────────────────────────
        Set<Role> callerOrgRoles = getActiveOrganizationRoles(organizationId, revokedByUserId);
        boolean isOrgAdmin = callerOrgRoles.contains(Role.ORGANIZATION_OWNER) || callerOrgRoles.contains(Role.ORGANIZATION_ADMIN);
        boolean isProjectLead = isOrgAdmin || getActiveProjectRoles(organizationId, projectId, revokedByUserId).contains(Role.PROJECT_LEAD);

        if (!isProjectLead) {
            throw new RoleAssignmentAccessDeniedException(
                    "User [" + revokedByUserId + "] is not authorized to revoke project roles in project [" + projectId + "]. Requires ORGANIZATION_OWNER, ORGANIZATION_ADMIN, or PROJECT_LEAD.");
        }

        RoleEntity roleEntity = roleRepository.findByName(role.name())
                .orElseThrow(() -> new IllegalStateException("Canonical role not found in catalog: " + role.name()));

        Instant now = Instant.now();
        ProjectRoleAssignment assignment = projectAssignmentRepository
                .findActiveByProjectIdAndUserIdAndRoleId(projectId, targetUserId, roleEntity.getId(), now)
                .orElseThrow(() -> new RoleAssignmentNotFoundException(
                        "No active assignment of role [" + role.name() + "] found for user [" + targetUserId + "] in project [" + projectId + "]"));

        assignment.revoke(revokedByUser, reason, now);
        ProjectRoleAssignment updated = projectAssignmentRepository.save(assignment);
        if (cacheService != null) {
            cacheService.evictUserProject(organizationId, projectId, targetUserId);
        }
        log.info("Revoked project role [{}] from user [{}] in project [{}] by [{}] (reason: {})",
                role.name(), targetUserId, projectId, revokedByUserId, reason);
        return updated;
    }

    /**
     * Lists project-scoped role assignments within an organization and project.
     *
     * @param organizationId tenant organization UUID
     * @param projectId target project UUID
     * @param callerUserId user making the request
     * @param activeOnly whether to filter only active assignments
     * @return list of assignments
     */
    @Transactional(readOnly = true)
    public List<ProjectRoleAssignment> listProjectRoleAssignments(
            UUID organizationId,
            UUID projectId,
            UUID callerUserId,
            boolean activeOnly
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        // Tenant boundary check if validator present
        if (tenantBoundaryValidator != null && !tenantBoundaryValidator.isProjectInOrganization(organizationId, projectId)) {
            throw new InvalidRoleAssignmentException(
                    "Tenant boundary violation: Project [" + projectId + "] does not belong to organization [" + organizationId + "]");
        }

        // Verify caller authorization: org OWNER/ADMIN or project LEAD or active project member
        Set<Role> callerOrgRoles = getActiveOrganizationRoles(organizationId, callerUserId);
        boolean isOrgAdmin = callerOrgRoles.contains(Role.ORGANIZATION_OWNER) || callerOrgRoles.contains(Role.ORGANIZATION_ADMIN);
        boolean isProjectMember = isOrgAdmin || !getActiveProjectRoles(organizationId, projectId, callerUserId).isEmpty();

        if (!isProjectMember) {
            throw new RoleAssignmentAccessDeniedException(
                    "User [" + callerUserId + "] is not authorized to list project roles in project [" + projectId + "]");
        }

        if (activeOnly) {
            return projectAssignmentRepository.findActiveByOrganizationIdAndProjectId(organizationId, projectId, Instant.now());
        } else {
            return projectAssignmentRepository.findByOrganizationIdAndProjectId(organizationId, projectId);
        }
    }

    /**
     * Revokes a project role assignment by its assignment UUID.
     *
     * @param organizationId tenant organization UUID
     * @param projectId target project UUID
     * @param assignmentId assignment UUID to revoke
     * @param revokedByUserId user executing the revocation
     * @param reason business reason
     * @return updated assignment
     */
    @Transactional
    public ProjectRoleAssignment revokeProjectRoleAssignment(
            UUID organizationId,
            UUID projectId,
            UUID assignmentId,
            UUID revokedByUserId,
            String reason
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(revokedByUserId, "revokedByUserId must not be null");

        ProjectRoleAssignment assignment = projectAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new RoleAssignmentNotFoundException(
                        "Project role assignment not found with ID: " + assignmentId));

        if (!assignment.getOrganization().getId().equals(organizationId) || !assignment.getProjectId().equals(projectId)) {
            throw new RoleAssignmentNotFoundException(
                    "Project role assignment [" + assignmentId + "] does not belong to organization [" + organizationId + "] and project [" + projectId + "]");
        }

        return revokeProjectRole(
                organizationId,
                projectId,
                assignment.getUser().getId(),
                assignment.getRole().toDomainRole(),
                revokedByUserId,
                reason != null && !reason.isBlank() ? reason : "ADMIN_REVOCATION"
        );
    }

    /**
     * Retrieves all active canonical roles held by a user in a specific project.
     *
     * @param organizationId tenant organization UUID
     * @param projectId project UUID
     * @param userId user UUID
     * @return set of active roles
     */
    @Transactional(readOnly = true)
    public Set<Role> getActiveProjectRoles(UUID organizationId, UUID projectId, UUID userId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        // Verify active organization membership
        boolean isMemberActive = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, userId)
                .map(m -> m.getStatus() == OrganizationMembershipStatus.ACTIVE)
                .orElse(false);

        if (!isMemberActive) {
            return Collections.emptySet();
        }

        List<ProjectRoleAssignment> active = projectAssignmentRepository.findActiveByOrganizationIdAndProjectIdAndUserId(
                organizationId, projectId, userId, Instant.now());

        return active.stream()
                .map(a -> a.getRole().toDomainRole())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Maintenance operation to mark any stale expired assignments as revoked.
     *
     * @param now reference instant
     * @return count of newly archived assignments
     */
    @Transactional
    public int expireStaleAssignments(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        List<OrganizationRoleAssignment> staleOrg = orgAssignmentRepository.findStaleUnrevoked(now);
        for (OrganizationRoleAssignment a : staleOrg) {
            a.revoke(a.getAssignedBy(), "EXPIRED", now);
            orgAssignmentRepository.save(a);
        }

        List<ProjectRoleAssignment> staleProj = projectAssignmentRepository.findStaleUnrevoked(now);
        for (ProjectRoleAssignment a : staleProj) {
            a.revoke(a.getAssignedBy(), "EXPIRED", now);
            projectAssignmentRepository.save(a);
        }

        return staleOrg.size() + staleProj.size();
    }
}
