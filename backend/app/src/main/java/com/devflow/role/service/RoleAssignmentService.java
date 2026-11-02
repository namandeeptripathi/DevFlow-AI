package com.devflow.role.service;

import com.devflow.modules.auth.domain.Role;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.exception.OrganizationNotFoundException;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.role.domain.OrganizationRoleAssignment;
import com.devflow.role.domain.ProjectRoleAssignment;
import com.devflow.role.domain.RoleEntity;
import com.devflow.role.exception.InvalidRoleAssignmentException;
import com.devflow.role.exception.RoleAssignmentAlreadyExistsException;
import com.devflow.role.exception.RoleAssignmentNotFoundException;
import com.devflow.role.repository.OrganizationRoleAssignmentRepository;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Application service managing the lifecycle of scoped role assignments (Stage 4).
 *
 * <p>Enforces core security invariants:
 * <ul>
 *   <li>Scope boundaries: Organization roles cannot be assigned to projects, and project roles cannot be assigned to organizations.</li>
 *   <li>Tenant isolation: Project roles require verified active organization membership in the enclosing tenant.</li>
 *   <li>Active uniqueness: Prevents duplicate active assignments for the same principal and scope.</li>
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

    @Autowired
    public RoleAssignmentService(
            OrganizationRoleAssignmentRepository orgAssignmentRepository,
            ProjectRoleAssignmentRepository projectAssignmentRepository,
            RoleRepository roleRepository,
            OrganizationRepository organizationRepository,
            OrganizationMemberRepository organizationMemberRepository,
            UserRepository userRepository
    ) {
        this.orgAssignmentRepository = Objects.requireNonNull(orgAssignmentRepository, "orgAssignmentRepository must not be null");
        this.projectAssignmentRepository = Objects.requireNonNull(projectAssignmentRepository, "projectAssignmentRepository must not be null");
        this.roleRepository = Objects.requireNonNull(roleRepository, "roleRepository must not be null");
        this.organizationRepository = Objects.requireNonNull(organizationRepository, "organizationRepository must not be null");
        this.organizationMemberRepository = Objects.requireNonNull(organizationMemberRepository, "organizationMemberRepository must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
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

        // Enforce active organization membership requirement
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId)
                .orElseThrow(() -> new InvalidRoleAssignmentException(
                        "User [" + targetUserId + "] is not a member of organization [" + organizationId + "]"));

        if (member.getStatus() != OrganizationMembershipStatus.ACTIVE) {
            throw new InvalidRoleAssignmentException(
                    "User [" + targetUserId + "] membership status is [" + member.getStatus() + "]; must be ACTIVE to receive roles");
        }

        RoleEntity roleEntity = roleRepository.findByName(role.name())
                .orElseThrow(() -> new IllegalStateException("Canonical role not found in catalog: " + role.name()));

        Instant now = Instant.now();
        if (orgAssignmentRepository.hasActiveAssignment(organizationId, targetUserId, roleEntity.getId(), now)) {
            throw new RoleAssignmentAlreadyExistsException(
                    "User [" + targetUserId + "] already holds an active [" + role.name() + "] assignment in organization [" + organizationId + "]");
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

        RoleEntity roleEntity = roleRepository.findByName(role.name())
                .orElseThrow(() -> new IllegalStateException("Canonical role not found in catalog: " + role.name()));

        Instant now = Instant.now();
        OrganizationRoleAssignment assignment = orgAssignmentRepository
                .findActiveByOrganizationIdAndUserIdAndRoleId(organizationId, targetUserId, roleEntity.getId(), now)
                .orElseThrow(() -> new RoleAssignmentNotFoundException(
                        "No active assignment of role [" + role.name() + "] found for user [" + targetUserId + "] in organization [" + organizationId + "]"));

        assignment.revoke(revokedByUser, reason, now);
        OrganizationRoleAssignment updated = orgAssignmentRepository.save(assignment);
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
        boolean isMemberActive = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, userId)
                .map(m -> m.getStatus() == OrganizationMembershipStatus.ACTIVE)
                .orElse(false);

        if (!isMemberActive) {
            return Collections.emptySet();
        }

        List<OrganizationRoleAssignment> active = orgAssignmentRepository.findActiveByOrganizationIdAndUserId(
                organizationId, userId, Instant.now());

        return active.stream()
                .map(a -> a.getRole().toDomainRole())
                .collect(Collectors.toUnmodifiableSet());
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

        RoleEntity roleEntity = roleRepository.findByName(role.name())
                .orElseThrow(() -> new IllegalStateException("Canonical role not found in catalog: " + role.name()));

        Instant now = Instant.now();
        if (projectAssignmentRepository.findActiveByProjectIdAndUserIdAndRoleId(projectId, targetUserId, roleEntity.getId(), now).isPresent()) {
            throw new RoleAssignmentAlreadyExistsException(
                    "User [" + targetUserId + "] already holds an active [" + role.name() + "] assignment in project [" + projectId + "]");
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

        User revokedByUser = userRepository.findById(revokedByUserId)
                .orElseThrow(() -> new UserProfileNotFoundException("Revoked-by user not found: " + revokedByUserId));

        RoleEntity roleEntity = roleRepository.findByName(role.name())
                .orElseThrow(() -> new IllegalStateException("Canonical role not found in catalog: " + role.name()));

        Instant now = Instant.now();
        ProjectRoleAssignment assignment = projectAssignmentRepository
                .findActiveByProjectIdAndUserIdAndRoleId(projectId, targetUserId, roleEntity.getId(), now)
                .orElseThrow(() -> new RoleAssignmentNotFoundException(
                        "No active assignment of role [" + role.name() + "] found for user [" + targetUserId + "] in project [" + projectId + "]"));

        assignment.revoke(revokedByUser, reason, now);
        ProjectRoleAssignment updated = projectAssignmentRepository.save(assignment);
        log.info("Revoked project role [{}] from user [{}] in project [{}] by [{}] (reason: {})",
                role.name(), targetUserId, projectId, revokedByUserId, reason);
        return updated;
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
}
