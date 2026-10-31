package com.devflow.team.repository;

import com.devflow.team.domain.TeamMember;
import com.devflow.team.domain.TeamRole;
import com.devflow.team.domain.TeamStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for the {@link TeamMember} entity.
 *
 * <p>Provides data access operations for managing team memberships, role queries,
 * and leadership resolution.
 *
 * <h2>Single Source of Truth for Leadership</h2>
 * <p>Because leadership is stored solely in {@link TeamMember} via {@link TeamRole#LEAD},
 * the team lead is resolved via {@link #findByTeamIdAndRole(UUID, TeamRole)} or convenience method
 * {@link #findLeadByTeamId(UUID)}.
 *
 * <h2>Tenant Isolation Guarantees</h2>
 * <p>Multi-tenant query methods (such as {@link #findByUserIdAndOrganizationId(UUID, UUID)} and
 * {@link #existsByUserIdAndRoleAndOrganizationIdAndTeamStatus(UUID, TeamRole, UUID, TeamStatus)})
 * enforce organization boundaries when managing membership lifecycles.
 *
 * @see TeamMember
 * @see TeamRole
 */
@Repository
public interface TeamMemberRepository extends JpaRepository<TeamMember, UUID> {

    /**
     * Finds a membership record by ID within a specific team.
     *
     * @param id the UUID of the membership record
     * @param teamId the UUID of the team
     * @return an {@link Optional} containing the member if found, or empty
     */
    Optional<TeamMember> findByIdAndTeamId(UUID id, UUID teamId);

    /**
     * Finds a membership record for a specific team and user.
     *
     * @param teamId the UUID of the team
     * @param userId the UUID of the user
     * @return an {@link Optional} containing the member if found, or empty
     */
    Optional<TeamMember> findByTeamIdAndUserId(UUID teamId, UUID userId);

    /**
     * Finds a membership record within a specific organization, team, and user boundary.
     *
     * @param organizationId the UUID of the organization
     * @param teamId the UUID of the team
     * @param userId the UUID of the user
     * @return an {@link Optional} containing the member if found, or empty
     */
    @EntityGraph(attributePaths = {"user", "team"})
    @Query("""
        SELECT tm FROM TeamMember tm
        WHERE tm.team.organization.id = :organizationId
          AND tm.team.id = :teamId
          AND tm.user.id = :userId
    """)
    Optional<TeamMember> findByOrganizationIdAndTeamIdAndUserId(
            @Param("organizationId") UUID organizationId,
            @Param("teamId") UUID teamId,
            @Param("userId") UUID userId);

    /**
     * Checks if a user is a member of the specified team.
     *
     * @param teamId the UUID of the team
     * @param userId the UUID of the user
     * @return {@code true} if membership exists; {@code false} otherwise
     */
    boolean existsByTeamIdAndUserId(UUID teamId, UUID userId);

    /**
     * Checks if a membership exists within specific organization, team, and user boundaries.
     *
     * @param organizationId the UUID of the organization
     * @param teamId the UUID of the team
     * @param userId the UUID of the user
     * @return {@code true} if membership exists; {@code false} otherwise
     */
    @Query("""
        SELECT COUNT(tm) > 0 FROM TeamMember tm
        WHERE tm.team.organization.id = :organizationId
          AND tm.team.id = :teamId
          AND tm.user.id = :userId
    """)
    boolean existsByOrganizationIdAndTeamIdAndUserId(
            @Param("organizationId") UUID organizationId,
            @Param("teamId") UUID teamId,
            @Param("userId") UUID userId);

    /**
     * Finds a member of the team matching a specific role.
     *
     * <p>For {@link TeamRole#LEAD}, this returns the unique active team lead, as enforced
     * by the database partial unique index.
     *
     * @param teamId the UUID of the team
     * @param role the team role to query
     * @return an {@link Optional} containing the matching member, or empty
     */
    @EntityGraph(attributePaths = {"user", "team"})
    Optional<TeamMember> findByTeamIdAndRole(UUID teamId, TeamRole role);

    /**
     * Convenience method to resolve the unique designated team lead.
     *
     * @param teamId the UUID of the team
     * @return an {@link Optional} containing the team lead member, or empty
     */
    default Optional<TeamMember> findLeadByTeamId(UUID teamId) {
        return findByTeamIdAndRole(teamId, TeamRole.LEAD);
    }

    /**
     * Finds the lead of a team within a specific organization boundary.
     *
     * @param organizationId the UUID of the owning organization
     * @param teamId the UUID of the team
     * @return an {@link Optional} containing the team lead, or empty
     */
    @EntityGraph(attributePaths = {"user", "team"})
    @Query("""
        SELECT tm FROM TeamMember tm
        WHERE tm.team.organization.id = :organizationId
          AND tm.team.id = :teamId
          AND tm.role = com.devflow.team.domain.TeamRole.LEAD
    """)
    Optional<TeamMember> findLeadByOrganizationIdAndTeamId(
            @Param("organizationId") UUID organizationId,
            @Param("teamId") UUID teamId);

    /**
     * Finds all membership records for a team.
     *
     * @param teamId the UUID of the team
     * @return list of team members
     */
    @EntityGraph(attributePaths = {"user", "team"})
    List<TeamMember> findByTeamId(UUID teamId);

    /**
     * Finds all membership records for a team within a specific organization boundary.
     *
     * @param organizationId the UUID of the organization
     * @param teamId the UUID of the team
     * @return list of team members in the organization
     */
    @EntityGraph(attributePaths = {"user", "team"})
    @Query("""
        SELECT tm FROM TeamMember tm
        WHERE tm.team.organization.id = :organizationId
          AND tm.team.id = :teamId
    """)
    List<TeamMember> findByOrganizationIdAndTeamId(
            @Param("organizationId") UUID organizationId,
            @Param("teamId") UUID teamId);

    /**
     * Finds all team memberships held by a user across all organizations.
     *
     * @param userId the UUID of the user
     * @return list of team members
     */
    List<TeamMember> findByUserId(UUID userId);

    /**
     * Finds all team memberships held by a user within a specific organization boundary.
     *
     * @param userId the UUID of the user
     * @param organizationId the UUID of the organization
     * @return list of team members in the specified organization
     */
    @Query("""
        SELECT tm FROM TeamMember tm
        WHERE tm.user.id = :userId
          AND tm.team.organization.id = :organizationId
    """)
    List<TeamMember> findByUserIdAndOrganizationId(
            @Param("userId") UUID userId,
            @Param("organizationId") UUID organizationId);

    /**
     * Finds all team memberships within an entire workspace.
     *
     * @param workspaceId the UUID of the workspace
     * @return list of team members in the workspace
     */
    @Query("""
        SELECT tm FROM TeamMember tm
        WHERE tm.team.workspace.id = :workspaceId
    """)
    List<TeamMember> findByWorkspaceId(@Param("workspaceId") UUID workspaceId);

    /**
     * Finds all team memberships within an entire organization.
     *
     * @param organizationId the UUID of the organization
     * @return list of team members in the organization
     */
    @Query("""
        SELECT tm FROM TeamMember tm
        WHERE tm.team.organization.id = :organizationId
    """)
    List<TeamMember> findByOrganizationId(@Param("organizationId") UUID organizationId);

    /**
     * Checks if a user holds a specific role in any team matching the given status within an organization.
     *
     * <p>Primarily used to verify whether a user is an active team lead in an organization
     * before permitting their removal from the organization.
     *
     * @param userId the UUID of the user
     * @param role the team role to query (e.g., LEAD)
     * @param organizationId the UUID of the organization
     * @param teamStatus the lifecycle status of the team (e.g., ACTIVE)
     * @return {@code true} if matching membership exists; {@code false} otherwise
     */
    @Query("""
        SELECT COUNT(tm) > 0 FROM TeamMember tm
        WHERE tm.user.id = :userId
          AND tm.role = :role
          AND tm.team.organization.id = :organizationId
          AND tm.team.status = :teamStatus
    """)
    boolean existsByUserIdAndRoleAndOrganizationIdAndTeamStatus(
            @Param("userId") UUID userId,
            @Param("role") TeamRole role,
            @Param("organizationId") UUID organizationId,
            @Param("teamStatus") TeamStatus teamStatus);

    /**
     * Counts the total number of members in a team.
     *
     * @param teamId the UUID of the team
     * @return member count
     */
    long countByTeamId(UUID teamId);

    /**
     * Counts the number of members in a team holding a specific role.
     *
     * @param teamId the UUID of the team
     * @param role the team role
     * @return count of matching members
     */
    long countByTeamIdAndRole(UUID teamId, TeamRole role);

    /**
     * Deletes a membership record for a specific team and user.
     *
     * @param teamId the UUID of the team
     * @param userId the UUID of the user
     */
    void deleteByTeamIdAndUserId(UUID teamId, UUID userId);

    /**
     * Deletes all membership records belonging to a team.
     *
     * @param teamId the UUID of the team
     */
    void deleteByTeamId(UUID teamId);

    /**
     * Deletes all team memberships held by a user within a specific organization.
     *
     * <p>Used during organization member offboarding to clean up non-lead team memberships.
     *
     * @param userId the UUID of the user
     * @param organizationId the UUID of the owning organization
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        DELETE FROM TeamMember tm
        WHERE tm.user.id = :userId
          AND tm.team.id IN (
              SELECT t.id FROM Team t WHERE t.organization.id = :organizationId
          )
    """)
    void deleteByUserIdAndOrganizationId(
            @Param("userId") UUID userId,
            @Param("organizationId") UUID organizationId);
}
