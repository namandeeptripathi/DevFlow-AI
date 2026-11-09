package com.devflow.role.repository;

import com.devflow.role.domain.CustomRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link CustomRoleAssignment} (Stage 7).
 */
@Repository
public interface CustomRoleAssignmentRepository extends JpaRepository<CustomRoleAssignment, UUID> {

    List<CustomRoleAssignment> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    List<CustomRoleAssignment> findByOrganizationId(UUID organizationId);

    @Query("""
        SELECT a FROM CustomRoleAssignment a
        JOIN FETCH a.customRole r
        LEFT JOIN FETCH r.rolePermissions rp
        LEFT JOIN FETCH rp.permission
        WHERE a.organization.id = :organizationId
          AND a.user.id = :userId
          AND a.revokedAt IS NULL
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
    """)
    List<CustomRoleAssignment> findActiveByOrganizationIdAndUserId(
        @Param("organizationId") UUID organizationId,
        @Param("userId") UUID userId,
        @Param("now") Instant now
    );

    @Query("""
        SELECT a FROM CustomRoleAssignment a
        JOIN FETCH a.customRole r
        JOIN FETCH a.user u
        WHERE a.organization.id = :organizationId
          AND a.revokedAt IS NULL
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
    """)
    List<CustomRoleAssignment> findActiveByOrganizationId(
        @Param("organizationId") UUID organizationId,
        @Param("now") Instant now
    );

    @Query("""
        SELECT a FROM CustomRoleAssignment a
        JOIN FETCH a.customRole r
        JOIN FETCH a.user u
        WHERE a.organization.id = :organizationId
    """)
    List<CustomRoleAssignment> findAllWithDetailsByOrganizationId(
        @Param("organizationId") UUID organizationId
    );

    @Query("""
        SELECT a FROM CustomRoleAssignment a
        JOIN FETCH a.customRole r
        WHERE a.organization.id = :organizationId
          AND a.user.id = :userId
          AND a.customRole.id = :customRoleId
          AND a.revokedAt IS NULL
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
    """)
    Optional<CustomRoleAssignment> findActiveByOrganizationIdAndUserIdAndCustomRoleId(
        @Param("organizationId") UUID organizationId,
        @Param("userId") UUID userId,
        @Param("customRoleId") UUID customRoleId,
        @Param("now") Instant now
    );

    @Query("""
        SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END
        FROM CustomRoleAssignment a
        WHERE a.organization.id = :organizationId
          AND a.user.id = :userId
          AND a.customRole.id = :customRoleId
          AND a.revokedAt IS NULL
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
    """)
    boolean hasActiveAssignment(
        @Param("organizationId") UUID organizationId,
        @Param("userId") UUID userId,
        @Param("customRoleId") UUID customRoleId,
        @Param("now") Instant now
    );

    @Query("""
        SELECT COUNT(a) FROM CustomRoleAssignment a
        WHERE a.customRole.id = :customRoleId
          AND a.revokedAt IS NULL
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
    """)
    long countActiveByCustomRoleId(
        @Param("customRoleId") UUID customRoleId,
        @Param("now") Instant now
    );

    long countByCustomRoleId(UUID customRoleId);

    @Query("""
        SELECT a FROM CustomRoleAssignment a
        WHERE a.organization.id = :organizationId
          AND a.user.id = :userId
          AND a.customRole.id = :customRoleId
          AND a.revokedAt IS NULL
          AND a.expiresAt IS NOT NULL
          AND a.expiresAt <= :now
    """)
    List<CustomRoleAssignment> findExpiredUnrevoked(
        @Param("organizationId") UUID organizationId,
        @Param("userId") UUID userId,
        @Param("customRoleId") UUID customRoleId,
        @Param("now") Instant now
    );
}
