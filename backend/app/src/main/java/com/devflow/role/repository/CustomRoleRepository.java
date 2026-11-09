package com.devflow.role.repository;

import com.devflow.role.domain.CustomRoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link CustomRoleEntity} (Stage 7).
 */
@Repository
public interface CustomRoleRepository extends JpaRepository<CustomRoleEntity, UUID> {

    /**
     * Finds a custom role by ID and organization ID to enforce tenant isolation.
     */
    Optional<CustomRoleEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Finds a custom role by organization ID and unique role name.
     */
    Optional<CustomRoleEntity> findByOrganizationIdAndName(UUID organizationId, String name);

    /**
     * Checks if a custom role name already exists in the given organization.
     */
    boolean existsByOrganizationIdAndName(UUID organizationId, String name);

    /**
     * Finds all custom roles belonging to an organization.
     */
    List<CustomRoleEntity> findByOrganizationId(UUID organizationId);

    /**
     * Finds custom roles belonging to an organization filtered by active status.
     */
    List<CustomRoleEntity> findByOrganizationIdAndActive(UUID organizationId, boolean active);

    /**
     * Eagerly loads a custom role with its permissions by ID and organization ID.
     */
    @Query("""
        SELECT r FROM CustomRoleEntity r
        LEFT JOIN FETCH r.rolePermissions rp
        LEFT JOIN FETCH rp.permission
        WHERE r.id = :id AND r.organization.id = :organizationId
    """)
    Optional<CustomRoleEntity> findByIdAndOrganizationIdWithPermissions(
        @Param("id") UUID id,
        @Param("organizationId") UUID organizationId
    );

    /**
     * Eagerly loads all custom roles with their permissions for an organization.
     */
    @Query("""
        SELECT DISTINCT r FROM CustomRoleEntity r
        LEFT JOIN FETCH r.rolePermissions rp
        LEFT JOIN FETCH rp.permission
        WHERE r.organization.id = :organizationId
    """)
    List<CustomRoleEntity> findByOrganizationIdWithPermissions(@Param("organizationId") UUID organizationId);
}
