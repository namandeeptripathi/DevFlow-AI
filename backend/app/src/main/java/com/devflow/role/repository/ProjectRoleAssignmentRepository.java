package com.devflow.role.repository;

import com.devflow.role.domain.ProjectRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link ProjectRoleAssignment}.
 */
@Repository
public interface ProjectRoleAssignmentRepository extends JpaRepository<ProjectRoleAssignment, UUID> {

    /**
     * Finds all assignments (including revoked/expired) for the specified project and user.
     *
     * @param projectId project UUID
     * @param userId user UUID
     * @return list of assignments
     */
    List<ProjectRoleAssignment> findByProjectIdAndUserId(UUID projectId, UUID userId);

    /**
     * Finds all assignments within a project.
     *
     * @param projectId project UUID
     * @return list of assignments
     */
    List<ProjectRoleAssignment> findByProjectId(UUID projectId);

    /**
     * Finds all active (non-revoked, unexpired) project role assignments for a user in a project.
     *
     * @param projectId project UUID
     * @param userId user UUID
     * @param now reference instant for checking expiration
     * @return list of active assignments with role eagerly initialized
     */
    @Query("""
        SELECT a FROM ProjectRoleAssignment a
        JOIN FETCH a.role
        WHERE a.projectId = :projectId
          AND a.user.id = :userId
          AND a.revokedAt IS NULL
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
    """)
    List<ProjectRoleAssignment> findActiveByProjectIdAndUserId(
        @Param("projectId") UUID projectId,
        @Param("userId") UUID userId,
        @Param("now") Instant now
    );

    /**
     * Finds all active project role assignments for a user, verifying the enclosing organization tenant boundary.
     *
     * @param organizationId tenant organization UUID
     * @param projectId project UUID
     * @param userId user UUID
     * @param now reference instant
     * @return list of active assignments with role eagerly initialized
     */
    @Query("""
        SELECT a FROM ProjectRoleAssignment a
        JOIN FETCH a.role
        WHERE a.organization.id = :organizationId
          AND a.projectId = :projectId
          AND a.user.id = :userId
          AND a.revokedAt IS NULL
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
    """)
    List<ProjectRoleAssignment> findActiveByOrganizationIdAndProjectIdAndUserId(
        @Param("organizationId") UUID organizationId,
        @Param("projectId") UUID projectId,
        @Param("userId") UUID userId,
        @Param("now") Instant now
    );

    /**
     * Finds a specific active project assignment for given project, user, and role.
     *
     * @param projectId project UUID
     * @param userId user UUID
     * @param roleId role UUID
     * @param now reference instant
     * @return optional containing active assignment if found
     */
    @Query("""
        SELECT a FROM ProjectRoleAssignment a
        JOIN FETCH a.role
        WHERE a.projectId = :projectId
          AND a.user.id = :userId
          AND a.role.id = :roleId
          AND a.revokedAt IS NULL
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
    """)
    Optional<ProjectRoleAssignment> findActiveByProjectIdAndUserIdAndRoleId(
        @Param("projectId") UUID projectId,
        @Param("userId") UUID userId,
        @Param("roleId") UUID roleId,
        @Param("now") Instant now
    );

    /**
     * Checks if a user holds any active project role assignment within the specified project and organization.
     *
     * @param organizationId tenant organization UUID
     * @param projectId project UUID
     * @param userId user UUID
     * @param now reference instant
     * @return {@code true} if an active project assignment exists; {@code false} otherwise
     */
    @Query("""
        SELECT COUNT(a) > 0 FROM ProjectRoleAssignment a
        WHERE a.organization.id = :organizationId
          AND a.projectId = :projectId
          AND a.user.id = :userId
          AND a.revokedAt IS NULL
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
    """)
    boolean hasActiveProjectAssignment(
        @Param("organizationId") UUID organizationId,
        @Param("projectId") UUID projectId,
        @Param("userId") UUID userId,
        @Param("now") Instant now
    );

    /**
     * Finds any unrevoked assignments for the specified project, user, and role that have expired.
     *
     * @param projectId project UUID
     * @param userId user UUID
     * @param roleId role UUID
     * @param now reference instant
     * @return list of expired unrevoked assignments
     */
    @Query("""
        SELECT a FROM ProjectRoleAssignment a
        WHERE a.projectId = :projectId
          AND a.user.id = :userId
          AND a.role.id = :roleId
          AND a.revokedAt IS NULL
          AND a.expiresAt IS NOT NULL
          AND a.expiresAt <= :now
    """)
    List<ProjectRoleAssignment> findExpiredUnrevoked(
        @Param("projectId") UUID projectId,
        @Param("userId") UUID userId,
        @Param("roleId") UUID roleId,
        @Param("now") Instant now
    );

    /**
     * Finds all unrevoked project assignments that have passed their expiration timestamp.
     *
     * @param now reference instant
     * @return list of stale unrevoked assignments
     */
    @Query("""
        SELECT a FROM ProjectRoleAssignment a
        WHERE a.revokedAt IS NULL
          AND a.expiresAt IS NOT NULL
          AND a.expiresAt <= :now
    """)
    List<ProjectRoleAssignment> findStaleUnrevoked(@Param("now") Instant now);
}
