package com.devflow.team.service;

import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.exception.OrganizationMemberNotFoundException;
import com.devflow.organization.exception.OrganizationNotFoundException;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.service.OrganizationAuthorizationService;
import com.devflow.team.domain.Team;
import com.devflow.team.domain.TeamMember;
import com.devflow.team.domain.TeamRole;
import com.devflow.team.domain.TeamStatus;
import com.devflow.team.exception.InvalidTeamOperationException;
import com.devflow.team.exception.TeamAlreadyExistsException;
import com.devflow.team.exception.TeamMemberAlreadyExistsException;
import com.devflow.team.exception.TeamMemberNotFoundException;
import com.devflow.team.exception.TeamNotFoundException;
import com.devflow.team.repository.TeamMemberRepository;
import com.devflow.team.repository.TeamRepository;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import com.devflow.workspace.domain.Workspace;
import com.devflow.workspace.exception.WorkspaceNotFoundException;
import com.devflow.workspace.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain service managing the lifecycle, operational policies, and membership of DevFlow Teams.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Create a new team inside a workspace with atomic initial lead assignment and case-insensitive
 *       name uniqueness enforcement.</li>
 *   <li>Retrieve teams and team members within strict multi-tenant boundaries.</li>
 *   <li>Update mutable team attributes with conflict detection.</li>
 *   <li>Archive teams without physically deleting historical records.</li>
 *   <li>Add, remove, and list team members with organization membership verification.</li>
 *   <li>Atomic leadership transfer preserving the one-lead-per-team partial index invariant.</li>
 * </ul>
 *
 * <h2>Architectural & Security Rules</h2>
 * <ul>
 *   <li>All operations are strictly tenant-scoped by {@code organizationId} and {@code workspaceId}.</li>
 *   <li>Permission checks are delegated to {@link TeamAuthorizationService} and
 *       {@link OrganizationAuthorizationService}.</li>
 *   <li>Suspended or inactive organization members are strictly prohibited from performing operations
 *       or joining teams.</li>
 *   <li>Private workspace access restrictions take precedence over team-level permissions.</li>
 * </ul>
 *
 * @see Team
 * @see TeamMember
 * @see TeamRepository
 * @see TeamMemberRepository
 * @see TeamAuthorizationService
 */
@Service
public class TeamService {

    private static final Logger log = LoggerFactory.getLogger(TeamService.class);

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final OrganizationAuthorizationService organizationAuthorizationService;
    private final TeamAuthorizationService teamAuthorizationService;

    public TeamService(
            TeamRepository teamRepository,
            TeamMemberRepository teamMemberRepository,
            WorkspaceRepository workspaceRepository,
            UserRepository userRepository,
            OrganizationMemberRepository organizationMemberRepository,
            OrganizationAuthorizationService organizationAuthorizationService,
            TeamAuthorizationService teamAuthorizationService
    ) {
        this.teamRepository = Objects.requireNonNull(teamRepository, "teamRepository must not be null");
        this.teamMemberRepository = Objects.requireNonNull(teamMemberRepository, "teamMemberRepository must not be null");
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.organizationMemberRepository = Objects.requireNonNull(
                organizationMemberRepository, "organizationMemberRepository must not be null");
        this.organizationAuthorizationService = Objects.requireNonNull(
                organizationAuthorizationService, "organizationAuthorizationService must not be null");
        this.teamAuthorizationService = Objects.requireNonNull(
                teamAuthorizationService, "teamAuthorizationService must not be null");
    }

    // ── Team Lifecycle Commands ────────────────────────────────────────────────

