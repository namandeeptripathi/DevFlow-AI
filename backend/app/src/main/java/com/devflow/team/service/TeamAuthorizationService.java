package com.devflow.team.service;

import com.devflow.organization.service.OrganizationAuthorizationService;
import com.devflow.team.domain.TeamMember;
import com.devflow.team.exception.TeamAccessDeniedException;
import com.devflow.team.repository.TeamMemberRepository;
import com.devflow.workspace.domain.Workspace;
import com.devflow.workspace.domain.WorkspaceVisibility;
import com.devflow.workspace.exception.WorkspaceAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Centralised authorization service for team-level access control and permission enforcement.
 *
 * <h2>Permission Hierarchy</h2>
 * <ul>
 *   <li><strong>Organization OWNER / ADMIN</strong>: Holds full administrative privileges over all teams
 *       across all workspaces in the tenant.</li>
 *   <li><strong>Team LEAD</strong>: Holds management privileges (updating team, archiving, managing members,
 *       transferring leadership) for their own team, provided the workspace is accessible.</li>
 *   <li><strong>Team MEMBER</strong>: Can view team details/members and leave the team, but cannot modify
 *       team settings or manage memberships.</li>
 * </ul>
 *
 * <h2>Workspace Visibility Rules</h2>
 * <ul>
 *   <li>Private workspace access restrictions take precedence over team-level permissions.
 *       Only active organization {@code OWNER} or {@code ADMIN} users may access teams within
 *       a {@code PRIVATE} workspace.</li>
 *   <li>Suspended organization members are strictly denied all team operations.</li>
 * </ul>
 *
 * @see OrganizationAuthorizationService
 * @see TeamMemberRepository
 * @see TeamAccessDeniedException
 * @see WorkspaceAccessDeniedException
 */
@Service
public class TeamAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(TeamAuthorizationService.class);

    private final OrganizationAuthorizationService organizationAuthorizationService;
    private final TeamMemberRepository teamMemberRepository;

    public TeamAuthorizationService(
            OrganizationAuthorizationService organizationAuthorizationService,
            TeamMemberRepository teamMemberRepository
    ) {
        this.organizationAuthorizationService = Objects.requireNonNull(
                organizationAuthorizationService, "organizationAuthorizationService must not be null");
        this.teamMemberRepository = Objects.requireNonNull(
                teamMemberRepository, "teamMemberRepository must not be null");
    }

    /**
     * Checks whether the user is the designated active lead of the team.
     *
     * @param organizationId the UUID of the organization
     * @param teamId the UUID of the team
     * @param userId the UUID of the user
     * @return {@code true} if the user is the team lead; {@code false} otherwise
     */
    @Transactional(readOnly = true)
    public boolean isTeamLead(UUID organizationId, UUID teamId, UUID userId) {
        if (organizationId == null || teamId == null || userId == null) {
            return false;
        }

        return teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, userId)
                .map(TeamMember::isLead)
                .orElse(false);
    }

    /**
     * Checks whether the user is a member (either lead or regular member) of the team.
     *
     * @param organizationId the UUID of the organization
     * @param teamId the UUID of the team
     * @param userId the UUID of the user
     * @return {@code true} if membership exists; {@code false} otherwise
     */
    @Transactional(readOnly = true)
    public boolean isTeamMember(UUID organizationId, UUID teamId, UUID userId) {
        if (organizationId == null || teamId == null || userId == null) {
            return false;
        }

        return teamMemberRepository.existsByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, userId);
    }

    /**
     * Checks whether the user is authorized to view a team within a given workspace.
     *
     * @param organizationId the UUID of the organization
     * @param workspace the workspace containing the team
     * @param userId the UUID of the user
     * @return {@code true} if authorized; {@code false} otherwise
     */
    @Transactional(readOnly = true)
    public boolean canViewTeam(UUID organizationId, Workspace workspace, UUID userId) {
        if (organizationId == null || workspace == null || userId == null) {
            return false;
        }

        if (!organizationAuthorizationService.isMember(organizationId, userId)) {
            return false;
        }

        if (workspace.getVisibility() == WorkspaceVisibility.PRIVATE) {
            return organizationAuthorizationService.isAdminOrOwner(organizationId, userId);
        }

        return true;
    }

    /**
     * Asserts that the caller is authorized to view a team within the workspace.
     *
     * @param organizationId the UUID of the organization
     * @param workspace the workspace containing the team
     * @param userId the UUID of the user
     * @throws com.devflow.organization.exception.OrganizationMembershipRequiredException if user is not an active member
     * @throws WorkspaceAccessDeniedException if workspace is private and user is not admin/owner
     */
    @Transactional(readOnly = true)
    public void requireCanViewTeam(UUID organizationId, Workspace workspace, UUID userId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(workspace, "workspace must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        organizationAuthorizationService.requireMember(organizationId, userId);

        if (workspace.getVisibility() == WorkspaceVisibility.PRIVATE
                && !organizationAuthorizationService.isAdminOrOwner(organizationId, userId)) {
            log.warn("Access denied: user [{}] attempted to access team in private workspace [{}] without admin/owner role",
                    userId, workspace.getId());
            throw new WorkspaceAccessDeniedException(
                    "Access denied: ADMIN or OWNER role required to access private workspace [" + workspace.getId() + "]");
        }
    }

    /**
     * Asserts that the caller is authorized to create a team within a workspace.
     *
     * <p>Only organization {@code OWNER} or {@code ADMIN} can create teams.
     *
     * @param organizationId the UUID of the organization
     * @param workspace the workspace target
     * @param userId the UUID of the caller
     * @throws com.devflow.organization.exception.OrganizationAccessDeniedException if caller lacks admin/owner role
     */
    @Transactional(readOnly = true)
    public void requireCanCreateTeam(UUID organizationId, Workspace workspace, UUID userId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(workspace, "workspace must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        organizationAuthorizationService.requireAdminOrOwner(organizationId, userId);
    }

    /**
     * Asserts that the caller is authorized to manage (update, archive, add/remove member, transfer leadership) a team.
     *
     * <p>Authorized callers are organization {@code OWNER}, {@code ADMIN}, or the active {@code LEAD} of the team,
     * provided workspace visibility rules are respected.
     *
     * @param organizationId the UUID of the organization
     * @param workspace the workspace containing the team
     * @param teamId the UUID of the team
     * @param userId the UUID of the caller
     * @throws WorkspaceAccessDeniedException if workspace is private and user is not admin/owner
     * @throws TeamAccessDeniedException if user lacks admin/owner and is not team lead
     */
    @Transactional(readOnly = true)
    public void requireCanManageTeam(UUID organizationId, Workspace workspace, UUID teamId, UUID userId) {
        requireCanViewTeam(organizationId, workspace, userId);

        if (organizationAuthorizationService.isAdminOrOwner(organizationId, userId)) {
            return;
        }

        if (isTeamLead(organizationId, teamId, userId)) {
            return;
        }

        log.warn("Management rejected: user [{}] lacks admin/owner or lead role for team [{}]", userId, teamId);
        throw new TeamAccessDeniedException(
                "Access denied: Organization ADMIN, OWNER, or Team LEAD role required to manage team [" + teamId + "]");
    }
}

