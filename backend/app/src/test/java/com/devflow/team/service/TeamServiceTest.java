package com.devflow.team.service;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.exception.OrganizationMemberNotFoundException;
import com.devflow.organization.exception.OrganizationMembershipRequiredException;
import com.devflow.organization.exception.OrganizationNotFoundException;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.service.OrganizationAuthorizationService;
import com.devflow.team.domain.Team;
import com.devflow.team.domain.TeamMember;
import com.devflow.team.domain.TeamRole;
import com.devflow.team.domain.TeamStatus;
import com.devflow.team.exception.InvalidTeamOperationException;
import com.devflow.team.exception.TeamAccessDeniedException;
import com.devflow.team.exception.TeamAlreadyExistsException;
import com.devflow.team.exception.TeamMemberAlreadyExistsException;
import com.devflow.team.exception.TeamMemberNotFoundException;
import com.devflow.team.exception.TeamNotFoundException;
import com.devflow.team.repository.TeamMemberRepository;
import com.devflow.team.repository.TeamRepository;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import com.devflow.workspace.domain.Workspace;
import com.devflow.workspace.domain.WorkspaceVisibility;
import com.devflow.workspace.exception.WorkspaceAccessDeniedException;
import com.devflow.workspace.exception.WorkspaceNotFoundException;
import com.devflow.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TeamService Unit Tests")
class TeamServiceTest {

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrganizationMemberRepository organizationMemberRepository;

    @Mock
    private OrganizationAuthorizationService organizationAuthorizationService;

    @Mock
    private TeamAuthorizationService teamAuthorizationService;

    private TeamService teamService;

    private UUID organizationId;
    private UUID workspaceId;
    private UUID teamId;
    private UUID callerUserId;
    private UUID targetUserId;

    private Organization organization;
    private Workspace workspace;
    private User creatorUser;
    private User targetUser;
    private Team activeTeam;
    private TeamMember leadMember;
    private TeamMember regularMember;

    @BeforeEach
    void setUp() {
        teamService = new TeamService(
                teamRepository,
                teamMemberRepository,
                workspaceRepository,
                userRepository,
                organizationMemberRepository,
                organizationAuthorizationService,
                teamAuthorizationService
        );

        organizationId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        teamId = UUID.randomUUID();
        callerUserId = UUID.randomUUID();
        targetUserId = UUID.randomUUID();

        organization = Organization.builder()
                .id(organizationId)
                .name("Acme Corp")
                .slug("acme-corp")
                .build();

        workspace = Workspace.builder()
                .id(workspaceId)
                .organization(organization)
                .name("Engineering")
                .visibility(WorkspaceVisibility.PUBLIC)
                .build();

        creatorUser = User.builder()
                .id(callerUserId)
                .email("creator@devflow.com")
                .username("creator")
                .accountStatus(AccountStatus.ACTIVE)
                .build();

        targetUser = User.builder()
                .id(targetUserId)
                .email("target@devflow.com")
                .username("target")
                .accountStatus(AccountStatus.ACTIVE)
                .build();

        activeTeam = Team.builder()
                .id(teamId)
                .workspace(workspace)
                .organization(organization)
                .name("Core Platform")
                .description("Platform Team")
                .status(TeamStatus.ACTIVE)
                .build();

        leadMember = TeamMember.builder()
                .id(UUID.randomUUID())
                .team(activeTeam)
                .user(creatorUser)
                .role(TeamRole.LEAD)
                .joinedAt(Instant.now())
                .build();

        regularMember = TeamMember.builder()
                .id(UUID.randomUUID())
                .team(activeTeam)
                .user(targetUser)
                .role(TeamRole.MEMBER)
                .joinedAt(Instant.now())
                .build();
    }

    // ── Create Team ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Create Team")
    class CreateTeamTests {

