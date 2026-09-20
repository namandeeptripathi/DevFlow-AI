package com.devflow.workspace.repository;

import com.devflow.workspace.domain.Workspace;
import com.devflow.workspace.domain.WorkspaceVisibility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for the {@link Workspace} entity.
 *
 * <p>Provides tenant-scoped data access operations for managing workspaces within
 * organizations, querying workspace lists, and validating uniqueness constraints.
 *
 * <h2>Multi-Tenant Security Guideline</h2>
 * <p>When fetching or mutating workspace records by entity ID within organization scope,
 * developers MUST use tenant-scoped methods (e.g., {@link #findByIdAndOrganizationId(UUID, UUID)})
 * rather than top-level {@code findById(UUID)} to guarantee tenant isolation and prevent cross-tenant data leaks.
 *
 * @see Workspace
 * @see WorkspaceVisibility
 */
@Repository
public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    /**
     * Finds a workspace by its unique identifier within a specific organization boundary.
     *
     * @param id the UUID of the workspace
     * @param organizationId the UUID of the owning organization
     * @return an {@link Optional} containing the workspace if found within the tenant, or empty if not
     */
    Optional<Workspace> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Checks if a workspace exists by ID within a specific organization boundary.
     *
     * @param id the UUID of the workspace
     * @param organizationId the UUID of the owning organization
     * @return {@code true} if the workspace exists within the tenant; {@code false} otherwise
     */
    boolean existsByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Finds all workspaces belonging to a specific organization.
     *
     * @param organizationId the UUID of the organization
     * @return a list of all workspaces within the organization
     */
    List<Workspace> findByOrganizationId(UUID organizationId);

    /**
     * Finds all workspaces belonging to a specific organization filtered by visibility.
     *
     * @param organizationId the UUID of the organization
     * @param visibility the visibility scope to filter by
     * @return a list of matching workspaces within the organization
     */
    List<Workspace> findByOrganizationIdAndVisibility(UUID organizationId, WorkspaceVisibility visibility);

    /**
     * Checks if a workspace with the given name already exists within an organization,
     * ignoring case differences.
     *
     * @param organizationId the UUID of the organization
     * @param name the workspace name to check
     * @return {@code true} if a workspace with the same name exists in the organization; {@code false} otherwise
     */
    boolean existsByOrganizationIdAndNameIgnoreCase(UUID organizationId, String name);
}
