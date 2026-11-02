package com.devflow.role.repository;

import com.devflow.modules.auth.domain.RoleScope;
import com.devflow.role.domain.RoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link RoleEntity}.
 */
@Repository
public interface RoleRepository extends JpaRepository<RoleEntity, UUID> {

    /**
     * Looks up a canonical role by its unique name (e.g., "ORGANIZATION_OWNER").
     *
     * @param name the role identifier
     * @return an {@link Optional} containing the role if found
     */
    Optional<RoleEntity> findByName(String name);

    /**
     * Retrieves all roles defined within a particular authorization scope.
     *
     * @param scope the {@link RoleScope}
     * @return a list of roles
     */
    List<RoleEntity> findByScope(RoleScope scope);

    /**
     * Checks if a role with the given name exists.
     *
     * @param name the role identifier
     * @return {@code true} if present; {@code false} otherwise
     */
    boolean existsByName(String name);
}
