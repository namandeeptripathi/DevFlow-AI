package com.devflow.team.service;

import com.devflow.organization.exception.OrganizationAccessDeniedException;
import com.devflow.organization.exception.OrganizationMembershipRequiredException;
import com.devflow.organization.service.OrganizationAuthorizationService;
import com.devflow.team.domain.Team;
import com.devflow.team.domain.TeamMember;
import com.devflow.team.domain.TeamRole;
import com.devflow.team.exception.TeamAccessDeniedException;
import com.devflow.team.repository.TeamMemberRepository;
import com.devflow.workspace.domain.Workspace;
import com.devflow.workspace.domain.WorkspaceVisibility;
import com.devflow.workspace.exception.WorkspaceAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TeamAuthorizationService Unit Tests")
class TeamAuthorizationServiceTest {

    @Mock
    private OrganizationAuthorizationService organizationAuthorizationService;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    private TeamAuthorizationService teamAuthorizationService;

    private UUID organizationId;
    private UUID teamId;
    private UUID userId;
    private Workspace publicWorkspace;
    private Workspace privateWorkspace;

    @BeforeEach
    void setUp() {
        teamAuthorizationService = new TeamAuthorizationService(
                organizationAuthorizationService,
                teamMemberRepository
        );

        organizationId = UUID.randomUUID();
        teamId = UUID.randomUUID();
        userId = UUID.randomUUID();

        publicWorkspace = Workspace.builder()
                .id(UUID.randomUUID())
                .name("Public Engineering")
                .visibility(WorkspaceVisibility.PUBLIC)
                .build();

        privateWorkspace = Workspace.builder()
                .id(UUID.randomUUID())
                .name("Private Executive")
                .visibility(WorkspaceVisibility.PRIVATE)
                .build();
    }

    @Nested
    @DisplayName("isTeamLead & isTeamMember Queries")
    class LeadAndMemberQueries {

        @Test
        @DisplayName("isTeamLead returns true when member has LEAD role")
        void isTeamLead_trueWhenLead() {
            TeamMember leadMember = TeamMember.builder().role(TeamRole.LEAD).build();
            when(teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, userId))
                    .thenReturn(Optional.of(leadMember));

            assertThat(teamAuthorizationService.isTeamLead(organizationId, teamId, userId)).isTrue();
        }

        @Test
        @DisplayName("isTeamLead returns false when member has MEMBER role")
        void isTeamLead_falseWhenMember() {
            TeamMember regularMember = TeamMember.builder().role(TeamRole.MEMBER).build();
            when(teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, userId))
                    .thenReturn(Optional.of(regularMember));

