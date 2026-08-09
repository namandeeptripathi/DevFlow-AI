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
 *   <li>Add users to an organization with a specific {@link OrganizationRole}.</li>
 *   <li>Remove users from an organization while preserving at least one active {@link OrganizationRole#OWNER}.</li>
 *   <li>List all active members belonging to an organization.</li>
 *   <li>Change a member's role while enforcing owner preservation rules.</li>
 * </ul>
 *
 * <h2>Architectural Boundaries</h2>
 * <ul>
 *   <li>Constructor injection only — no field injection.</li>
 *   <li>Command operations use {@link Transactional}, query operations use {@link Transactional#readOnly()}.</li>
 *   <li>Enforces multi-tenant member uniqueness and owner guardrails at the domain service level.</li>
 * </ul>
 *
 * @see OrganizationMember
 * @see OrganizationRole
 * @see OrganizationMembershipStatus
 * @see OrganizationMemberRepository
 */
@Service
public class OrganizationMembershipService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationMembershipService.class);

    private final OrganizationMemberRepository organizationMemberRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;

    public OrganizationMembershipService(
            OrganizationMemberRepository organizationMemberRepository,
            OrganizationRepository organizationRepository,
            UserRepository userRepository
    ) {
        this.organizationMemberRepository = Objects.requireNonNull(
                organizationMemberRepository, "organizationMemberRepository must not be null");
        this.organizationRepository = Objects.requireNonNull(
                organizationRepository, "organizationRepository must not be null");
        this.userRepository = Objects.requireNonNull(
                userRepository, "userRepository must not be null");
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    /**
     * Adds a user to an organization with the specified role.
     *
     * @param organizationId the UUID of the target organization
     * @param userId the UUID of the user to add
     * @param role the initial role to assign
     * @return the saved {@link OrganizationMember} entity
     * @throws OrganizationNotFoundException if the organization or user does not exist
     * @throws OrganizationMemberAlreadyExistsException if the user is already a member of the organization
     */
    @Transactional
    public OrganizationMember addMember(UUID organizationId, UUID userId, OrganizationRole role) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(role, "role must not be null");

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> {
                    log.warn("Add member failed: organization [{}] not found", organizationId);
                    return new OrganizationNotFoundException("Organization not found: " + organizationId);
                });

        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("Add member failed: user [{}] not found", userId);
                    return new OrganizationNotFoundException("User not found: " + userId);
                });

        if (organizationMemberRepository.existsByOrganizationIdAndUserId(organizationId, userId)) {
            log.warn("Add member rejected: user [{}] already member of organization [{}]", userId, organizationId);
            throw new OrganizationMemberAlreadyExistsException(
                    "User [" + userId + "] is already a member of organization [" + organizationId + "]");
        }

        OrganizationMember member = OrganizationMember.builder()
                .organization(organization)
                .user(user)
                .role(role)
                .status(OrganizationMembershipStatus.ACTIVE)
                .joinedAt(Instant.now())
                .build();

        OrganizationMember saved = organizationMemberRepository.save(member);
        log.info("Added user [{}] to organization [{}] with role [{}]", userId, organizationId, role);
        return saved;
    }

    /**
     * Removes a user from an organization.
     *
     * <p>Enforces that the last active {@link OrganizationRole#OWNER} cannot be removed.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the member user to remove
     * @throws OrganizationMemberNotFoundException if the user is not a member of the organization
     * @throws LastOrganizationOwnerRemovalException if removing the user would leave zero active owners
     */
    @Transactional
    public void removeMember(UUID organizationId, UUID userId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, userId)
                .orElseThrow(() -> {
                    log.warn("Remove member failed: member not found for user [{}] in organization [{}]", userId, organizationId);
                    return new OrganizationMemberNotFoundException(
                            "Member not found for user [" + userId + "] in organization [" + organizationId + "]");
                });

        if (member.getRole() == OrganizationRole.OWNER && member.getStatus() == OrganizationMembershipStatus.ACTIVE) {
            long activeOwnerCount = organizationMemberRepository.countByOrganizationIdAndRoleAndStatus(
                    organizationId, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE);
            if (activeOwnerCount <= 1) {
                log.warn("Remove member rejected: attempt to remove last active OWNER [{}] from organization [{}]",
                        userId, organizationId);
                throw new LastOrganizationOwnerRemovalException(
                        "Cannot remove the last active OWNER from organization [" + organizationId + "]");
            }
        }

        organizationMemberRepository.delete(member);
        log.info("Removed user [{}] from organization [{}]", userId, organizationId);
    }

    /**
     * Changes an existing member's role within an organization.
     *
     * <p>Enforces that demoting an {@link OrganizationRole#OWNER} to a non-owner role
     * requires at least one other active owner to remain in the organization.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the member user
     * @param newRole the new role to assign
     * @return the updated {@link OrganizationMember} entity
     * @throws OrganizationMemberNotFoundException if the user is not a member of the organization
     * @throws LastOrganizationOwnerRemovalException if demoting the owner would leave zero active owners
     */
    @Transactional
    public OrganizationMember changeMemberRole(UUID organizationId, UUID userId, OrganizationRole newRole) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(newRole, "newRole must not be null");

        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, userId)
                .orElseThrow(() -> {
                    log.warn("Role change failed: member not found for user [{}] in organization [{}]", userId, organizationId);
                    return new OrganizationMemberNotFoundException(
                            "Member not found for user [" + userId + "] in organization [" + organizationId + "]");
                });

        if (member.getRole() == OrganizationRole.OWNER && newRole != OrganizationRole.OWNER
                && member.getStatus() == OrganizationMembershipStatus.ACTIVE) {
            long activeOwnerCount = organizationMemberRepository.countByOrganizationIdAndRoleAndStatus(
                    organizationId, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE);
            if (activeOwnerCount <= 1) {
                log.warn("Role change rejected: attempt to demote last active OWNER [{}] in organization [{}]",
                        userId, organizationId);
                throw new LastOrganizationOwnerRemovalException(
                        "Cannot demote the last active OWNER of organization [" + organizationId + "]");
            }
        }

        member.setRole(newRole);
        OrganizationMember updated = organizationMemberRepository.save(member);
        log.info("Changed role for user [{}] in organization [{}] to [{}]", userId, organizationId, newRole);
        return updated;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Retrieves all active members belonging to an organization.
     *
     * @param organizationId the UUID of the organization
     * @return a list of active {@link OrganizationMember} entities
     * @throws OrganizationNotFoundException if the organization does not exist
     */
    @Transactional(readOnly = true)
    public List<OrganizationMember> listActiveMembers(UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");

        if (!organizationRepository.existsById(organizationId)) {
            log.warn("List active members failed: organization [{}] not found", organizationId);
            throw new OrganizationNotFoundException("Organization not found: " + organizationId);
        }

        log.debug("Listing active members for organization [{}]", organizationId);
        return organizationMemberRepository.findByOrganizationIdAndStatus(
                organizationId, OrganizationMembershipStatus.ACTIVE);
    }
}