    /**
     * Creates a new team within a workspace and automatically assigns the creator as the initial team {@code LEAD}.
     *
     * <p>Requires the caller to hold {@code OWNER} or {@code ADMIN} role in the organization.
     *
     * @param organizationId the UUID of the owning organization
     * @param workspaceId the UUID of the target workspace
     * @param name the team name (trimmed, non-blank, max 100 chars)
     * @param description optional team description (max 500 chars)
     * @param callerUserId the UUID of the creator
     * @return the created and persisted {@link Team} aggregate
     * @throws WorkspaceNotFoundException if the workspace is not found within the organization
     * @throws TeamAlreadyExistsException if a team with the same name already exists in the workspace
     * @throws IllegalArgumentException if the team name is blank or exceeds 100 characters
     */
    @Transactional
    public Team createTeam(UUID organizationId, UUID workspaceId, String name, String description, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(name, "team name must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        String trimmedName = name.trim();
        if (trimmedName.isEmpty()) {
            throw new IllegalArgumentException("Team name must not be blank");
        }
        if (trimmedName.length() > 100) {
            throw new IllegalArgumentException("Team name cannot exceed 100 characters");
        }

        String trimmedDescription = normalise(description);
        if (trimmedDescription != null && trimmedDescription.length() > 500) {
            throw new IllegalArgumentException("Team description cannot exceed 500 characters");
        }

        Workspace workspace = workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId)
                .orElseThrow(() -> {
                    log.warn("Team creation rejected: workspace [{}] not found in organization [{}]", workspaceId, organizationId);
                    return new WorkspaceNotFoundException(
                            "Workspace not found: " + workspaceId + " in organization: " + organizationId);
                });

        teamAuthorizationService.requireCanCreateTeam(organizationId, workspace, callerUserId);

        if (teamRepository.existsByWorkspaceIdAndOrganizationIdAndNameIgnoreCase(workspaceId, organizationId, trimmedName)) {
            log.warn("Team creation rejected: team [{}] already exists in workspace [{}]", trimmedName, workspaceId);
            throw new TeamAlreadyExistsException(
                    "Team with name [" + trimmedName + "] already exists in workspace [" + workspaceId + "]");
        }

        User creator = userRepository.findById(callerUserId)
                .orElseThrow(() -> new OrganizationNotFoundException("User not found: " + callerUserId));

        Team team = Team.builder()
                .name(trimmedName)
                .description(trimmedDescription)
                .workspace(workspace)
                .organization(workspace.getOrganization())
                .status(TeamStatus.ACTIVE)
                .build();

        Team savedTeam = teamRepository.save(team);

        TeamMember initialLead = TeamMember.builder()
                .team(savedTeam)
                .user(creator)
                .role(TeamRole.LEAD)
                .joinedAt(Instant.now())
                .build();

        teamMemberRepository.save(initialLead);

        log.info("Created team [{}] in workspace [{}] with initial lead [{}] by user [{}]",
                savedTeam.getId(), workspaceId, creator.getId(), callerUserId);

        return savedTeam;
    }

