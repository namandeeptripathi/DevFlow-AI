package com.devflow.authorization;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.exception.OrganizationAccessDeniedException;
import com.devflow.organization.exception.OrganizationMembershipRequiredException;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.organization.service.OrganizationAuthorizationService;
import com.devflow.team.domain.Team;
import com.devflow.team.domain.TeamMember;
import com.devflow.team.domain.TeamRole;
import com.devflow.team.domain.TeamStatus;
import com.devflow.team.exception.TeamAccessDeniedException;
import com.devflow.team.repository.TeamMemberRepository;
import com.devflow.team.repository.TeamRepository;
import com.devflow.team.service.TeamAuthorizationService;
import com.devflow.team.service.TeamService;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 5 integration test suite verifying that team authorization decisions
 * correctly integrate with the centralized authorization engine and organization services.
 *
 * <h2>Coverage Invariants</h2>
 * <ul>
 *   <li>Team LEAD holds management permissions over their own team in public workspaces.</li>
 *   <li>Team MEMBER has read access but is forbidden from mutating team settings or memberships.</li>
 *   <li>Organization OWNER and ADMIN hold supervisory permissions over all teams.</li>
 *   <li>Private workspace visibility takes strict precedence over team-level lead permissions.</li>
 *   <li>Suspended organization membership denies all team access and operations immediately.</li>
 *   <li>End-to-end regression coverage of team lifecycle commands under centralized authorization.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Stage 5: Team Centralized Authorization Integration Tests")
class TeamCentralizedAuthorizationIntegrationTest {

    @Autowired
    private TeamAuthorizationService teamAuthorizationService;

    @Autowired
    private TeamService teamService;

    @Autowired
    private OrganizationAuthorizationService organizationAuthorizationService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private User orgOwner;
    private User orgAdmin;
    private User devLead;
    private User devMember;
    private User devOther;
    private User suspendedUser;

    private Organization org;
    private Workspace publicWorkspace;
    private Workspace privateWorkspace;
    private Team publicTeam;

    @BeforeEach
    void setUp() {
        orgOwner = createUser("org-owner");
        orgAdmin = createUser("org-admin");
        devLead = createUser("dev-lead");
        devMember = createUser("dev-member");
        devOther = createUser("dev-other");
        suspendedUser = createUser("dev-suspended");

        org = organizationRepository.save(Organization.builder()
                .name("Acme Engineering")
                .slug("acme-" + UUID.randomUUID())
                .owner(orgOwner)
                .build());

        createOrgMembership(org, orgOwner, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE);
        createOrgMembership(org, orgAdmin, OrganizationRole.ADMIN, OrganizationMembershipStatus.ACTIVE);
        createOrgMembership(org, devLead, OrganizationRole.DEVELOPER, OrganizationMembershipStatus.ACTIVE);
        createOrgMembership(org, devMember, OrganizationRole.DEVELOPER, OrganizationMembershipStatus.ACTIVE);
        createOrgMembership(org, devOther, OrganizationRole.DEVELOPER, OrganizationMembershipStatus.ACTIVE);
        createOrgMembership(org, suspendedUser, OrganizationRole.DEVELOPER, OrganizationMembershipStatus.SUSPENDED);

        publicWorkspace = workspaceRepository.save(Workspace.builder()
                .name("Public Engineering Workspace")
                .organization(org)
                .visibility(WorkspaceVisibility.PUBLIC)
                .build());

        privateWorkspace = workspaceRepository.save(Workspace.builder()
                .name("Restricted Core Ops")
                .organization(org)
                .visibility(WorkspaceVisibility.PRIVATE)
                .build());

        // Create initial team in public workspace with devLead as lead and devMember as member
        publicTeam = teamRepository.save(Team.builder()
                .name("Infrastructure Core")
                .description("Core services & reliability")
                .workspace(publicWorkspace)
                .organization(org)
                .status(TeamStatus.ACTIVE)
                .build());

        teamMemberRepository.save(TeamMember.builder()
                .team(publicTeam)
                .user(devLead)
                .role(TeamRole.LEAD)
                .joinedAt(java.time.Instant.now())
                .build());

        teamMemberRepository.save(TeamMember.builder()
                .team(publicTeam)
                .user(devMember)
                .role(TeamRole.MEMBER)
                .joinedAt(java.time.Instant.now())
                .build());

        entityManager.flush();
        entityManager.clear();
    }

