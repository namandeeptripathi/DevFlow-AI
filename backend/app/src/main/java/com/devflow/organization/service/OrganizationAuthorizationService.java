package com.devflow.organization.service;

import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.exception.OrganizationAccessDeniedException;
import com.devflow.organization.exception.OrganizationMembershipRequiredException;
import com.devflow.organization.repository.OrganizationMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Centralised authorization service for organization-scoped permission checks.
 *
 * <p>All organization permission decisions are routed through this service to
 * prevent duplication of role-checking logic across domain services and controllers.
 *
 * <h2>Permission Model</h2>
 * <ul>
 *   <li><strong>Member</strong>: Any user with an active membership ({@link OrganizationMembershipStatus#ACTIVE}).</li>
 *   <li><strong>Admin or Owner</strong>: Active membership with {@link OrganizationRole#ADMIN} or
 *       {@link OrganizationRole#OWNER}.</li>
 *   <li><strong>Owner</strong>: Active membership with {@link OrganizationRole#OWNER}.</li>
 * </ul>
 *
 * <h2>Security Invariants</h2>
 * <ul>
 *   <li>Only active memberships ({@link OrganizationMembershipStatus#ACTIVE}) are granted authorization.
 *       {@link OrganizationMembershipStatus#INVITED} or {@link OrganizationMembershipStatus#SUSPENDED}
 *       members are strictly denied.</li>
 *   <li>Defensive null handling: Null organization IDs or user IDs fail closed immediately.</li>
 *   <li>Imperative {@code require*} methods issue structured security warning logs on rejection.</li>
 *   <li>Constructor injection only — no field injection.</li>
 * </ul>
 *
 * @see OrganizationMemberRepository
 * @see OrganizationAccessDeniedException
 * @see OrganizationMembershipRequiredException
 */
@Service
public class OrganizationAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationAuthorizationService.class);

    private static final Set<OrganizationRole> ADMIN_OR_OWNER_ROLES = Set.of(
            OrganizationRole.OWNER, OrganizationRole.ADMIN);
    private static final Set<OrganizationRole> OWNER_ONLY_ROLES = Set.of(
            OrganizationRole.OWNER);

    private final OrganizationMemberRepository organizationMemberRepository;

    public OrganizationAuthorizationService(OrganizationMemberRepository organizationMemberRepository) {
        this.organizationMemberRepository = Objects.requireNonNull(
                organizationMemberRepository, "organizationMemberRepository must not be null");
    }

    // ── Boolean queries ──────────────────────────────────────────────────────

    /**
     * Checks whether the user is an active member of the organization (any role).
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @return {@code true} if the user holds an active membership; {@code false} otherwise
     */
    @Transactional(readOnly = true)
    public boolean isMember(UUID organizationId, UUID userId) {
        if (organizationId == null || userId == null) {
            return false;
        }

        return organizationMemberRepository.existsByOrganizationIdAndUserIdAndStatus(
                organizationId, userId, OrganizationMembershipStatus.ACTIVE);
    }

    /**
     * Checks whether the user is an active owner of the organization.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @return {@code true} if the user holds an active OWNER membership; {@code false} otherwise
     */
    @Transactional(readOnly = true)
    public boolean isOwner(UUID organizationId, UUID userId) {
        if (organizationId == null || userId == null) {
            return false;
        }

        return organizationMemberRepository.existsByOrganizationIdAndUserIdAndRoleInAndStatus(
                organizationId, userId, OWNER_ONLY_ROLES, OrganizationMembershipStatus.ACTIVE);
    }

    /**
     * Checks whether the user is an active admin or owner of the organization.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @return {@code true} if the user holds an active ADMIN or OWNER membership; {@code false} otherwise
     */
    @Transactional(readOnly = true)
    public boolean isAdminOrOwner(UUID organizationId, UUID userId) {
        if (organizationId == null || userId == null) {
            return false;
        }

        return organizationMemberRepository.existsByOrganizationIdAndUserIdAndRoleInAndStatus(
                organizationId, userId, ADMIN_OR_OWNER_ROLES, OrganizationMembershipStatus.ACTIVE);
    }

    // ── Imperative checks ────────────────────────────────────────────────────

    /**
     * Requires the user to be an active member of the organization.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @throws OrganizationMembershipRequiredException if the user is not an active member
     */
    @Transactional(readOnly = true)
    public void requireMember(UUID organizationId, UUID userId) {
        if (!isMember(organizationId, userId)) {
            log.warn("Security rejection: user [{}] denied member access to organization [{}]", userId, organizationId);
            throw new OrganizationMembershipRequiredException(
                    "Access denied: active organization membership required for organization [" + organizationId + "]");
        }
    }

    /**
     * Requires the user to be an active admin or owner of the organization.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @throws OrganizationAccessDeniedException if the user is not an active admin or owner
     */
    @Transactional(readOnly = true)
    public void requireAdminOrOwner(UUID organizationId, UUID userId) {
        if (!isAdminOrOwner(organizationId, userId)) {
            log.warn("Security rejection: user [{}] denied admin/owner access to organization [{}]", userId, organizationId);
            throw new OrganizationAccessDeniedException(
                    "Access denied: ADMIN or OWNER role required for organization [" + organizationId + "]");
        }
    }

    /**
     * Requires the user to be an active owner of the organization.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @throws OrganizationAccessDeniedException if the user is not an active owner
     */
    @Transactional(readOnly = true)
    public void requireOwner(UUID organizationId, UUID userId) {
        if (!isOwner(organizationId, userId)) {
            log.warn("Security rejection: user [{}] denied owner access to organization [{}]", userId, organizationId);
            throw new OrganizationAccessDeniedException(
                    "Access denied: OWNER role required for organization [" + organizationId + "]");
        }
    }
}
