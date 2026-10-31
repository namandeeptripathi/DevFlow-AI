package com.devflow.team.repository;

import com.devflow.team.domain.Team;
import com.devflow.team.domain.TeamStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for the {@link Team} entity.
 *
 * <p>
 * Provides tenant-scoped and workspace-scoped data access operations for
 * managing teams,
 * querying team collections, and enforcing case-insensitive name uniqueness.
 *
 * <h2>Multi-Tenant Security Guideline</h2>
 * <p>
 * When querying or mutating team records, developers MUST use tenant- and
 * workspace-scoped
 * lookup methods (e.g.,
 * {@link #findByIdAndWorkspaceIdAndOrganizationId(UUID, UUID, UUID)} or
 * {@link #findByIdAndWorkspaceId(UUID, UUID)}) to enforce strict multi-tenant
 * boundary isolation.
 *
 * @see Team
 * @see TeamStatus
 */
@Repository
public interface TeamRepository extends JpaRepository<Team, UUID> {

    /**
     * Finds a team by ID within a specific workspace boundary.
     *
     * @param id          the UUID of the team
     * @param workspaceId the UUID of the owning workspace
     * @return an {@link Optional} containing the team if found within the
     *         workspace, or empty
     */
    Optional<Team> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    /**
     * Finds a team by ID within a specific organization boundary.
     *
     * @param id             the UUID of the team
     * @param organizationId the UUID of the owning organization
     * @return an {@link Optional} containing the team if found within the
     *         organization, or empty
     */
    Optional<Team> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Finds a team by ID within both workspace and organization boundaries.
     *
     * @param id             the UUID of the team
     * @param workspaceId    the UUID of the owning workspace
     * @param organizationId the UUID of the owning organization
     * @return an {@link Optional} containing the team if found within both
     *         boundaries, or empty
     */
    Optional<Team> findByIdAndWorkspaceIdAndOrganizationId(UUID id, UUID workspaceId, UUID organizationId);

    /**
     * Checks if a team exists by ID within a specific workspace boundary.
     *
     * @param id          the UUID of the team
     * @param workspaceId the UUID of the workspace
     * @return {@code true} if the team exists; {@code false} otherwise
     */
    boolean existsByIdAndWorkspaceId(UUID id, UUID workspaceId);

    /**
     * Checks if a team exists by ID within workspace and organization boundaries.
     *
     * @param id             the UUID of the team
     * @param workspaceId    the UUID of the workspace
     * @param organizationId the UUID of the organization
     * @return {@code true} if the team exists within both boundaries; {@code false}
     *         otherwise
     */
    boolean existsByIdAndWorkspaceIdAndOrganizationId(UUID id, UUID workspaceId, UUID organizationId);

    /**
     * Finds all teams belonging to a workspace.
     *
     * @param workspaceId the UUID of the workspace
     * @return a list of teams
     */
    List<Team> findByWorkspaceId(UUID workspaceId);

    /**
     * Finds all teams belonging to a workspace matching a specific lifecycle
     * status.
     *
     * @param workspaceId the UUID of the workspace
     * @param status      the team status
     * @return a list of matching teams
     */
    List<Team> findByWorkspaceIdAndStatus(UUID workspaceId, TeamStatus status);

    /**
     * Finds all teams belonging to a workspace and organization.
     *
     * @param workspaceId    the UUID of the workspace
     * @param organizationId the UUID of the organization
     * @return a list of teams
     */
    List<Team> findByWorkspaceIdAndOrganizationId(UUID workspaceId, UUID organizationId);

    /**
     * Finds all teams belonging to a workspace and organization matching a specific
     * lifecycle status.
     *
     * @param workspaceId    the UUID of the workspace
     * @param organizationId the UUID of the organization
     * @param status         the team status
     * @return a list of matching teams
     */
    List<Team> findByWorkspaceIdAndOrganizationIdAndStatus(UUID workspaceId, UUID organizationId, TeamStatus status);

    /**
     * Finds all teams belonging to an entire organization.
     *
     * @param organizationId the UUID of the organization
     * @return a list of teams in the organization
     */
    List<Team> findByOrganizationId(UUID organizationId);

    /**
     * Finds all teams belonging to an organization matching a specific lifecycle
     * status.
     *
     * @param organizationId the UUID of the organization
     * @param status         the team status
     * @return a list of matching teams
     */
    List<Team> findByOrganizationIdAndStatus(UUID organizationId, TeamStatus status);

    /**
     * Checks if a team with the given name already exists in the workspace,
     * ignoring case differences.
     *
     * @param workspaceId the UUID of the workspace
     * @param name        the team name to check
     * @return {@code true} if a team with the same name exists in the workspace;
     *         {@code false} otherwise
     */
    boolean existsByWorkspaceIdAndNameIgnoreCase(UUID workspaceId, String name);

    /**
     * Checks if a team with the given name already exists within the workspace and
     * organization, ignoring case differences.
     *
     * @param workspaceId    the UUID of the workspace
     * @param organizationId the UUID of the organization
     * @param name           the team name to check
     * @return {@code true} if a team with the same name exists; {@code false}
     *         otherwise
     */
    boolean existsByWorkspaceIdAndOrganizationIdAndNameIgnoreCase(UUID workspaceId, UUID organizationId, String name);

    /**
     * Checks if a team with the given name already exists within the workspace and
     * organization, ignoring case differences,
     * excluding a specific team ID (used for name uniqueness verification during
     * team updates).
     *
     * @param workspaceId    the UUID of the workspace
     * @param organizationId the UUID of the organization
     * @param name           the team name to check
     * @param id             the team ID to exclude
     * @return {@code true} if another team with the same name exists; {@code false}
     *         otherwise
     */
    boolean existsByWorkspaceIdAndOrganizationIdAndNameIgnoreCaseAndIdNot(
            UUID workspaceId, UUID organizationId, String name, UUID id);

    /**
     * Checks if a team with the given name already exists in the workspace,
     * ignoring case differences,
     * excluding a specific team ID.
     *
     * @param workspaceId the UUID of the workspace
     * @param name        the team name to check
     * @param id          the team ID to exclude
     * @return {@code true} if another team with the same name exists; {@code false}
     *         otherwise
     */
    boolean existsByWorkspaceIdAndNameIgnoreCaseAndIdNot(UUID workspaceId, String name, UUID id);

    /**
     * Counts the number of teams in a workspace with the given status.
     *
     * @param workspaceId the UUID of the workspace
     * @param status      the team status to filter by
     * @return count of matching teams
     */
    long countByWorkspaceIdAndStatus(UUID workspaceId, TeamStatus status);

    /**
     * Counts the number of teams in a workspace and organization with the given
     * status.
     *
     * @param workspaceId    the UUID of the workspace
     * @param organizationId the UUID of the organization
     * @param status         the team status
     * @return count of matching teams
     */
    long countByWorkspaceIdAndOrganizationIdAndStatus(UUID workspaceId, UUID organizationId, TeamStatus status);

    /**
     * Counts the number of teams in an entire organization matching the given
     * status.
     *
     * @param organizationId the UUID of the organization
     * @param status         the team status
     * @return count of matching teams
     */
    long countByOrganizationIdAndStatus(UUID organizationId, TeamStatus status);
}
