package com.devflow.organization.repository;

import com.devflow.organization.domain.OrganizationInvitation;
import com.devflow.organization.domain.OrganizationInvitationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for the {@link OrganizationInvitation} entity.
 *
 * <p>Provides tenant-scoped data access operations for managing organization onboarding
 * invitations, validating tokens, and querying pending invitation lists.
 *
 * <h2>Multi-Tenant Security Guideline</h2>
 * <p>When fetching or mutating invitation records by entity ID within organization scope,
 * developers MUST use tenant-scoped methods (e.g., {@link #findByIdAndOrganizationId(UUID, UUID)})
 * rather than top-level {@code findById(UUID)} to guarantee tenant isolation and prevent cross-tenant data leaks.
 *
 * @see OrganizationInvitation
 */
@Repository
public interface OrganizationInvitationRepository extends JpaRepository<OrganizationInvitation, UUID> {

    /**
     * Finds an invitation by its unguessable secure token string.
     *
     * @param token the secure validation token
     * @return an {@link Optional} containing the invitation if found, or empty if not
     */
    Optional<OrganizationInvitation> findByToken(String token);

    /**
     * Finds an invitation by its unique identifier within a specific organization boundary.
     *
     * @param id the UUID of the invitation
     * @param organizationId the UUID of the owning organization
     * @return an {@link Optional} containing the invitation if found within the tenant, or empty if not
     */
    Optional<OrganizationInvitation> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Checks if an invitation exists by ID within a specific organization boundary.
     *
     * @param id the UUID of the invitation
     * @param organizationId the UUID of the owning organization
     * @return {@code true} if the invitation exists within the tenant; {@code false} otherwise
     */
    boolean existsByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Checks if a pending invitation exists for a target email address within an organization boundary.
     *
     * @param organizationId the UUID of the organization
     * @param email the target recipient's email address
     * @param status the invitation status (e.g., PENDING)
     * @return {@code true} if a matching invitation exists; {@code false} otherwise
     */
    boolean existsByOrganizationIdAndEmailAndStatus(
            UUID organizationId, String email, OrganizationInvitationStatus status);

    /**
     * Finds all invitations for an organization matching a specific status.
     *
     * @param organizationId the UUID of the organization
     * @param status the invitation status to filter by
     * @return a list of matching {@link OrganizationInvitation} entities
     */
    List<OrganizationInvitation> findByOrganizationIdAndStatus(
            UUID organizationId, OrganizationInvitationStatus status);

    /**
     * Counts the number of invitations for an organization matching a specific status.
     *
     * @param organizationId the UUID of the organization
     * @param status the invitation status to count
     * @return the count of matching invitation records
     */
    long countByOrganizationIdAndStatus(UUID organizationId, OrganizationInvitationStatus status);

    /**
     * Finds all invitations across the system that have passed their expiration timestamp
     * and remain in a specific status (e.g., PENDING).
     *
     * @param now the reference timestamp
     * @param status the invitation status to match
     * @return a list of expired invitation records
     */
    List<OrganizationInvitation> findByExpiresAtBeforeAndStatus(
            Instant now, OrganizationInvitationStatus status);
}
