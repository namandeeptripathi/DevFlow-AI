package com.devflow.permission.repository;

import com.devflow.permission.domain.PermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link PermissionEntity}.
 */
@Repository
public interface PermissionRepository extends JpaRepository<PermissionEntity, UUID> {

    /**
     * Finds a permission by its canonical string value (e.g., "project.read").
     *
     * @param name the canonical permission string
     * @return an {@link Optional} containing the permission if found
     */
    Optional<PermissionEntity> findByName(String name);

    /**
     * Finds all permissions belonging to a given resource domain (e.g., "repo", "task").
     *
     * @param resource the resource domain
     * @return list of matching permissions
     */
    List<PermissionEntity> findByResource(String resource);

    /**
     * Checks if a permission with the given canonical string exists.
     *
     * @param name the canonical permission string
     * @return {@code true} if present; {@code false} otherwise
     */
    boolean existsByName(String name);
}
