package com.devflow.role.repository;

import com.devflow.role.domain.CustomRolePermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link CustomRolePermissionEntity} (Stage 7).
 */
@Repository
public interface CustomRolePermissionRepository extends JpaRepository<CustomRolePermissionEntity, UUID> {

    List<CustomRolePermissionEntity> findByCustomRoleId(UUID customRoleId);

    void deleteByCustomRoleId(UUID customRoleId);

    boolean existsByCustomRoleIdAndPermissionId(UUID customRoleId, UUID permissionId);
}