        @Test
        @DisplayName("successfully creates team and assigns creator as LEAD")
        void createTeam_success() {
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.of(workspace));
            doNothing().when(teamAuthorizationService).requireCanCreateTeam(organizationId, workspace, callerUserId);
            when(teamRepository.existsByWorkspaceIdAndOrganizationIdAndNameIgnoreCase(workspaceId, organizationId, "Core Platform"))
                    .thenReturn(false);
            when(userRepository.findById(callerUserId)).thenReturn(Optional.of(creatorUser));
            when(teamRepository.save(any(Team.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Team created = teamService.createTeam(organizationId, workspaceId, "  Core Platform  ", "Description", callerUserId);

            assertThat(created).isNotNull();
            assertThat(created.getName()).isEqualTo("Core Platform");
            assertThat(created.getStatus()).isEqualTo(TeamStatus.ACTIVE);
            assertThat(created.getWorkspace()).isEqualTo(workspace);

            ArgumentCaptor<TeamMember> memberCaptor = ArgumentCaptor.forClass(TeamMember.class);
            verify(teamMemberRepository).save(memberCaptor.capture());
            TeamMember initialLead = memberCaptor.getValue();
            assertThat(initialLead.getUser()).isEqualTo(creatorUser);
            assertThat(initialLead.getRole()).isEqualTo(TeamRole.LEAD);
            assertThat(initialLead.isLead()).isTrue();
        }

        @Test
        @DisplayName("throws IllegalArgumentException for blank team name")
        void createTeam_blankName_throwsException() {
            assertThatThrownBy(() -> teamService.createTeam(organizationId, workspaceId, "   ", "Desc", callerUserId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Team name must not be blank");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when name exceeds 100 characters")
        void createTeam_nameTooLong_throwsException() {
            String longName = "A".repeat(101);
            assertThatThrownBy(() -> teamService.createTeam(organizationId, workspaceId, longName, "Desc", callerUserId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Team name cannot exceed 100 characters");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when description exceeds 500 characters")
        void createTeam_descriptionTooLong_throwsException() {
            String longDesc = "D".repeat(501);
            assertThatThrownBy(() -> teamService.createTeam(organizationId, workspaceId, "Alpha", longDesc, callerUserId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Team description cannot exceed 500 characters");
        }

        @Test
        @DisplayName("throws WorkspaceNotFoundException when workspace is missing in organization")
        void createTeam_workspaceNotFound_throwsException() {
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> teamService.createTeam(organizationId, workspaceId, "Alpha", "Desc", callerUserId))
                    .isInstanceOf(WorkspaceNotFoundException.class);
        }

        @Test
        @DisplayName("throws TeamAlreadyExistsException on duplicate team name in workspace")
        void createTeam_duplicateName_throwsException() {
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.of(workspace));
            doNothing().when(teamAuthorizationService).requireCanCreateTeam(organizationId, workspace, callerUserId);
            when(teamRepository.existsByWorkspaceIdAndOrganizationIdAndNameIgnoreCase(workspaceId, organizationId, "Core Platform"))
                    .thenReturn(true);

            assertThatThrownBy(() -> teamService.createTeam(organizationId, workspaceId, "Core Platform", "Desc", callerUserId))
                    .isInstanceOf(TeamAlreadyExistsException.class)
                    .hasMessageContaining("already exists in workspace");
        }
    }

    // ── Get & List Teams ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Get & List Teams")
    class GetAndListTeamsTests {

        @Test
        @DisplayName("getTeam with workspaceId retrieves team when authorized")
        void getTeam_withWorkspace_success() {
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanViewTeam(organizationId, workspace, callerUserId);

            Team result = teamService.getTeam(organizationId, workspaceId, teamId, callerUserId);

            assertThat(result).isEqualTo(activeTeam);
        }

        @Test
        @DisplayName("getTeam with workspaceId throws TeamNotFoundException when team missing")
        void getTeam_withWorkspace_notFound_throwsException() {
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> teamService.getTeam(organizationId, workspaceId, teamId, callerUserId))
                    .isInstanceOf(TeamNotFoundException.class);
        }

        @Test
        @DisplayName("getTeam with organizationId retrieves team when authorized")
        void getTeam_withOrg_success() {
            when(teamRepository.findByIdAndOrganizationId(teamId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanViewTeam(organizationId, workspace, callerUserId);

            Team result = teamService.getTeam(organizationId, teamId, callerUserId);

            assertThat(result).isEqualTo(activeTeam);
        }

        @Test
        @DisplayName("listTeams returns all teams in workspace when status is null")
        void listTeams_allStatus() {
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.of(workspace));
            doNothing().when(teamAuthorizationService).requireCanViewTeam(organizationId, workspace, callerUserId);
            when(teamRepository.findByWorkspaceIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(List.of(activeTeam));

            List<Team> teams = teamService.listTeams(organizationId, workspaceId, null, callerUserId);

            assertThat(teams).hasSize(1).contains(activeTeam);
        }

        @Test
        @DisplayName("listTeams returns filtered teams when status is provided")
        void listTeams_filteredByStatus() {
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.of(workspace));
            doNothing().when(teamAuthorizationService).requireCanViewTeam(organizationId, workspace, callerUserId);
            when(teamRepository.findByWorkspaceIdAndOrganizationIdAndStatus(workspaceId, organizationId, TeamStatus.ACTIVE))
                    .thenReturn(List.of(activeTeam));

            List<Team> teams = teamService.listTeams(organizationId, workspaceId, TeamStatus.ACTIVE, callerUserId);

            assertThat(teams).hasSize(1).contains(activeTeam);
        }

        @Test
        @DisplayName("listTeams propagates WorkspaceAccessDeniedException on private workspace when unauthorized")
        void listTeams_privateWorkspace_unauthorized_throwsException() {
            when(workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId))
                    .thenReturn(Optional.of(workspace));
            doThrow(new WorkspaceAccessDeniedException("Private workspace access denied"))
                    .when(teamAuthorizationService).requireCanViewTeam(organizationId, workspace, callerUserId);

            assertThatThrownBy(() -> teamService.listTeams(organizationId, workspaceId, null, callerUserId))
                    .isInstanceOf(WorkspaceAccessDeniedException.class);
        }
    }

    // ── Update & Archive Team ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Update & Archive Team")
    class UpdateAndArchiveTeamTests {

        @Test
        @DisplayName("updateTeam updates name and description successfully")
        void updateTeam_success() {
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanManageTeam(organizationId, workspace, teamId, callerUserId);
            when(teamRepository.existsByWorkspaceIdAndOrganizationIdAndNameIgnoreCaseAndIdNot(
                    workspaceId, organizationId, "Updated Platform", teamId)).thenReturn(false);
            when(teamRepository.save(any(Team.class))).thenAnswer(i -> i.getArgument(0));

            Team updated = teamService.updateTeam(
                    organizationId, workspaceId, teamId, "Updated Platform", "New Description", callerUserId);

            assertThat(updated.getName()).isEqualTo("Updated Platform");
            assertThat(updated.getDescription()).isEqualTo("New Description");
        }

        @Test
        @DisplayName("updateTeam throws InvalidTeamOperationException when team is archived")
        void updateTeam_archived_throwsException() {
            activeTeam.setStatus(TeamStatus.ARCHIVED);
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));

            assertThatThrownBy(() -> teamService.updateTeam(
                    organizationId, workspaceId, teamId, "New Name", "New Desc", callerUserId))
                    .isInstanceOf(InvalidTeamOperationException.class)
                    .hasMessageContaining("Cannot update archived team");
        }

        @Test
        @DisplayName("updateTeam throws TeamAlreadyExistsException on name collision")
        void updateTeam_nameCollision_throwsException() {
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanManageTeam(organizationId, workspace, teamId, callerUserId);
            when(teamRepository.existsByWorkspaceIdAndOrganizationIdAndNameIgnoreCaseAndIdNot(
                    workspaceId, organizationId, "Existing Team", teamId)).thenReturn(true);

            assertThatThrownBy(() -> teamService.updateTeam(
                    organizationId, workspaceId, teamId, "Existing Team", null, callerUserId))
                    .isInstanceOf(TeamAlreadyExistsException.class)
                    .hasMessageContaining("already exists in workspace");
        }

        @Test
        @DisplayName("archiveTeam transitions team to ARCHIVED status")
        void archiveTeam_success() {
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanManageTeam(organizationId, workspace, teamId, callerUserId);
            when(teamRepository.save(any(Team.class))).thenAnswer(i -> i.getArgument(0));

            Team archived = teamService.archiveTeam(organizationId, workspaceId, teamId, callerUserId);

            assertThat(archived.getStatus()).isEqualTo(TeamStatus.ARCHIVED);
            assertThat(archived.isArchived()).isTrue();
        }

        @Test
        @DisplayName("archiveTeam throws InvalidTeamOperationException when already archived")
        void archiveTeam_alreadyArchived_throwsException() {
            activeTeam.setStatus(TeamStatus.ARCHIVED);
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanManageTeam(organizationId, workspace, teamId, callerUserId);

            assertThatThrownBy(() -> teamService.archiveTeam(organizationId, workspaceId, teamId, callerUserId))
                    .isInstanceOf(InvalidTeamOperationException.class);
        }
    }

    // ── Member Operations ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Member Operations")
    class MemberOperationsTests {

        @Test
        @DisplayName("addMember adds active organization member to team")
        void addMember_success() {
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanManageTeam(organizationId, workspace, teamId, callerUserId);

            OrganizationMember orgMember = OrganizationMember.builder()
                    .organization(organization)
                    .user(targetUser)
                    .role(OrganizationRole.DEVELOPER)
                    .status(OrganizationMembershipStatus.ACTIVE)
                    .build();
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(orgMember));
            when(teamMemberRepository.existsByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, targetUserId))
                    .thenReturn(false);
            when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
            when(teamMemberRepository.save(any(TeamMember.class))).thenAnswer(i -> i.getArgument(0));

            TeamMember added = teamService.addMember(organizationId, workspaceId, teamId, targetUserId, callerUserId);

            assertThat(added.getUser()).isEqualTo(targetUser);
            assertThat(added.getRole()).isEqualTo(TeamRole.MEMBER);
            assertThat(added.getTeam()).isEqualTo(activeTeam);
        }

        @Test
        @DisplayName("addMember rejects suspended organization member")
        void addMember_suspendedUser_throwsException() {
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanManageTeam(organizationId, workspace, teamId, callerUserId);

            OrganizationMember suspendedOrgMember = OrganizationMember.builder()
                    .organization(organization)
                    .user(targetUser)
                    .status(OrganizationMembershipStatus.SUSPENDED)
                    .build();
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(suspendedOrgMember));

            assertThatThrownBy(() -> teamService.addMember(organizationId, workspaceId, teamId, targetUserId, callerUserId))
                    .isInstanceOf(InvalidTeamOperationException.class)
                    .hasMessageContaining("Cannot add non-active or suspended organization member");
        }