    /**
     * Updates mutable attributes of an existing team within a workspace.
     *
     * <p>Requires the caller to hold organization {@code OWNER}/{@code ADMIN} role or team {@code LEAD}.
     *
     * @param organizationId the UUID of the organization
     * @param workspaceId the UUID of the workspace
     * @param teamId the UUID of the team
     * @param name the new team name (or null to retain existing)
     * @param description the new team description (or null to retain existing)
     * @param callerUserId the UUID of the caller
     * @return the updated {@link Team}
     * @throws TeamNotFoundException if the team is not found within the workspace and organization
     * @throws TeamAlreadyExistsException if the new name collides with an existing team in the workspace
     * @throws InvalidTeamOperationException if the team is archived
     */
    @Transactional
    public Team updateTeam(
            UUID organizationId,
            UUID workspaceId,
            UUID teamId,
            String name,
            String description,
            UUID callerUserId
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(teamId, "teamId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        Team team = teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId)
                .orElseThrow(() -> {
                    log.warn("Team update rejected: team [{}] not found in workspace [{}] and organization [{}]",
                            teamId, workspaceId, organizationId);
                    return new TeamNotFoundException(
                            "Team not found: " + teamId + " in workspace: " + workspaceId);
                });

        if (team.isArchived()) {
            log.warn("Team update rejected: team [{}] is archived", teamId);
            throw new InvalidTeamOperationException("Cannot update archived team [" + teamId + "]");
        }

        teamAuthorizationService.requireCanManageTeam(organizationId, team.getWorkspace(), teamId, callerUserId);

        if (name != null) {
            String trimmedName = name.trim();
            if (trimmedName.isEmpty()) {
                throw new IllegalArgumentException("Team name must not be blank");
            }
            if (trimmedName.length() > 100) {
                throw new IllegalArgumentException("Team name cannot exceed 100 characters");
            }

            if (!trimmedName.equalsIgnoreCase(team.getName())) {
                if (teamRepository.existsByWorkspaceIdAndOrganizationIdAndNameIgnoreCaseAndIdNot(
                        workspaceId, organizationId, trimmedName, teamId)) {
                    log.warn("Team update rejected: name [{}] already in use in workspace [{}]", trimmedName, workspaceId);
                    throw new TeamAlreadyExistsException(
                            "Team with name [" + trimmedName + "] already exists in workspace [" + workspaceId + "]");
                }
            }
            team.rename(trimmedName);
        }

        if (description != null) {
            String trimmedDesc = normalise(description);
            if (trimmedDesc != null && trimmedDesc.length() > 500) {
                throw new IllegalArgumentException("Team description cannot exceed 500 characters");
            }
            team.updateDescription(trimmedDesc);
        }

        Team updated = teamRepository.save(team);
        log.info("Updated team [{}] in workspace [{}] by user [{}]", teamId, workspaceId, callerUserId);

        return updated;
    }

    /**
     * Archives a team without physically deleting historical records or memberships.
     *
     * <p>Requires the caller to hold organization {@code OWNER}/{@code ADMIN} role or team {@code LEAD}.
     *
     * @param organizationId the UUID of the organization
     * @param workspaceId the UUID of the workspace
     * @param teamId the UUID of the team
     * @param callerUserId the UUID of the caller
     * @return the archived {@link Team}
     * @throws TeamNotFoundException if the team is not found
     * @throws InvalidTeamOperationException if the team is already archived
     */
    @Transactional
    public Team archiveTeam(UUID organizationId, UUID workspaceId, UUID teamId, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(teamId, "teamId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        Team team = teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId)
                .orElseThrow(() -> {
                    log.warn("Team archive rejected: team [{}] not found in workspace [{}]", teamId, workspaceId);
                    return new TeamNotFoundException(
                            "Team not found: " + teamId + " in workspace: " + workspaceId);
                });

        teamAuthorizationService.requireCanManageTeam(organizationId, team.getWorkspace(), teamId, callerUserId);

        team.archive();
        Team archived = teamRepository.save(team);

        log.info("Archived team [{}] in workspace [{}] by user [{}]", teamId, workspaceId, callerUserId);
        return archived;
    }

    // ── Team Queries ───────────────────────────────────────────────────────────

    /**
     * Retrieves a team by its ID within workspace and organization boundaries.
     *
     * @param organizationId the UUID of the organization
     * @param workspaceId the UUID of the workspace
     * @param teamId the UUID of the team
     * @param callerUserId the UUID of the caller
     * @return the retrieved {@link Team}
     * @throws TeamNotFoundException if the team is not found within the boundaries
     */
    @Transactional(readOnly = true)
    public Team getTeam(UUID organizationId, UUID workspaceId, UUID teamId, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(teamId, "teamId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        Team team = teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId)
                .orElseThrow(() -> {
                    log.warn("Team [{}] not found in workspace [{}] and organization [{}]", teamId, workspaceId, organizationId);
                    return new TeamNotFoundException(
                            "Team not found: " + teamId + " in workspace: " + workspaceId);
                });

        teamAuthorizationService.requireCanViewTeam(organizationId, team.getWorkspace(), callerUserId);
        return team;
    }

    /**
     * Retrieves a team by its ID within an organization boundary.
     *
     * @param organizationId the UUID of the organization
     * @param teamId the UUID of the team
     * @param callerUserId the UUID of the caller
     * @return the retrieved {@link Team}
     * @throws TeamNotFoundException if the team is not found within the organization
     */
    @Transactional(readOnly = true)
    public Team getTeam(UUID organizationId, UUID teamId, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(teamId, "teamId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        Team team = teamRepository.findByIdAndOrganizationId(teamId, organizationId)
                .orElseThrow(() -> {
                    log.warn("Team [{}] not found in organization [{}]", teamId, organizationId);
                    return new TeamNotFoundException("Team not found: " + teamId + " in organization: " + organizationId);
                });

        teamAuthorizationService.requireCanViewTeam(organizationId, team.getWorkspace(), callerUserId);
        return team;
    }

    /**
     * Lists teams belonging to a workspace, optionally filtered by status.
     *
     * @param organizationId the UUID of the organization
     * @param workspaceId the UUID of the workspace
     * @param status optional lifecycle status filter (null to return all)
     * @param callerUserId the UUID of the caller
     * @return list of matching {@link Team} entities
     * @throws WorkspaceNotFoundException if the workspace is not found
     */
    @Transactional(readOnly = true)
    public List<Team> listTeams(UUID organizationId, UUID workspaceId, TeamStatus status, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        Workspace workspace = workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId)
                .orElseThrow(() -> {
                    log.warn("List teams rejected: workspace [{}] not found in organization [{}]", workspaceId, organizationId);
                    return new WorkspaceNotFoundException(
                            "Workspace not found: " + workspaceId + " in organization: " + organizationId);
                });

        teamAuthorizationService.requireCanViewTeam(organizationId, workspace, callerUserId);

        if (status != null) {
            return teamRepository.findByWorkspaceIdAndOrganizationIdAndStatus(workspaceId, organizationId, status);
        } else {
            return teamRepository.findByWorkspaceIdAndOrganizationId(workspaceId, organizationId);
        }
    }

    // ── Team Membership Operations ─────────────────────────────────────────────

    /**
     * Adds an existing active organization member to a team with role {@link TeamRole#MEMBER}.
     *
     * <p>Requires the caller to hold organization {@code OWNER}/{@code ADMIN} role or team {@code LEAD}.
     *
     * @param organizationId the UUID of the organization
     * @param workspaceId the UUID of the workspace
     * @param teamId the UUID of the team
     * @param targetUserId the UUID of the user to add
     * @param callerUserId the UUID of the caller
     * @return the created {@link TeamMember}
     * @throws TeamNotFoundException if the team is not found
     * @throws InvalidTeamOperationException if the team is archived or target user is not active in the organization
     * @throws TeamMemberAlreadyExistsException if target user is already a member of the team
     * @throws OrganizationMemberNotFoundException if target user is not a member of the organization
     */
    @Transactional
    public TeamMember addMember(
            UUID organizationId,
            UUID workspaceId,
            UUID teamId,
            UUID targetUserId,
            UUID callerUserId
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(teamId, "teamId must not be null");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        Team team = teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId)
                .orElseThrow(() -> {
                    log.warn("Add member rejected: team [{}] not found in workspace [{}]", teamId, workspaceId);
                    return new TeamNotFoundException(
                            "Team not found: " + teamId + " in workspace: " + workspaceId);
                });

        if (team.isArchived()) {
            log.warn("Add member rejected: team [{}] is archived", teamId);
            throw new InvalidTeamOperationException("Cannot add member to archived team [" + teamId + "]");
        }

        teamAuthorizationService.requireCanManageTeam(organizationId, team.getWorkspace(), teamId, callerUserId);

        OrganizationMember orgMember = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId)
                .orElseThrow(() -> {
                    log.warn("Add member rejected: target user [{}] is not a member of organization [{}]",
                            targetUserId, organizationId);
                    return new OrganizationMemberNotFoundException(
                            "User [" + targetUserId + "] is not a member of organization [" + organizationId + "]");
                });

        if (orgMember.getStatus() != OrganizationMembershipStatus.ACTIVE) {
            log.warn("Add member rejected: target user [{}] has non-active status [{}] in organization [{}]",
                    targetUserId, orgMember.getStatus(), organizationId);
            throw new InvalidTeamOperationException(
                    "Cannot add non-active or suspended organization member [" + targetUserId + "] to team [" + teamId + "]");
        }

        if (teamMemberRepository.existsByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, targetUserId)) {
            log.warn("Add member rejected: user [{}] is already a member of team [{}]", targetUserId, teamId);
            throw new TeamMemberAlreadyExistsException(
                    "User [" + targetUserId + "] is already a member of team [" + teamId + "]");
        }

        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new OrganizationNotFoundException("User not found: " + targetUserId));

