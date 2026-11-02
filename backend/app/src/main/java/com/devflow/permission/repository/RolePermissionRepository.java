package com.devflow.permission.repository;

import com.devflow.permission.domain.RolePermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link RolePermissionEntity}.
 */
@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermissionEntity, UUID> {

    /**
     * Finds all role-permission mappings for the given role ID.
     *
     * @param roleId the role UUID
     * @return list of mappings
     */
    List<RolePermissionEntity> findByRoleId(UUID roleId);

    /**
     * Finds all role-permission mappings for the given role name with permissions eagerly fetched.
     *
     * @param roleName the canonical role name (e.g. "ORGANIZATION_ADMIN")
     * @return list of mappings with permissions initialized
     */
    @Query("""
        SELECT rp FROM RolePermissionEntity rp
        JOIN FETCH rp.permission
        WHERE rp.role.name = :roleName
    """)
    List<RolePermissionEntity> findByRoleName(@Param("roleName") String roleName);

    /**
     * Checks if a mapping exists for the specified role and permission IDs.
     *
     * @param roleId the role UUID
     * @param permissionId the permission UUID
     * @return {@code true} if mapping exists; {@code false} otherwise
     */
    boolean existsByRoleIdAndPermissionId(UUID roleId, UUID permissionId);
}
