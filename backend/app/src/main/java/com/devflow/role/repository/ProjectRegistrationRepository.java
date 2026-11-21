package com.devflow.role.repository;

import com.devflow.role.domain.ProjectRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link ProjectRegistration}.
 */
@Repository
public interface ProjectRegistrationRepository extends JpaRepository<ProjectRegistration, UUID> {

    Optional<ProjectRegistration> findByProjectId(UUID projectId);

    boolean existsByProjectId(UUID projectId);

    boolean existsByProjectIdAndOrganizationId(UUID projectId, UUID organizationId);

    @Query("SELECT r.organization.id FROM ProjectRegistration r WHERE r.projectId = :projectId")
    Optional<UUID> findOrganizationIdByProjectId(@Param("projectId") UUID projectId);

    List<ProjectRegistration> findByOrganizationId(UUID organizationId);

    @Modifying
    @Query("DELETE FROM ProjectRegistration r WHERE r.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") UUID projectId);

    @Modifying
    @Query("DELETE FROM ProjectRegistration r WHERE r.organization.id = :organizationId")
    void deleteByOrganizationId(@Param("organizationId") UUID organizationId);
}