    private User createUser(String prefix) {
        return userRepository.save(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@devflow.io")
                .username(prefix + "_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed_pw")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());
    }

    private OrganizationMember createOrgMembership(
            Organization organization, User user, OrganizationRole role, OrganizationMembershipStatus status) {
        return organizationMemberRepository.save(OrganizationMember.builder()
                .organization(organization)
                .user(user)
                .role(role)
                .status(status)
                .build());
    }

    // ── 1. Team LEAD and MEMBER Behavior ──────────────────────────────────────

    @Nested
    @DisplayName("Team LEAD vs MEMBER Access Control")
    class LeadAndMemberBehavior {

        @Test
        @DisplayName("Team LEAD is verified and authorized to manage their team in public workspace")
        void teamLead_authorizedToManageTeam() {
            assertThat(teamAuthorizationService.isTeamLead(org.getId(), publicTeam.getId(), devLead.getId()))
                    .isTrue();
            assertThat(teamAuthorizationService.isTeamMember(org.getId(), publicTeam.getId(), devLead.getId()))
                    .isTrue();
            assertThat(teamAuthorizationService.canViewTeam(org.getId(), publicWorkspace, devLead.getId()))
                    .isTrue();

            assertThatCode(() -> teamAuthorizationService.requireCanManageTeam(
                    org.getId(), publicWorkspace, publicTeam.getId(), devLead.getId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Team MEMBER can view team but is denied management privileges")
        void teamMember_canView_butDeniedManagement() {
            assertThat(teamAuthorizationService.isTeamLead(org.getId(), publicTeam.getId(), devMember.getId()))
                    .isFalse();
            assertThat(teamAuthorizationService.isTeamMember(org.getId(), publicTeam.getId(), devMember.getId()))
                    .isTrue();
            assertThat(teamAuthorizationService.canViewTeam(org.getId(), publicWorkspace, devMember.getId()))
                    .isTrue();

            assertThatCode(() -> teamAuthorizationService.requireCanViewTeam(
                    org.getId(), publicWorkspace, devMember.getId()))
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> teamAuthorizationService.requireCanManageTeam(
                    org.getId(), publicWorkspace, publicTeam.getId(), devMember.getId()))
                    .isInstanceOf(TeamAccessDeniedException.class)
                    .hasMessageContaining("Team LEAD role required");
        }

        @Test
        @DisplayName("Non-team member in organization can view public workspace team but cannot manage")
        void nonTeamMember_canViewPublicWorkspaceTeam_butCannotManage() {
            assertThat(teamAuthorizationService.isTeamMember(org.getId(), publicTeam.getId(), devOther.getId()))
                    .isFalse();
            assertThat(teamAuthorizationService.canViewTeam(org.getId(), publicWorkspace, devOther.getId()))
                    .isTrue();

            assertThatThrownBy(() -> teamAuthorizationService.requireCanManageTeam(
                    org.getId(), publicWorkspace, publicTeam.getId(), devOther.getId()))
                    .isInstanceOf(TeamAccessDeniedException.class);
        }
    }

    // ── 2. Organization Admin and Owner Supervision ───────────────────────────

    @Nested
    @DisplayName("Organization ADMIN and OWNER Supervision")
    class AdminAndOwnerBehavior {

        @Test
        @DisplayName("Organization OWNER can manage any team even when not an explicit team member")
        void orgOwner_canManageAnyTeam() {
            assertThat(teamAuthorizationService.isTeamMember(org.getId(), publicTeam.getId(), orgOwner.getId()))
                    .isFalse();

            assertThatCode(() -> teamAuthorizationService.requireCanManageTeam(
                    org.getId(), publicWorkspace, publicTeam.getId(), orgOwner.getId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Organization ADMIN can manage any team even when not an explicit team member")
        void orgAdmin_canManageAnyTeam() {
            assertThat(teamAuthorizationService.isTeamMember(org.getId(), publicTeam.getId(), orgAdmin.getId()))
                    .isFalse();

            assertThatCode(() -> teamAuthorizationService.requireCanManageTeam(
                    org.getId(), publicWorkspace, publicTeam.getId(), orgAdmin.getId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Only Organization ADMIN or OWNER can create teams; regular DEVELOPER is denied")
        void teamCreation_adminOrOwnerRequired() {
            assertThatCode(() -> teamAuthorizationService.requireCanCreateTeam(
                    org.getId(), publicWorkspace, orgOwner.getId()))
                    .doesNotThrowAnyException();

            assertThatCode(() -> teamAuthorizationService.requireCanCreateTeam(
                    org.getId(), publicWorkspace, orgAdmin.getId()))
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> teamAuthorizationService.requireCanCreateTeam(
                    org.getId(), publicWorkspace, devLead.getId()))
                    .isInstanceOf(OrganizationAccessDeniedException.class);
        }
    }

    // ── 3. Private Workspace Visibility Restrictions ──────────────────────────

    @Nested
    @DisplayName("Private Workspace Restrictions")
    class PrivateWorkspaceRestrictions {

        private Team privateTeam;

        @BeforeEach
        void createPrivateTeam() {
            privateTeam = teamRepository.save(Team.builder()
                    .name("SecOps Internal")
                    .description("Confidential security ops")
                    .workspace(privateWorkspace)
                    .organization(org)
                    .status(TeamStatus.ACTIVE)
                    .build());

            teamMemberRepository.save(TeamMember.builder()
                    .team(privateTeam)
                    .user(devLead)
                    .role(TeamRole.LEAD)
                    .joinedAt(java.time.Instant.now())
                    .build());

            entityManager.flush();
            entityManager.clear();
        }

        @Test
        @DisplayName("Private workspace restricts access to Organization ADMIN and OWNER")
        void privateWorkspace_accessibleByAdminAndOwner() {
            assertThat(teamAuthorizationService.canViewTeam(org.getId(), privateWorkspace, orgOwner.getId()))
                    .isTrue();
            assertThat(teamAuthorizationService.canViewTeam(org.getId(), privateWorkspace, orgAdmin.getId()))
                    .isTrue();

            assertThatCode(() -> teamAuthorizationService.requireCanViewTeam(
                    org.getId(), privateWorkspace, orgOwner.getId()))
                    .doesNotThrowAnyException();

            assertThatCode(() -> teamAuthorizationService.requireCanManageTeam(
                    org.getId(), privateWorkspace, privateTeam.getId(), orgAdmin.getId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Team LEAD who is only an organization DEVELOPER is denied access to private workspace team")
        void privateWorkspace_deniesDeveloperEvenIfTeamLead() {
            // Private workspace visibility rule takes precedence over team-level lead privileges
            assertThat(teamAuthorizationService.canViewTeam(org.getId(), privateWorkspace, devLead.getId()))
                    .isFalse();

            assertThatThrownBy(() -> teamAuthorizationService.requireCanViewTeam(
                    org.getId(), privateWorkspace, devLead.getId()))
                    .isInstanceOf(WorkspaceAccessDeniedException.class)
                    .hasMessageContaining("ADMIN or OWNER role required");

            assertThatThrownBy(() -> teamAuthorizationService.requireCanManageTeam(
                    org.getId(), privateWorkspace, privateTeam.getId(), devLead.getId()))
                    .isInstanceOf(WorkspaceAccessDeniedException.class);
        }

        @Test
        @DisplayName("Regular organization DEVELOPER is denied viewing private workspace team")
        void privateWorkspace_deniesRegularDeveloper() {
            assertThat(teamAuthorizationService.canViewTeam(org.getId(), privateWorkspace, devMember.getId()))
                    .isFalse();

            assertThatThrownBy(() -> teamAuthorizationService.requireCanViewTeam(
                    org.getId(), privateWorkspace, devMember.getId()))
                    .isInstanceOf(WorkspaceAccessDeniedException.class);
        }
    }

    // ── 4. Suspended Organization Membership ──────────────────────────────────

    @Nested
    @DisplayName("Suspended Organization Membership")
    class SuspendedMembershipEnforcement {

        @Test
        @DisplayName("Suspended member is denied all team viewing and operations immediately")
        void suspendedMember_deniedAllTeamOperations() {
            // Suspended user cannot view team
            assertThat(teamAuthorizationService.canViewTeam(org.getId(), publicWorkspace, suspendedUser.getId()))
                    .isFalse();

            assertThatThrownBy(() -> teamAuthorizationService.requireCanViewTeam(
                    org.getId(), publicWorkspace, suspendedUser.getId()))
                    .isInstanceOf(OrganizationMembershipRequiredException.class);

            assertThatThrownBy(() -> teamAuthorizationService.requireCanManageTeam(
                    org.getId(), publicWorkspace, publicTeam.getId(), suspendedUser.getId()))
                    .isInstanceOf(OrganizationMembershipRequiredException.class);

            assertThatThrownBy(() -> teamAuthorizationService.requireCanCreateTeam(
                    org.getId(), publicWorkspace, suspendedUser.getId()))
                    .isInstanceOf(OrganizationAccessDeniedException.class);
        }

        @Test
        @DisplayName("Suspended member cannot perform team mutation via TeamService")
        void suspendedMember_teamServiceRejection() {
            assertThatThrownBy(() -> teamService.updateTeam(
                    org.getId(), publicWorkspace.getId(), publicTeam.getId(),
                    "Attempted Name", "Attempted Desc", suspendedUser.getId()))
                    .isInstanceOf(OrganizationMembershipRequiredException.class);
        }
    }

    // ── 5. End-to-End Regression Coverage for Team Operations ─────────────────

    @Nested
    @DisplayName("End-to-End Team Operations Regression")
    class TeamOperationsRegression {

        @Test
        @DisplayName("Team lead can update team description and name")
        void updateTeam_teamLead_success() {
            Team updated = teamService.updateTeam(
                    org.getId(), publicWorkspace.getId(), publicTeam.getId(),
                    "Infrastructure Core 2.0", "New description", devLead.getId());

            entityManager.flush();
            entityManager.clear();

            Team found = teamRepository.findById(updated.getId()).orElseThrow();
            assertThat(found.getName()).isEqualTo("Infrastructure Core 2.0");
            assertThat(found.getDescription()).isEqualTo("New description");
        }

        @Test
        @DisplayName("Team lead can add a new active organization member to the team")
        void addTeamMember_teamLead_success() {
            TeamMember newMember = teamService.addMember(
                    org.getId(), publicWorkspace.getId(), publicTeam.getId(),
                    devOther.getId(), devLead.getId());

            entityManager.flush();
            entityManager.clear();

            assertThat(newMember.getUser().getId()).isEqualTo(devOther.getId());
            assertThat(newMember.getRole()).isEqualTo(TeamRole.MEMBER);
            assertThat(teamMemberRepository.existsByOrganizationIdAndTeamIdAndUserId(
                    org.getId(), publicTeam.getId(), devOther.getId())).isTrue();
        }

        @Test
        @DisplayName("Team lead can transfer leadership to another team member atomically")
        void transferLeadership_teamLead_success() {
            teamService.transferLeadership(
                    org.getId(), publicWorkspace.getId(), publicTeam.getId(),
                    devMember.getId(), devLead.getId());

            entityManager.flush();
            entityManager.clear();

            TeamMember oldLead = teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(
                    org.getId(), publicTeam.getId(), devLead.getId()).orElseThrow();
            TeamMember newLead = teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(
                    org.getId(), publicTeam.getId(), devMember.getId()).orElseThrow();

            assertThat(oldLead.getRole()).isEqualTo(TeamRole.MEMBER);
            assertThat(newLead.getRole()).isEqualTo(TeamRole.LEAD);
        }

        @Test
        @DisplayName("Team lead can archive team")
        void archiveTeam_teamLead_success() {
            teamService.archiveTeam(org.getId(), publicWorkspace.getId(), publicTeam.getId(), devLead.getId());

            entityManager.flush();
            entityManager.clear();

            Team archived = teamRepository.findById(publicTeam.getId()).orElseThrow();
            assertThat(archived.getStatus()).isEqualTo(TeamStatus.ARCHIVED);
        }

        @Test
        @DisplayName("Team member cannot add another member to the team")
        void addTeamMember_regularMember_denied() {
            assertThatThrownBy(() -> teamService.addMember(
                    org.getId(), publicWorkspace.getId(), publicTeam.getId(),
                    devOther.getId(), devMember.getId()))
                    .isInstanceOf(TeamAccessDeniedException.class);
        }
    }
}