            assertThat(teamAuthorizationService.isTeamLead(organizationId, teamId, userId)).isFalse();
        }

        @Test
        @DisplayName("isTeamLead returns false when user is not in team")
        void isTeamLead_falseWhenNotFound() {
            when(teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, userId))
                    .thenReturn(Optional.empty());

            assertThat(teamAuthorizationService.isTeamLead(organizationId, teamId, userId)).isFalse();
        }

        @Test
        @DisplayName("isTeamLead returns false on null inputs")
        void isTeamLead_falseOnNulls() {
            assertThat(teamAuthorizationService.isTeamLead(null, teamId, userId)).isFalse();
            assertThat(teamAuthorizationService.isTeamLead(organizationId, null, userId)).isFalse();
            assertThat(teamAuthorizationService.isTeamLead(organizationId, teamId, null)).isFalse();
        }

        @Test
        @DisplayName("isTeamMember returns true when membership exists")
        void isTeamMember_trueWhenExists() {
            when(teamMemberRepository.existsByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, userId))
                    .thenReturn(true);

            assertThat(teamAuthorizationService.isTeamMember(organizationId, teamId, userId)).isTrue();
        }

        @Test
        @DisplayName("isTeamMember returns false when membership does not exist")
        void isTeamMember_falseWhenAbsent() {
            when(teamMemberRepository.existsByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, userId))
                    .thenReturn(false);

            assertThat(teamAuthorizationService.isTeamMember(organizationId, teamId, userId)).isFalse();
        }
    }

    @Nested
    @DisplayName("canViewTeam & requireCanViewTeam")
    class ViewTeamAuthorization {

        @Test
        @DisplayName("canViewTeam returns true for active member in PUBLIC workspace")
        void canViewTeam_publicWorkspace_activeMember_returnsTrue() {
            when(organizationAuthorizationService.isMember(organizationId, userId)).thenReturn(true);

            assertThat(teamAuthorizationService.canViewTeam(organizationId, publicWorkspace, userId)).isTrue();
        }

        @Test
        @DisplayName("canViewTeam returns false if user is not active organization member")
        void canViewTeam_notMember_returnsFalse() {
            when(organizationAuthorizationService.isMember(organizationId, userId)).thenReturn(false);

            assertThat(teamAuthorizationService.canViewTeam(organizationId, publicWorkspace, userId)).isFalse();
        }

        @Test
        @DisplayName("canViewTeam returns true for PRIVATE workspace when user is admin or owner")
        void canViewTeam_privateWorkspace_adminOrOwner_returnsTrue() {
            when(organizationAuthorizationService.isMember(organizationId, userId)).thenReturn(true);
            when(organizationAuthorizationService.isAdminOrOwner(organizationId, userId)).thenReturn(true);

            assertThat(teamAuthorizationService.canViewTeam(organizationId, privateWorkspace, userId)).isTrue();
        }

        @Test
        @DisplayName("canViewTeam returns false for PRIVATE workspace when user is not admin or owner")
        void canViewTeam_privateWorkspace_nonAdmin_returnsFalse() {
            when(organizationAuthorizationService.isMember(organizationId, userId)).thenReturn(true);
            when(organizationAuthorizationService.isAdminOrOwner(organizationId, userId)).thenReturn(false);

            assertThat(teamAuthorizationService.canViewTeam(organizationId, privateWorkspace, userId)).isFalse();
        }

        @Test
        @DisplayName("requireCanViewTeam succeeds for active member in public workspace")
        void requireCanViewTeam_succeedsForPublicWorkspace() {
            doNothing().when(organizationAuthorizationService).requireMember(organizationId, userId);

            assertThatCode(() -> teamAuthorizationService.requireCanViewTeam(organizationId, publicWorkspace, userId))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("requireCanViewTeam throws WorkspaceAccessDeniedException for private workspace when not admin/owner")
        void requireCanViewTeam_privateWorkspace_throwsException() {
            doNothing().when(organizationAuthorizationService).requireMember(organizationId, userId);
            when(organizationAuthorizationService.isAdminOrOwner(organizationId, userId)).thenReturn(false);

            assertThatThrownBy(() -> teamAuthorizationService.requireCanViewTeam(organizationId, privateWorkspace, userId))
                    .isInstanceOf(WorkspaceAccessDeniedException.class)
                    .hasMessageContaining("ADMIN or OWNER role required to access private workspace");
        }

        @Test
        @DisplayName("requireCanViewTeam propagates OrganizationMembershipRequiredException for suspended member")
        void requireCanViewTeam_suspendedMember_throwsException() {
            doThrow(new OrganizationMembershipRequiredException("Active membership required"))
                    .when(organizationAuthorizationService).requireMember(organizationId, userId);

            assertThatThrownBy(() -> teamAuthorizationService.requireCanViewTeam(organizationId, publicWorkspace, userId))
                    .isInstanceOf(OrganizationMembershipRequiredException.class);
        }
    }

    @Nested
    @DisplayName("requireCanCreateTeam")
    class CreateTeamAuthorization {

        @Test
        @DisplayName("succeeds when caller is organization ADMIN or OWNER")
        void requireCanCreateTeam_succeedsForAdminOrOwner() {
            doNothing().when(organizationAuthorizationService).requireAdminOrOwner(organizationId, userId);

            assertThatCode(() -> teamAuthorizationService.requireCanCreateTeam(organizationId, publicWorkspace, userId))
                    .doesNotThrowAnyException();

            verify(organizationAuthorizationService).requireAdminOrOwner(organizationId, userId);
        }

        @Test
        @DisplayName("propagates OrganizationAccessDeniedException when caller is regular member")
        void requireCanCreateTeam_failsForRegularMember() {
            doThrow(new OrganizationAccessDeniedException("Admin or Owner role required"))
                    .when(organizationAuthorizationService).requireAdminOrOwner(organizationId, userId);

            assertThatThrownBy(() -> teamAuthorizationService.requireCanCreateTeam(organizationId, publicWorkspace, userId))
                    .isInstanceOf(OrganizationAccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("requireCanManageTeam")
    class ManageTeamAuthorization {

        @Test
        @DisplayName("succeeds when caller is organization ADMIN or OWNER")
        void requireCanManageTeam_succeedsForAdminOrOwner() {
            doNothing().when(organizationAuthorizationService).requireMember(organizationId, userId);
            when(organizationAuthorizationService.isAdminOrOwner(organizationId, userId)).thenReturn(true);

            assertThatCode(() -> teamAuthorizationService.requireCanManageTeam(organizationId, publicWorkspace, teamId, userId))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("succeeds when caller is team LEAD in accessible workspace")
        void requireCanManageTeam_succeedsForTeamLead() {
            doNothing().when(organizationAuthorizationService).requireMember(organizationId, userId);
            when(organizationAuthorizationService.isAdminOrOwner(organizationId, userId)).thenReturn(false);

            TeamMember leadMember = TeamMember.builder().role(TeamRole.LEAD).build();
            when(teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, userId))
                    .thenReturn(Optional.of(leadMember));

            assertThatCode(() -> teamAuthorizationService.requireCanManageTeam(organizationId, publicWorkspace, teamId, userId))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws WorkspaceAccessDeniedException when team is in private workspace and caller is not admin/owner")
        void requireCanManageTeam_privateWorkspace_failsEvenIfLead() {
            doNothing().when(organizationAuthorizationService).requireMember(organizationId, userId);
            when(organizationAuthorizationService.isAdminOrOwner(organizationId, userId)).thenReturn(false);

            assertThatThrownBy(() -> teamAuthorizationService.requireCanManageTeam(organizationId, privateWorkspace, teamId, userId))
                    .isInstanceOf(WorkspaceAccessDeniedException.class)
                    .hasMessageContaining("ADMIN or OWNER role required");
        }

        @Test
        @DisplayName("throws TeamAccessDeniedException when caller is regular team MEMBER")
        void requireCanManageTeam_failsForRegularMember() {
            doNothing().when(organizationAuthorizationService).requireMember(organizationId, userId);
            when(organizationAuthorizationService.isAdminOrOwner(organizationId, userId)).thenReturn(false);

            TeamMember member = TeamMember.builder().role(TeamRole.MEMBER).build();
            when(teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, userId))
                    .thenReturn(Optional.of(member));

            assertThatThrownBy(() -> teamAuthorizationService.requireCanManageTeam(organizationId, publicWorkspace, teamId, userId))
                    .isInstanceOf(TeamAccessDeniedException.class)
                    .hasMessageContaining("Organization ADMIN, OWNER, or Team LEAD role required");
        }

        @Test
        @DisplayName("throws TeamAccessDeniedException when caller is not in team")
        void requireCanManageTeam_failsWhenNotInTeam() {
            doNothing().when(organizationAuthorizationService).requireMember(organizationId, userId);
            when(organizationAuthorizationService.isAdminOrOwner(organizationId, userId)).thenReturn(false);

            when(teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(organizationId, teamId, userId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> teamAuthorizationService.requireCanManageTeam(organizationId, publicWorkspace, teamId, userId))
                    .isInstanceOf(TeamAccessDeniedException.class)
                    .hasMessageContaining("Organization ADMIN, OWNER, or Team LEAD role required");
        }
    }
}

