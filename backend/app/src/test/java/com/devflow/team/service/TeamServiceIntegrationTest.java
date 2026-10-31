package com.devflow.team.service;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.exception.OrganizationAccessDeniedException;
import com.devflow.organization.exception.OrganizationMembershipRequiredException;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.team.domain.Team;
import com.devflow.team.domain.TeamMember;
import com.devflow.team.domain.TeamRole;
import com.devflow.team.domain.TeamStatus;
import com.devflow.team.exception.InvalidTeamOperationException;
import com.devflow.team.exception.TeamAccessDeniedException;
import com.devflow.team.exception.TeamAlreadyExistsException;
import com.devflow.team.exception.TeamMemberAlreadyExistsException;
import com.devflow.team.exception.TeamMemberNotFoundException;
import com.devflow.team.repository.TeamMemberRepository;
import com.devflow.team.repository.TeamRepository;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import com.devflow.workspace.domain.Workspace;
import com.devflow.workspace.domain.WorkspaceVisibility;
import com.devflow.workspace.exception.WorkspaceAccessDeniedException;
import com.devflow.workspace.repository.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("TeamService Integration Tests")
class TeamServiceIntegrationTest {

    @Autowired
    private TeamService teamService;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private User owner;
    private User dev1;
    private User dev2;
    private Organization org;
    private Workspace publicWorkspace;
    private Workspace privateWorkspace;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.builder()
                .email("owner-" + UUID.randomUUID() + "@devflow.com")
                .username("owner_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        dev1 = userRepository.save(User.builder()
                .email("dev1-" + UUID.randomUUID() + "@devflow.com")
                .username("dev1_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        dev2 = userRepository.save(User.builder()
                .email("dev2-" + UUID.randomUUID() + "@devflow.com")
                .username("dev2_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        org = organizationRepository.save(Organization.builder()
                .name("Test Corp")
                .slug("corp-" + UUID.randomUUID().toString().substring(0, 8))
                .owner(owner)
                .build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(org)
                .user(owner)
                .role(OrganizationRole.OWNER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(org)
                .user(dev1)
                .role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(org)
                .user(dev2)
                .role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        publicWorkspace = workspaceRepository.save(Workspace.builder()
                .name("General Engineering")
                .organization(org)
                .visibility(WorkspaceVisibility.PUBLIC)
                .build());

        privateWorkspace = workspaceRepository.save(Workspace.builder()
                .name("Security Engineering")
                .organization(org)
                .visibility(WorkspaceVisibility.PRIVATE)
                .build());

        entityManager.flush();
        entityManager.clear();
    }

    @Nested
    @DisplayName("End-to-End Team Lifecycle")
    class TeamLifecycle {

        @Test
        @DisplayName("createTeam atomically saves team and sets creator as LEAD in DB")
        void createTeam_persistsTeamAndLead() {
            Team team = teamService.createTeam(
                    org.getId(), publicWorkspace.getId(), "Platform Team", "Core Services", owner.getId());
            entityManager.flush();
            entityManager.clear();

            Team found = teamRepository.findById(team.getId()).orElseThrow();
            assertThat(found.getName()).isEqualTo("Platform Team");
            assertThat(found.getStatus()).isEqualTo(TeamStatus.ACTIVE);

            TeamMember lead = teamMemberRepository.findLeadByOrganizationIdAndTeamId(org.getId(), team.getId()).orElseThrow();
            assertThat(lead.getUser().getId()).isEqualTo(owner.getId());
            assertThat(lead.getRole()).isEqualTo(TeamRole.LEAD);
        }

        @Test
        @DisplayName("createTeam fails when developer tries to create team")
        void createTeam_developerUnauthorized_throwsException() {
            assertThatThrownBy(() -> teamService.createTeam(
                    org.getId(), publicWorkspace.getId(), "Dev Team", "Desc", dev1.getId()))
                    .isInstanceOf(OrganizationAccessDeniedException.class);
        }

        @Test
        @DisplayName("createTeam enforces case-insensitive uniqueness in database")
        void createTeam_duplicateName_throwsException() {
            teamService.createTeam(org.getId(), publicWorkspace.getId(), "Alpha", "Desc", owner.getId());
            entityManager.flush();

            assertThatThrownBy(() -> teamService.createTeam(
                    org.getId(), publicWorkspace.getId(), "alpha", "Desc", owner.getId()))
                    .isInstanceOf(TeamAlreadyExistsException.class);
        }

        @Test
        @DisplayName("updateTeam updates name and description and verifies database state")
        void updateTeam_persistsChanges() {
            Team team = teamService.createTeam(org.getId(), publicWorkspace.getId(), "Beta", "Old Desc", owner.getId());
            entityManager.flush();
            entityManager.clear();

            Team updated = teamService.updateTeam(
                    org.getId(), publicWorkspace.getId(), team.getId(), "Beta Updated", "New Desc", owner.getId());
            entityManager.flush();
            entityManager.clear();

            Team found = teamRepository.findById(team.getId()).orElseThrow();
            assertThat(found.getName()).isEqualTo("Beta Updated");
            assertThat(found.getDescription()).isEqualTo("New Desc");
        }

        @Test
        @DisplayName("archiveTeam sets status to ARCHIVED and rejects subsequent updates")
        void archiveTeam_persistsAndGuards() {
            Team team = teamService.createTeam(org.getId(), publicWorkspace.getId(), "Gamma", "Desc", owner.getId());
            entityManager.flush();
            entityManager.clear();

            teamService.archiveTeam(org.getId(), publicWorkspace.getId(), team.getId(), owner.getId());
            entityManager.flush();
            entityManager.clear();

            Team found = teamRepository.findById(team.getId()).orElseThrow();
            assertThat(found.isArchived()).isTrue();

            assertThatThrownBy(() -> teamService.updateTeam(
                    org.getId(), publicWorkspace.getId(), team.getId(), "New Gamma", "Desc", owner.getId()))
                    .isInstanceOf(InvalidTeamOperationException.class);
        }
    }

    @Nested
    @DisplayName("End-to-End Membership Lifecycle")
    class MembershipLifecycle {

        @Test
        @DisplayName("addMember, removeMember, and listMembers persist and query database correctly")
        void membershipOperations_persistCorrectly() {
            Team team = teamService.createTeam(org.getId(), publicWorkspace.getId(), "Delta", "Desc", owner.getId());
            entityManager.flush();
            entityManager.clear();

            // Add dev1
            TeamMember addedDev1 = teamService.addMember(org.getId(), publicWorkspace.getId(), team.getId(), dev1.getId(), owner.getId());
            entityManager.flush();
            entityManager.clear();

            List<TeamMember> members = teamService.listMembers(org.getId(), publicWorkspace.getId(), team.getId(), owner.getId());
            assertThat(members).hasSize(2)
                    .extracting(m -> m.getUser().getId())
                    .containsExactlyInAnyOrder(owner.getId(), dev1.getId());

            // Remove dev1
            teamService.removeMember(org.getId(), publicWorkspace.getId(), team.getId(), dev1.getId(), owner.getId());
            entityManager.flush();
            entityManager.clear();

            List<TeamMember> membersAfter = teamService.listMembers(org.getId(), publicWorkspace.getId(), team.getId(), owner.getId());
            assertThat(membersAfter).hasSize(1)
                    .extracting(m -> m.getUser().getId())
                    .containsExactly(owner.getId());
        }

        @Test
        @DisplayName("leaveTeam allows regular member to leave but blocks team lead")
        void leaveTeam_checks() {
            Team team = teamService.createTeam(org.getId(), publicWorkspace.getId(), "Epsilon", "Desc", owner.getId());
            teamService.addMember(org.getId(), publicWorkspace.getId(), team.getId(), dev1.getId(), owner.getId());
            entityManager.flush();
            entityManager.clear();

            // Lead leaving fails
            assertThatThrownBy(() -> teamService.leaveTeam(org.getId(), publicWorkspace.getId(), team.getId(), owner.getId()))
                    .isInstanceOf(InvalidTeamOperationException.class)
                    .hasMessageContaining("Team lead cannot leave team");

            // Regular member leaving succeeds
            teamService.leaveTeam(org.getId(), publicWorkspace.getId(), team.getId(), dev1.getId());
            entityManager.flush();
            entityManager.clear();

            assertThat(teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(org.getId(), team.getId(), dev1.getId()))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Leadership Transfer & Partial Unique Index Compatibility")
    class LeadershipTransferIntegration {

        @Test
        @DisplayName("transferLeadership successfully demotes predecessor, promotes successor in DB, respecting partial index")
        void transferLeadership_databaseVerified() {
            Team team = teamService.createTeam(org.getId(), publicWorkspace.getId(), "Zeta", "Desc", owner.getId());
            teamService.addMember(org.getId(), publicWorkspace.getId(), team.getId(), dev1.getId(), owner.getId());
            entityManager.flush();
            entityManager.clear();

            TeamMember updatedLead = teamService.transferLeadership(
                    org.getId(), publicWorkspace.getId(), team.getId(), dev1.getId(), owner.getId());
            entityManager.flush();
            entityManager.clear();

            TeamMember newLeadFromDb = teamMemberRepository.findLeadByOrganizationIdAndTeamId(org.getId(), team.getId()).orElseThrow();
            assertThat(newLeadFromDb.getUser().getId()).isEqualTo(dev1.getId());
            assertThat(newLeadFromDb.getRole()).isEqualTo(TeamRole.LEAD);

            TeamMember oldLeadFromDb = teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(
                    org.getId(), team.getId(), owner.getId()).orElseThrow();
            assertThat(oldLeadFromDb.getRole()).isEqualTo(TeamRole.MEMBER);
            assertThat(oldLeadFromDb.isLead()).isFalse();
        }

        @Test
        @DisplayName("suspended member lifecycle: blocked from operations, admin can transfer leadership, un-suspension restores safely")
        void suspendedMember_lifecycleVerified() {
            Team team = teamService.createTeam(org.getId(), publicWorkspace.getId(), "Theta", "Desc", owner.getId());
            teamService.addMember(org.getId(), publicWorkspace.getId(), team.getId(), dev1.getId(), owner.getId());
            // Transfer leadership to dev1
            teamService.transferLeadership(org.getId(), publicWorkspace.getId(), team.getId(), dev1.getId(), owner.getId());
            entityManager.flush();
            entityManager.clear();

            // Suspend dev1 in organization
            OrganizationMember dev1OrgMember = organizationMemberRepository.findByOrganizationIdAndUserId(org.getId(), dev1.getId()).orElseThrow();
            dev1OrgMember.setStatus(OrganizationMembershipStatus.SUSPENDED);
            organizationMemberRepository.save(dev1OrgMember);
            entityManager.flush();
            entityManager.clear();

            // Suspended lead cannot update team
            assertThatThrownBy(() -> teamService.updateTeam(
                    org.getId(), publicWorkspace.getId(), team.getId(), "New Name", "Desc", dev1.getId()))
                    .isInstanceOf(OrganizationMembershipRequiredException.class);

            // Organization OWNER transfers leadership back to owner
            teamService.transferLeadership(org.getId(), publicWorkspace.getId(), team.getId(), owner.getId(), owner.getId());
            entityManager.flush();
            entityManager.clear();

            TeamMember currentLead = teamMemberRepository.findLeadByOrganizationIdAndTeamId(org.getId(), team.getId()).orElseThrow();
            assertThat(currentLead.getUser().getId()).isEqualTo(owner.getId());

            // Unsuspend dev1
            OrganizationMember suspendedMember = organizationMemberRepository.findByOrganizationIdAndUserId(org.getId(), dev1.getId()).orElseThrow();
            suspendedMember.setStatus(OrganizationMembershipStatus.ACTIVE);
            organizationMemberRepository.save(suspendedMember);
            entityManager.flush();
            entityManager.clear();

            // dev1 is regular member in good standing
            TeamMember dev1TeamMember = teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(org.getId(), team.getId(), dev1.getId()).orElseThrow();
            assertThat(dev1TeamMember.getRole()).isEqualTo(TeamRole.MEMBER);
        }

        @Test
        @DisplayName("private workspace blocks non-admin/owner even if they are team lead")
        void privateWorkspace_blocksNonAdminOwnerLead() {
            // Owner creates team in private workspace and assigns dev1 as lead
            Team team = teamService.createTeam(org.getId(), privateWorkspace.getId(), "Secret Team", "Desc", owner.getId());
            teamService.addMember(org.getId(), privateWorkspace.getId(), team.getId(), dev1.getId(), owner.getId());
            teamService.transferLeadership(org.getId(), privateWorkspace.getId(), team.getId(), dev1.getId(), owner.getId());
            entityManager.flush();
            entityManager.clear();

            // dev1 attempts to view/manage team in private workspace -> blocked because private workspace rules take precedence
            assertThatThrownBy(() -> teamService.getTeam(org.getId(), privateWorkspace.getId(), team.getId(), dev1.getId()))
                    .isInstanceOf(WorkspaceAccessDeniedException.class);

            assertThatThrownBy(() -> teamService.updateTeam(org.getId(), privateWorkspace.getId(), team.getId(), "Renamed", "Desc", dev1.getId()))
                    .isInstanceOf(WorkspaceAccessDeniedException.class);
        }
    }
}