        TeamMember newMember = TeamMember.builder()
                .team(team)
                .user(targetUser)
                .role(TeamRole.MEMBER)
                .joinedAt(Instant.now())
                .build();

        TeamMember saved = teamMemberRepository.save(newMember);
        log.info("Added user [{}] to team [{}] by caller [{}]", targetUserId, teamId, callerUserId);

        return saved;
    }

    /**
     * Lists all members of a team.
     *
     * @param organizationId the UUID of the organization
     * @param workspaceId the UUID of the workspace
     * @param teamId the UUID of the team
     * @param callerUserId the UUID of the caller
     * @return list of team members
     * @throws TeamNotFoundException if the team is not found
     */
    @Transactional(readOnly = true)
    public List<TeamMember> listMembers(UUID organizationId, UUID workspaceId, UUID teamId, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(teamId, "teamId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        Team team = teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId)
                .orElseThrow(() -> {
                    log.warn("List members rejected: team [{}] not found in workspace [{}]", teamId, workspaceId);
                    return new TeamNotFoundException(
                            "Team not found: " + teamId + " in workspace: " + workspaceId);
                });

        teamAuthorizationService.requireCanViewTeam(organizationId, team.getWorkspace(), callerUserId);
        return teamMemberRepository.findByOrganizationIdAndTeamId(organizationId, teamId);
    }

    /**
     * Removes a member from a team.
     *
     * <p>Requires the caller to hold organization {@code OWNER}/{@code ADMIN} role or team {@code LEAD}.
     * Enforces that the current active team lead cannot be removed without prior leadership transfer.
     *
     * @param organizationId the UUID of the organization
     * @param workspaceId the UUID of the workspace
     * @param teamId the UUID of the team
     * @param targetUserId the UUID of the member to remove
     * @param callerUserId the UUID of the caller
     * @throws TeamNotFoundException if the team is not found
     * @throws InvalidTeamOperationException if the team is archived or target user is the active lead
     * @throws TeamMemberNotFoundException if the member record is not found
     */
    @Transactional
    public void removeMember(
            UUID organizationId,
            UUID workspaceId,
            UUID teamId,
            UUID targetUserId,
            UUID callerUserId
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(teamId, "teamId must not be null");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        Team team = teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId)
                .orElseThrow(() -> {
                    log.warn("Remove member rejected: team [{}] not found in workspace [{}]", teamId, workspaceId);
                    return new TeamNotFoundException(
                            "Team not found: " + teamId + " in workspace: " + workspaceId);
                });

        if (team.isArchived()) {
            log.warn("Remove member rejected: team [{}] is archived", teamId);
            throw new InvalidTeamOperationException("Cannot remove member from archived team [" + teamId + "]");
        }

        teamAuthorizationService.requireCanManageTeam(organizationId, team.getWorkspace(), teamId, callerUserId);

        TeamMember member = teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, targetUserId)
                .orElseThrow(() -> {
                    log.warn("Remove member rejected: member not found for user [{}] in team [{}]", targetUserId, teamId);
                    return new TeamMemberNotFoundException(
                            "Member not found for user [" + targetUserId + "] in team [" + teamId + "]");
                });

        if (member.isLead()) {
            log.warn("Remove member rejected: attempt to remove active lead [{}] from team [{}]", targetUserId, teamId);
            throw new InvalidTeamOperationException(
                    "Cannot remove active team lead from team [" + teamId + "]. Transfer team leadership first.");
        }

        teamMemberRepository.delete(member);
        log.info("Removed member [{}] from team [{}] by caller [{}]", targetUserId, teamId, callerUserId);
    }

    /**
     * Allows an active team member to leave their own team.
     *
     * <p>Enforces that the current team lead cannot leave without transferring leadership or archiving the team.
     * Suspended organization members are strictly denied.
     *
     * @param organizationId the UUID of the organization
     * @param workspaceId the UUID of the workspace
     * @param teamId the UUID of the team
     * @param callerUserId the UUID of the member leaving
     * @throws TeamNotFoundException if the team is not found
     * @throws InvalidTeamOperationException if the team is archived or caller is the active lead
     * @throws TeamMemberNotFoundException if caller is not a member of the team
     */
    @Transactional
    public void leaveTeam(UUID organizationId, UUID workspaceId, UUID teamId, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(teamId, "teamId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        organizationAuthorizationService.requireMember(organizationId, callerUserId);

        Team team = teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId)
                .orElseThrow(() -> {
                    log.warn("Leave team rejected: team [{}] not found in workspace [{}]", teamId, workspaceId);
                    return new TeamNotFoundException(
                            "Team not found: " + teamId + " in workspace: " + workspaceId);
                });

        if (team.isArchived()) {
            log.warn("Leave team rejected: team [{}] is archived", teamId);
            throw new InvalidTeamOperationException("Cannot leave archived team [" + teamId + "]");
        }

        teamAuthorizationService.requireCanViewTeam(organizationId, team.getWorkspace(), callerUserId);

        TeamMember member = teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, callerUserId)
                .orElseThrow(() -> {
                    log.warn("Leave team rejected: user [{}] is not a member of team [{}]", callerUserId, teamId);
                    return new TeamMemberNotFoundException(
                            "User [" + callerUserId + "] is not a member of team [" + teamId + "]");
                });

        if (member.isLead()) {
            log.warn("Leave team rejected: team lead [{}] cannot leave team [{}] without transfer", callerUserId, teamId);
            throw new InvalidTeamOperationException(
                    "Team lead cannot leave team [" + teamId + "] without transferring leadership or archiving the team");
        }

        teamMemberRepository.delete(member);
        log.info("User [{}] left team [{}]", callerUserId, teamId);
    }

    /**
     * Resolves the designated active team lead.
     *
     * @param organizationId the UUID of the organization
     * @param workspaceId the UUID of the workspace
     * @param teamId the UUID of the team
     * @param callerUserId the UUID of the caller
     * @return the {@link TeamMember} holding the {@code LEAD} role
     * @throws TeamNotFoundException if the team is not found
     * @throws TeamMemberNotFoundException if no lead is assigned to the team
     */
    @Transactional(readOnly = true)
    public TeamMember getTeamLead(UUID organizationId, UUID workspaceId, UUID teamId, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(teamId, "teamId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        Team team = teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId)
                .orElseThrow(() -> {
                    log.warn("Get team lead rejected: team [{}] not found in workspace [{}]", teamId, workspaceId);
                    return new TeamNotFoundException(
                            "Team not found: " + teamId + " in workspace: " + workspaceId);
                });

        teamAuthorizationService.requireCanViewTeam(organizationId, team.getWorkspace(), callerUserId);

        return teamMemberRepository.findLeadByOrganizationIdAndTeamId(organizationId, teamId)
                .orElseThrow(() -> {
                    log.error("Team lead invariant violated: team [{}] has no lead assigned", teamId);
                    return new TeamMemberNotFoundException("No lead found for team [" + teamId + "]");
                });
    }

    // ── Leadership Transfer Operations ─────────────────────────────────────────

    /**
     * Transfers leadership of an active team to an existing team member.
     *
     * <p>Requires caller to hold organization {@code OWNER}/{@code ADMIN} role or current team {@code LEAD}.
     * Demotes the current lead and flushes before promoting the successor and flushing, guaranteeing
     * compatibility with the PostgreSQL partial unique index.
     *
     * @param organizationId the UUID of the organization
     * @param workspaceId the UUID of the workspace
     * @param teamId the UUID of the team
     * @param targetUserId the UUID of the successor member
     * @param callerUserId the UUID of the caller
     * @return the updated successor {@link TeamMember} as new lead
     * @throws TeamNotFoundException if the team is not found
     * @throws InvalidTeamOperationException if the team is archived, target is already lead, or target is suspended
     * @throws TeamMemberNotFoundException if target is not a member of the team or current lead is missing
     * @throws OrganizationMemberNotFoundException if target is not a member of the organization
     */
    @Transactional
    public TeamMember transferLeadership(
            UUID organizationId,
            UUID workspaceId,
            UUID teamId,
            UUID targetUserId,
            UUID callerUserId
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(teamId, "teamId must not be null");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        Team team = teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId)
                .orElseThrow(() -> {
                    log.warn("Leadership transfer rejected: team [{}] not found in workspace [{}]", teamId, workspaceId);
                    return new TeamNotFoundException(
                            "Team not found: " + teamId + " in workspace: " + workspaceId);
                });

        if (team.isArchived()) {
            log.warn("Leadership transfer rejected: team [{}] is archived", teamId);
            throw new InvalidTeamOperationException("Cannot transfer leadership of archived team [" + teamId + "]");
        }

        teamAuthorizationService.requireCanManageTeam(organizationId, team.getWorkspace(), teamId, callerUserId);

        OrganizationMember targetOrgMember = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId)
                .orElseThrow(() -> {
                    log.warn("Leadership transfer rejected: target user [{}] is not a member of organization [{}]",
                            targetUserId, organizationId);
                    return new OrganizationMemberNotFoundException(
                            "Target user [" + targetUserId + "] is not a member of organization [" + organizationId + "]");
                });

        if (targetOrgMember.getStatus() != OrganizationMembershipStatus.ACTIVE) {
            log.warn("Leadership transfer rejected: target user [{}] is not active in organization [{}]",
                    targetUserId, organizationId);
            throw new InvalidTeamOperationException(
                    "Cannot transfer leadership to non-active or suspended organization member [" + targetUserId + "]");
        }

        TeamMember targetMember = teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, targetUserId)
                .orElseThrow(() -> {
                    log.warn("Leadership transfer rejected: target user [{}] is not a member of team [{}]",
                            targetUserId, teamId);
                    return new TeamMemberNotFoundException(
                            "Target user [" + targetUserId + "] is not a member of team [" + teamId + "]");
                });

        TeamMember currentLead = teamMemberRepository.findLeadByOrganizationIdAndTeamId(organizationId, teamId)
                .orElseThrow(() -> {
                    log.error("Team lead invariant violated: team [{}] has no lead assigned", teamId);
                    return new TeamMemberNotFoundException("No active lead found for team [" + teamId + "]");
                });

        if (targetMember.getId().equals(currentLead.getId())) {
            log.warn("Leadership transfer rejected: target user [{}] is already the lead of team [{}]", targetUserId, teamId);
            throw new InvalidTeamOperationException(
                    "Target user [" + targetUserId + "] is already the active lead of team [" + teamId + "]");
        }

        // Flush demotion first to satisfy PostgreSQL partial unique index uk_team_members_team_lead
        currentLead.demoteToMember();
        teamMemberRepository.saveAndFlush(currentLead);

        // Promote successor and flush
        targetMember.promoteToLead();
        TeamMember updatedLead = teamMemberRepository.saveAndFlush(targetMember);

        log.info("Transferred leadership of team [{}] from user [{}] to user [{}] by caller [{}]",
                teamId, currentLead.getUser().getId(), targetUserId, callerUserId);

        return updatedLead;
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    /**
     * Trims a string and returns {@code null} if empty.
     */
    private String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

