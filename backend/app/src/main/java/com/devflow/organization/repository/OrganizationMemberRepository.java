package com.devflow.organization.repository;

import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for the {@link OrganizationMember} entity.
 *
 * <p>Provides tenant-scoped data access operations for managing user memberships within
 * organizations, querying member lists, and validating role constraints.
 *
 * @see OrganizationMember
 */
@Repository
public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, UUID> {

    /**
     * Finds a membership record for a specific organization and user.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @return an {@link Optional} containing the member if found, or empty if not
     */
    Optional<OrganizationMember> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    /**
     * Finds an active membership record for a specific organization and user.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @param status the membership status
     * @return an {@link Optional} containing the active member if found, or empty if not
     */
    Optional<OrganizationMember> findByOrganizationIdAndUserIdAndStatus(
            UUID organizationId, UUID userId, OrganizationMembershipStatus status);

    /**
     * Checks if a membership record exists for a specific organization and user.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @return {@code true} if a membership exists; {@code false} otherwise
     */
    boolean existsByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    /**
     * Checks if a membership record with a specific status exists for an organization and user.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @param status the membership status to check
     * @return {@code true} if an active membership exists; {@code false} otherwise
     */
    boolean existsByOrganizationIdAndUserIdAndStatus(
            UUID organizationId, UUID userId, OrganizationMembershipStatus status);

    /**
     * Checks if a user holds an active membership with any of the specified roles in an organization.
     *
     * @param organizationId the UUID of the organization
     * @param userId the UUID of the user
     * @param roles the set of allowed roles
     * @param status the membership status (must be ACTIVE)
     * @return {@code true} if a matching membership exists; {@code false} otherwise
     */
    boolean existsByOrganizationIdAndUserIdAndRoleInAndStatus(
            UUID organizationId, UUID userId, Collection<OrganizationRole> roles, OrganizationMembershipStatus status);

    /**
     * Finds all membership records for an organization matching a specific status.
     *
     * @param organizationId the UUID of the organization
     * @param status the membership status to filter by
     * @return a list of matching {@link OrganizationMember} entities
     */
    List<OrganizationMember> findByOrganizationIdAndStatus(UUID organizationId, OrganizationMembershipStatus status);

    /**
     * Counts the number of members in an organization with a specific role and status.
     *
     * @param organizationId the UUID of the organization
     * @param role the organization role
     * @param status the membership status
     * @return the count of matching membership records
     */
    long countByOrganizationIdAndRoleAndStatus(UUID organizationId, OrganizationRole role, OrganizationMembershipStatus status);
}