        @Test
        @DisplayName("addMember throws TeamMemberAlreadyExistsException if already in team")
        void addMember_alreadyMember_throwsException() {
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanManageTeam(organizationId, workspace, teamId, callerUserId);

            OrganizationMember orgMember = OrganizationMember.builder()
                    .organization(organization)
                    .user(targetUser)
                    .status(OrganizationMembershipStatus.ACTIVE)
                    .build();
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(orgMember));
            when(teamMemberRepository.existsByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, targetUserId))
                    .thenReturn(true);

            assertThatThrownBy(() -> teamService.addMember(organizationId, workspaceId, teamId, targetUserId, callerUserId))
                    .isInstanceOf(TeamMemberAlreadyExistsException.class)
                    .hasMessageContaining("already a member of team");
        }

        @Test
        @DisplayName("removeMember deletes regular team member")
        void removeMember_success() {
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanManageTeam(organizationId, workspace, teamId, callerUserId);
            when(teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, targetUserId))
                    .thenReturn(Optional.of(regularMember));

            teamService.removeMember(organizationId, workspaceId, teamId, targetUserId, callerUserId);

            verify(teamMemberRepository).delete(regularMember);
        }

        @Test
        @DisplayName("removeMember rejects removing the active team lead")
        void removeMember_lead_throwsException() {
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanManageTeam(organizationId, workspace, teamId, callerUserId);
            when(teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, callerUserId))
                    .thenReturn(Optional.of(leadMember));

            assertThatThrownBy(() -> teamService.removeMember(organizationId, workspaceId, teamId, callerUserId, callerUserId))
                    .isInstanceOf(InvalidTeamOperationException.class)
                    .hasMessageContaining("Cannot remove active team lead");

            verify(teamMemberRepository, never()).delete(any());
        }

        @Test
        @DisplayName("leaveTeam allows regular member to leave")
        void leaveTeam_regularMember_success() {
            doNothing().when(organizationAuthorizationService).requireMember(organizationId, targetUserId);
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanViewTeam(organizationId, workspace, targetUserId);
            when(teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, targetUserId))
                    .thenReturn(Optional.of(regularMember));

            teamService.leaveTeam(organizationId, workspaceId, teamId, targetUserId);

            verify(teamMemberRepository).delete(regularMember);
        }

        @Test
        @DisplayName("leaveTeam rejects active lead leaving without leadership transfer")
        void leaveTeam_lead_throwsException() {
            doNothing().when(organizationAuthorizationService).requireMember(organizationId, callerUserId);
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanViewTeam(organizationId, workspace, callerUserId);
            when(teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, callerUserId))
                    .thenReturn(Optional.of(leadMember));

            assertThatThrownBy(() -> teamService.leaveTeam(organizationId, workspaceId, teamId, callerUserId))
                    .isInstanceOf(InvalidTeamOperationException.class)
                    .hasMessageContaining("Team lead cannot leave team");

            verify(teamMemberRepository, never()).delete(any());
        }

        @Test
        @DisplayName("getTeamLead returns designated lead member")
        void getTeamLead_success() {
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanViewTeam(organizationId, workspace, callerUserId);
            when(teamMemberRepository.findLeadByOrganizationIdAndTeamId(organizationId, teamId))
                    .thenReturn(Optional.of(leadMember));

            TeamMember lead = teamService.getTeamLead(organizationId, workspaceId, teamId, callerUserId);

            assertThat(lead).isEqualTo(leadMember);
            assertThat(lead.isLead()).isTrue();
        }
    }

    // ── Leadership Transfer ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Leadership Transfer")
    class LeadershipTransferTests {

        @Test
        @DisplayName("transferLeadership demotes predecessor and promotes successor")
        void transferLeadership_success() {
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanManageTeam(organizationId, workspace, teamId, callerUserId);

            OrganizationMember targetOrgMember = OrganizationMember.builder()
                    .organization(organization)
                    .user(targetUser)
                    .status(OrganizationMembershipStatus.ACTIVE)
                    .build();
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(targetOrgMember));

            when(teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, targetUserId))
                    .thenReturn(Optional.of(regularMember));
            when(teamMemberRepository.findLeadByOrganizationIdAndTeamId(organizationId, teamId))
                    .thenReturn(Optional.of(leadMember));

            when(teamMemberRepository.saveAndFlush(any(TeamMember.class))).thenAnswer(i -> i.getArgument(0));

            TeamMember newLead = teamService.transferLeadership(
                    organizationId, workspaceId, teamId, targetUserId, callerUserId);

            assertThat(newLead.isLead()).isTrue();
            assertThat(newLead.getRole()).isEqualTo(TeamRole.LEAD);
            assertThat(leadMember.isLead()).isFalse();
            assertThat(leadMember.getRole()).isEqualTo(TeamRole.MEMBER);

            verify(teamMemberRepository).saveAndFlush(leadMember);
            verify(teamMemberRepository).saveAndFlush(regularMember);
        }

        @Test
        @DisplayName("transferLeadership rejects transferring to current lead")
        void transferLeadership_toCurrentLead_throwsException() {
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanManageTeam(organizationId, workspace, teamId, callerUserId);

            OrganizationMember targetOrgMember = OrganizationMember.builder()
                    .organization(organization)
                    .user(creatorUser)
                    .status(OrganizationMembershipStatus.ACTIVE)
                    .build();
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, callerUserId))
                    .thenReturn(Optional.of(targetOrgMember));

            when(teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, callerUserId))
                    .thenReturn(Optional.of(leadMember));
            when(teamMemberRepository.findLeadByOrganizationIdAndTeamId(organizationId, teamId))
                    .thenReturn(Optional.of(leadMember));

            assertThatThrownBy(() -> teamService.transferLeadership(
                    organizationId, workspaceId, teamId, callerUserId, callerUserId))
                    .isInstanceOf(InvalidTeamOperationException.class)
                    .hasMessageContaining("already the active lead");
        }

        @Test
        @DisplayName("transferLeadership rejects suspended organization member")
        void transferLeadership_suspendedTarget_throwsException() {
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanManageTeam(organizationId, workspace, teamId, callerUserId);

            OrganizationMember targetOrgMember = OrganizationMember.builder()
                    .organization(organization)
                    .user(targetUser)
                    .status(OrganizationMembershipStatus.SUSPENDED)
                    .build();
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(targetOrgMember));

            assertThatThrownBy(() -> teamService.transferLeadership(
                    organizationId, workspaceId, teamId, targetUserId, callerUserId))
                    .isInstanceOf(InvalidTeamOperationException.class)
                    .hasMessageContaining("non-active or suspended organization member");
        }

        @Test
        @DisplayName("transferLeadership throws TeamMemberNotFoundException when target not in team")
        void transferLeadership_targetNotInTeam_throwsException() {
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));
            doNothing().when(teamAuthorizationService).requireCanManageTeam(organizationId, workspace, teamId, callerUserId);

            OrganizationMember targetOrgMember = OrganizationMember.builder()
                    .organization(organization)
                    .user(targetUser)
                    .status(OrganizationMembershipStatus.ACTIVE)
                    .build();
            when(organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUserId))
                    .thenReturn(Optional.of(targetOrgMember));

            when(teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, targetUserId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> teamService.transferLeadership(
                    organizationId, workspaceId, teamId, targetUserId, callerUserId))
                    .isInstanceOf(TeamMemberNotFoundException.class)
                    .hasMessageContaining("not a member of team");
        }

        @Test
        @DisplayName("transferLeadership rejects operation on archived team")
        void transferLeadership_archivedTeam_throwsException() {
            activeTeam.setStatus(TeamStatus.ARCHIVED);
            when(teamRepository.findByIdAndWorkspaceIdAndOrganizationId(teamId, workspaceId, organizationId))
                    .thenReturn(Optional.of(activeTeam));

            assertThatThrownBy(() -> teamService.transferLeadership(
                    organizationId, workspaceId, teamId, targetUserId, callerUserId))
                    .isInstanceOf(InvalidTeamOperationException.class)
                    .hasMessageContaining("Cannot transfer leadership of archived team");
        }
    }
}

