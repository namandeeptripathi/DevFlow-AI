package com.devflow.organization.service;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.exception.LastOrganizationOwnerRemovalException;
import com.devflow.organization.exception.OrganizationMemberAlreadyExistsException;
import com.devflow.organization.exception.OrganizationMemberNotFoundException;
import com.devflow.organization.exception.OrganizationNotFoundException;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain service managing user memberships within DevFlow organizations (tenants).
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Add users to an organization with a specific {@link OrganizationRole} (admin/owner-gated).</li>
 *   <li>Remove users from an organization while preserving at least one active {@link OrganizationRole#OWNER} (admin/owner-gated).</li>
 *   <li>List all active members belonging to an organization (member-gated).</li>
 *   <li>Change a member's role while enforcing owner preservation rules (owner-gated).</li>
 * </ul>
 *
 * <h2>Architectural Boundaries</h2>
 * <ul>
 *   <li>Authorization is delegated to {@link OrganizationAuthorizationService}. This service does not inspect roles directly.</li>
 *   <li>Constructor injection only — no field injection.</li>
 *   <li>Command operations use {@link Transactional}, query operations use {@link Transactional#readOnly()}.</li>
 *   <li>Enforces multi-tenant member uniqueness and owner guardrails at the domain service level.</li>
 * </ul>
 *
 * @see OrganizationMember
 * @see OrganizationRole
 * @see OrganizationMembershipStatus
 * @see OrganizationMemberRepository
 * @see OrganizationAuthorizationService
 */
@Service
public class OrganizationMembershipService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationMembershipService.class);

    private final OrganizationMemberRepository organizationMemberRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final OrganizationAuthorizationService authorizationService;

    public OrganizationMembershipService(
            OrganizationMemberRepository organizationMemberRepository,
            OrganizationRepository organizationRepository,
            UserRepository userRepository,
            OrganizationAuthorizationService authorizationService
    ) {
        this.organizationMemberRepository = Objects.requireNonNull(
                organizationMemberRepository, "organizationMemberRepository must not be null");
        this.organizationRepository = Objects.requireNonNull(
                organizationRepository, "organizationRepository must not be null");
        this.userRepository = Objects.requireNonNull(
                userRepository, "userRepository must not be null");
        this.authorizationService = Objects.requireNonNull(
                authorizationService, "authorizationService must not be null");
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    /**
     * Adds a user to an organization with the specified role.
     *
     * <p>Requires caller to hold {@link OrganizationRole#ADMIN} or {@link OrganizationRole#OWNER} role.
     *
     * @param organizationId the UUID of the target organization
     * @param targetUserId the UUID of the user to add
     * @param role the initial role to assign
     * @param callerUserId the UUID of the authenticated caller
     * @return the saved {@link OrganizationMember} entity
     * @throws OrganizationNotFoundException if the organization or user does not exist
     * @throws OrganizationMemberAlreadyExistsException if the user is already a member of the organization
     * @throws com.devflow.organization.exception.OrganizationAccessDeniedException if the caller lacks admin/owner permissions
     */
    @Transactional
    public OrganizationMember addMember(UUID organizationId, UUID targetUserId, OrganizationRole role, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        authorizationService.requireAdminOrOwner(organizationId, callerUserId);

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> {
                    log.warn("Add member failed: organization [{}] not found", organizationId);
                    return new OrganizationNotFoundException("Organization not found: " + organizationId);
                });

        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> {
                    log.warn("Add member failed: user [{}] not found", targetUserId);
                    return new OrganizationNotFoundException("User not found: " + targetUserId);
                });

        if (organizationMemberRepository.existsByOrganizationIdAndUserId(organizationId, targetUserId)) {
            log.warn("Add member rejected: user [{}] already member of organization [{}]", targetUserId, organizationId);
            throw new OrganizationMemberAlreadyExistsException(
                    "User [" + targetUserId + "] is already a member of organization [" + organizationId + "]");
        }

        OrganizationMember member = OrganizationMember.builder()
                .organization(organization)
                .user(user)
                .role(role)
                .status(OrganizationMembershipStatus.ACTIVE)
                .joinedAt(Instant.now())
                .build();

        OrganizationMember saved = organizationMemberRepository.save(member);
        log.info("Added user [{}] to organization [{}] with role [{}] by caller [{}]", targetUserId, organizationId, role, callerUserId);
        return saved;
    }

    /**
     * Removes a user from an organization.
     *
     * <p>Requires caller to hold {@link OrganizationRole#ADMIN} or {@link OrganizationRole#OWNER} role.
     * Enforces that the last active {@link OrganizationRole#OWNER} cannot be removed.
     *
     * @param organizationId the UUID of the organization
     * @param targetUserId the UUID of the member user to remove
     * @param callerUserId the UUID of the authenticated caller
     * @throws OrganizationMemberNotFoundException if the user is not a member of the organization
     * @throws LastOrganizationOwnerRemovalException if removing the user would leave zero active owners
     * @throws com.devflow.organization.exception.OrganizationAccessDeniedException if the caller lacks admin/owner permissions
     */
    @Transactional
    public void removeMember(UUID organizationId, UUID targetUserId, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        authorizationService.requireAdminOrOwner(organizationId, callerUserId);

        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId)
                .orElseThrow(() -> {
                    log.warn("Remove member failed: member not found for user [{}] in organization [{}]", targetUserId, organizationId);
                    return new OrganizationMemberNotFoundException(
                            "Member not found for user [" + targetUserId + "] in organization [" + organizationId + "]");
                });

        if (member.getRole() == OrganizationRole.OWNER && member.getStatus() == OrganizationMembershipStatus.ACTIVE) {
            long activeOwnerCount = organizationMemberRepository.countByOrganizationIdAndRoleAndStatus(
                    organizationId, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE);
            if (activeOwnerCount <= 1) {
                log.warn("Remove member rejected: attempt to remove last active OWNER [{}] from organization [{}]",
                        targetUserId, organizationId);
                throw new LastOrganizationOwnerRemovalException(
                        "Cannot remove the last active OWNER from organization [" + organizationId + "]");
            }
        }

        organizationMemberRepository.delete(member);
        log.info("Removed user [{}] from organization [{}] by caller [{}]", targetUserId, organizationId, callerUserId);
    }

    /**
     * Changes an existing member's role within an organization.
     *
     * <p>Requires caller to hold {@link OrganizationRole#OWNER} role.
     * Enforces that demoting an {@link OrganizationRole#OWNER} to a non-owner role
     * requires at least one other active owner to remain in the organization.
     *
     * @param organizationId the UUID of the organization
     * @param targetUserId the UUID of the member user
     * @param newRole the new role to assign
     * @param callerUserId the UUID of the authenticated caller
     * @return the updated {@link OrganizationMember} entity
     * @throws OrganizationMemberNotFoundException if the user is not a member of the organization
     * @throws LastOrganizationOwnerRemovalException if demoting the owner would leave zero active owners
     * @throws com.devflow.organization.exception.OrganizationAccessDeniedException if the caller is not an owner
     */
    @Transactional
    public OrganizationMember changeMemberRole(UUID organizationId, UUID targetUserId, OrganizationRole newRole, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Objects.requireNonNull(newRole, "newRole must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        authorizationService.requireOwner(organizationId, callerUserId);

        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId)
                .orElseThrow(() -> {
                    log.warn("Role change failed: member not found for user [{}] in organization [{}]", targetUserId, organizationId);
                    return new OrganizationMemberNotFoundException(
                            "Member not found for user [" + targetUserId + "] in organization [" + organizationId + "]");
                });

        if (member.getRole() == OrganizationRole.OWNER && newRole != OrganizationRole.OWNER
                && member.getStatus() == OrganizationMembershipStatus.ACTIVE) {
            long activeOwnerCount = organizationMemberRepository.countByOrganizationIdAndRoleAndStatus(
                    organizationId, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE);
            if (activeOwnerCount <= 1) {
                log.warn("Role change rejected: attempt to demote last active OWNER [{}] in organization [{}]",
                        targetUserId, organizationId);
                throw new LastOrganizationOwnerRemovalException(
                        "Cannot demote the last active OWNER of organization [" + organizationId + "]");
            }
        }

        member.setRole(newRole);
        OrganizationMember updated = organizationMemberRepository.save(member);
        log.info("Changed role for user [{}] in organization [{}] to [{}] by caller [{}]", targetUserId, organizationId, newRole, callerUserId);
        return updated;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Retrieves all active members belonging to an organization.
     *
     * <p>Requires caller to be an active member of the organization.
     *
     * @param organizationId the UUID of the organization
     * @param callerUserId the UUID of the authenticated caller
     * @return a list of active {@link OrganizationMember} entities
     * @throws OrganizationNotFoundException if the organization does not exist
     * @throws com.devflow.organization.exception.OrganizationMembershipRequiredException if the caller is not a member
     */
    @Transactional(readOnly = true)
    public List<OrganizationMember> listActiveMembers(UUID organizationId, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        authorizationService.requireMember(organizationId, callerUserId);

        if (!organizationRepository.existsById(organizationId)) {
            log.warn("List active members failed: organization [{}] not found", organizationId);
            throw new OrganizationNotFoundException("Organization not found: " + organizationId);
        }

        log.debug("Listing active members for organization [{}] by caller [{}]", organizationId, callerUserId);
        return organizationMemberRepository.findByOrganizationIdAndStatus(
                organizationId, OrganizationMembershipStatus.ACTIVE);
    }
}
