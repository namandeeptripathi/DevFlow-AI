package com.devflow.role.repository;

import com.devflow.role.domain.OrganizationRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link OrganizationRoleAssignment}.
 */
@Repository
public interface OrganizationRoleAssignmentRepository extends JpaRepository<OrganizationRoleAssignment, UUID> {

    /**
     * Finds all assignments (including revoked/expired) for the specified organization and user.
     *
     * @param organizationId tenant organization UUID
     * @param userId user UUID
     * @return list of assignments
     */
    List<OrganizationRoleAssignment> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    /**
     * Finds all assignments within the organization.
     *
     * @param organizationId tenant organization UUID
     * @return list of assignments
     */
    List<OrganizationRoleAssignment> findByOrganizationId(UUID organizationId);

    /**
     * Finds all active (non-revoked, unexpired) organization role assignments for a user.
     *
     * @param organizationId tenant organization UUID
     * @param userId user UUID
     * @param now reference instant for checking expiration
     * @return list of active assignments with role eagerly initialized
     */
    @Query("""
        SELECT a FROM OrganizationRoleAssignment a
        JOIN FETCH a.role
        WHERE a.organization.id = :organizationId
          AND a.user.id = :userId
          AND a.revokedAt IS NULL
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
    """)
    List<OrganizationRoleAssignment> findActiveByOrganizationIdAndUserId(
        @Param("organizationId") UUID organizationId,
        @Param("userId") UUID userId,
        @Param("now") Instant now
    );

    /**
     * Finds all active organization role assignments across the entire organization.
     *
     * @param organizationId tenant organization UUID
     * @param now reference instant for checking expiration
     * @return list of active assignments with role eagerly initialized
     */
    @Query("""
        SELECT a FROM OrganizationRoleAssignment a
        JOIN FETCH a.role
        WHERE a.organization.id = :organizationId
          AND a.revokedAt IS NULL
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
    """)
    List<OrganizationRoleAssignment> findActiveByOrganizationId(
        @Param("organizationId") UUID organizationId,
        @Param("now") Instant now
    );

    /**
     * Finds a specific active assignment for the given organization, user, and role.
     *
     * @param organizationId tenant organization UUID
     * @param userId user UUID
     * @param roleId role UUID
     * @param now reference instant
     * @return optional containing the active assignment if found
     */
    @Query("""
        SELECT a FROM OrganizationRoleAssignment a
        JOIN FETCH a.role
        WHERE a.organization.id = :organizationId
          AND a.user.id = :userId
          AND a.role.id = :roleId
          AND a.revokedAt IS NULL
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
    """)
    Optional<OrganizationRoleAssignment> findActiveByOrganizationIdAndUserIdAndRoleId(
        @Param("organizationId") UUID organizationId,
        @Param("userId") UUID userId,
        @Param("roleId") UUID roleId,
        @Param("now") Instant now
    );

    /**
     * Checks if an active assignment already exists for the given organization, user, and role.
     *
     * @param organizationId tenant organization UUID
     * @param userId user UUID
     * @param roleId role UUID
     * @param now reference instant
     * @return {@code true} if active assignment exists; {@code false} otherwise
     */
    @Query("""
        SELECT COUNT(a) > 0 FROM OrganizationRoleAssignment a
        WHERE a.organization.id = :organizationId
          AND a.user.id = :userId
          AND a.role.id = :roleId
          AND a.revokedAt IS NULL
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
    """)
    boolean hasActiveAssignment(
        @Param("organizationId") UUID organizationId,
        @Param("userId") UUID userId,
        @Param("roleId") UUID roleId,
        @Param("now") Instant now
    );

    /**
     * Checks if any assignment records (active, revoked, or expired) exist for this user in this organization.
     * Indicates whether the user has transitioned to the persistent role assignment model.
     *
     * @param organizationId tenant organization UUID
     * @param userId user UUID
     * @return true if assignment records exist; false otherwise
     */
    @Query("""
        SELECT COUNT(a) > 0 FROM OrganizationRoleAssignment a
        WHERE a.organization.id = :organizationId
          AND a.user.id = :userId
    """)
    boolean hasAnyAssignments(
        @Param("organizationId") UUID organizationId,
        @Param("userId") UUID userId
    );

    /**
     * Finds any unrevoked assignments for the specified organization, user, and role that have expired.
     *
     * @param organizationId tenant organization UUID
     * @param userId user UUID
     * @param roleId role UUID
     * @param now reference instant
     * @return list of expired unrevoked assignments
     */
    @Query("""
        SELECT a FROM OrganizationRoleAssignment a
        WHERE a.organization.id = :organizationId
          AND a.user.id = :userId
          AND a.role.id = :roleId
          AND a.revokedAt IS NULL
          AND a.expiresAt IS NOT NULL
          AND a.expiresAt <= :now
    """)
    List<OrganizationRoleAssignment> findExpiredUnrevoked(
        @Param("organizationId") UUID organizationId,
        @Param("userId") UUID userId,
        @Param("roleId") UUID roleId,
        @Param("now") Instant now
    );

    /**
     * Finds all unrevoked assignments across all organizations that have passed their expiration timestamp.
     *
     * @param now reference instant
     * @return list of stale unrevoked assignments
     */
    @Query("""
        SELECT a FROM OrganizationRoleAssignment a
        WHERE a.revokedAt IS NULL
          AND a.expiresAt IS NOT NULL
          AND a.expiresAt <= :now
    """)
    List<OrganizationRoleAssignment> findStaleUnrevoked(@Param("now") Instant now);

    /**
     * Counts active ORGANIZATION_OWNER assignments in an organization.
     *
     * @param organizationId tenant organization UUID
     * @param now reference instant
     * @return count of active owner assignments
     */
    @Query("""
        SELECT COUNT(a) FROM OrganizationRoleAssignment a
        WHERE a.organization.id = :organizationId
          AND a.role.name = 'ORGANIZATION_OWNER'
          AND a.revokedAt IS NULL
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
    """)
    long countActiveOwners(
        @Param("organizationId") UUID organizationId,
        @Param("now") Instant now
    );
}
