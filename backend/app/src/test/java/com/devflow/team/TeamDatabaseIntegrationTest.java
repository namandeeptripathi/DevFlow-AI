package com.devflow.team;

import com.devflow.config.JpaAuditingConfiguration;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.organization.service.OrganizationAuthorizationService;
import com.devflow.organization.service.OrganizationMembershipService;
import com.devflow.team.domain.Team;
import com.devflow.team.domain.TeamMember;
import com.devflow.team.domain.TeamRole;
import com.devflow.team.domain.TeamStatus;
import com.devflow.team.exception.InvalidTeamOperationException;
import com.devflow.team.repository.TeamMemberRepository;
import com.devflow.team.repository.TeamRepository;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import com.devflow.workspace.domain.Workspace;
import com.devflow.workspace.domain.WorkspaceVisibility;
import com.devflow.workspace.repository.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(JpaAuditingConfiguration.class)
@ActiveProfiles("test")
@DisplayName("Team Database Constraints & Schema Integration")
class TeamDatabaseIntegrationTest {

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private EntityManager entityManager;

    private User owner;
    private Organization orgA;
    private Organization orgB;
    private Workspace workspaceA1;
    private Workspace workspaceA2;
    private User testUser1;
    private User testUser2;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.builder()
                .email("owner-" + UUID.randomUUID() + "@devflow.com")
                .username("owner_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        testUser1 = userRepository.save(User.builder()
                .email("user1-" + UUID.randomUUID() + "@devflow.com")
                .username("user1_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        testUser2 = userRepository.save(User.builder()
                .email("user2-" + UUID.randomUUID() + "@devflow.com")
                .username("user2_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        orgA = organizationRepository.save(Organization.builder()
                .name("Organization Alpha")
                .slug("org-alpha-" + UUID.randomUUID().toString().substring(0, 8))
                .owner(owner)
                .build());

        orgB = organizationRepository.save(Organization.builder()
                .name("Organization Beta")
                .slug("org-beta-" + UUID.randomUUID().toString().substring(0, 8))
                .owner(owner)
                .build());

        workspaceA1 = workspaceRepository.save(Workspace.builder()
                .name("Engineering")
                .organization(orgA)
                .visibility(WorkspaceVisibility.PUBLIC)
                .build());

        workspaceA2 = workspaceRepository.save(Workspace.builder()
                .name("Product")
                .organization(orgA)
                .visibility(WorkspaceVisibility.PUBLIC)
                .build());

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("case-insensitive uniqueness rejects duplicate team name in same workspace")
    void caseInsensitiveNameUniquenessEnforcedWithinWorkspace() {
        Team team1 = Team.builder()
                .name("Platform Infrastructure")
                .workspace(workspaceA1)
                .organization(orgA)
                .status(TeamStatus.ACTIVE)
                .build();
        teamRepository.save(team1);
        entityManager.flush();

        Team team2 = Team.builder()
                .name("platform infrastructure")
                .workspace(workspaceA1)
                .organization(orgA)
                .status(TeamStatus.ACTIVE)
                .build();

        assertThatThrownBy(() -> {
            teamRepository.save(team2);
            entityManager.flush();
        })
        .isInstanceOfAny(DataIntegrityViolationException.class, ConstraintViolationException.class)
        .hasMessageContaining("uk_teams_workspace_name_ci");
    }

    @Test
    @DisplayName("same team name is permitted in distinct workspaces")
    void sameTeamNamePermittedInDifferentWorkspaces() {
        Team team1 = Team.builder()
                .name("Platform Infrastructure")
                .workspace(workspaceA1)
                .organization(orgA)
                .status(TeamStatus.ACTIVE)
                .build();
        teamRepository.save(team1);
        entityManager.flush();

        Team team2 = Team.builder()
                .name("platform infrastructure")
                .workspace(workspaceA2)
                .organization(orgA)
                .status(TeamStatus.ACTIVE)
                .build();

        Team saved2 = teamRepository.save(team2);
        entityManager.flush();

        assertThat(saved2.getId()).isNotNull();
    }

    @Test
    @DisplayName("composite tenant integrity rejects team referencing mismatched organization and workspace")
    void compositeTenantIntegrityRejectsMismatchedOrgAndWorkspace() {
        // workspaceA1 belongs to orgA. Attempt to persist team referencing workspaceA1 but orgB
        Team mismatchedTeam = Team.builder()
                .name("Security Team")
                .workspace(workspaceA1)
                .organization(orgB)
                .status(TeamStatus.ACTIVE)
                .build();

        assertThatThrownBy(() -> {
            teamRepository.save(mismatchedTeam);
            entityManager.flush();
        })
        .isInstanceOfAny(DataIntegrityViolationException.class, ConstraintViolationException.class)
        .hasMessageContaining("fk_teams_workspace");
    }

    @Test
    @DisplayName("duplicate membership constraint rejects adding same user twice to same team")
    void duplicateMembershipConstraintRejectsDuplicateUserInTeam() {
        Team team = teamRepository.save(Team.builder()
                .name("Frontend Team")
                .workspace(workspaceA1)
                .organization(orgA)
                .status(TeamStatus.ACTIVE)
                .build());
        entityManager.flush();

        TeamMember member1 = TeamMember.builder()
                .team(team)
                .user(testUser1)
                .role(TeamRole.MEMBER)
                .joinedAt(Instant.now())
                .build();
        teamMemberRepository.save(member1);
        entityManager.flush();

        TeamMember member2 = TeamMember.builder()
                .team(team)
                .user(testUser1)
                .role(TeamRole.MEMBER)
                .joinedAt(Instant.now())
                .build();

        assertThatThrownBy(() -> {
            teamMemberRepository.save(member2);
            entityManager.flush();
        })
        .isInstanceOfAny(DataIntegrityViolationException.class, ConstraintViolationException.class)
        .hasMessageContaining("uk_team_members_team_user");
    }

    @Test
    @DisplayName("partial unique lead index rejects inserting second LEAD for same team")
    void partialUniqueLeadIndexRejectsSecondLead() {
        Team team = teamRepository.save(Team.builder()
                .name("DevOps Team")
                .workspace(workspaceA1)
                .organization(orgA)
                .status(TeamStatus.ACTIVE)
                .build());
        entityManager.flush();

        TeamMember lead1 = TeamMember.builder()
                .team(team)
                .user(testUser1)
                .role(TeamRole.LEAD)
                .joinedAt(Instant.now())
                .build();
        teamMemberRepository.save(lead1);
        entityManager.flush();

        TeamMember lead2 = TeamMember.builder()
                .team(team)
                .user(testUser2)
                .role(TeamRole.LEAD)
                .joinedAt(Instant.now())
                .build();

        assertThatThrownBy(() -> {
            teamMemberRepository.save(lead2);
            entityManager.flush();
        })
        .isInstanceOfAny(DataIntegrityViolationException.class, ConstraintViolationException.class)
        .hasMessageContaining("uk_team_members_team_lead");
    }

    @Test
    @DisplayName("partial unique lead index allows multiple MEMBER roles in same team")
    void partialUniqueLeadIndexAllowsMultipleMembers() {
        Team team = teamRepository.save(Team.builder()
                .name("Mobile Team")
                .workspace(workspaceA1)
                .organization(orgA)
                .status(TeamStatus.ACTIVE)
                .build());
        entityManager.flush();

        TeamMember member1 = TeamMember.builder()
                .team(team)
                .user(testUser1)
                .role(TeamRole.MEMBER)
                .joinedAt(Instant.now())
                .build();
        teamMemberRepository.save(member1);

        TeamMember member2 = TeamMember.builder()
                .team(team)
                .user(testUser2)
                .role(TeamRole.MEMBER)
                .joinedAt(Instant.now())
                .build();
        teamMemberRepository.save(member2);
        entityManager.flush();

        assertThat(teamMemberRepository.countByTeamIdAndRole(team.getId(), TeamRole.MEMBER)).isEqualTo(2);
    }

    @Test
    @DisplayName("cascade delete removes team_members when team is deleted")
    void cascadeDeleteRemovesTeamMembersWhenTeamDeleted() {
        Team team = teamRepository.save(Team.builder()
                .name("Ephemeral Team")
                .workspace(workspaceA1)
                .organization(orgA)
                .status(TeamStatus.ACTIVE)
                .build());

        teamMemberRepository.save(TeamMember.builder()
                .team(team)
                .user(testUser1)
                .role(TeamRole.LEAD)
                .joinedAt(Instant.now())
                .build());
        entityManager.flush();
        entityManager.clear();

        Team managedTeam = teamRepository.findById(team.getId()).orElseThrow();
        teamRepository.delete(managedTeam);
        entityManager.flush();
        entityManager.clear();

        assertThat(teamRepository.findById(team.getId())).isEmpty();
        assertThat(teamMemberRepository.findByTeamId(team.getId())).isEmpty();
    }

    @Test
    @DisplayName("restrict delete blocks deleting user who is a team member")
    void restrictDeleteBlocksDeletingUserInTeamMembers() {
        Team team = teamRepository.save(Team.builder()
                .name("Core Team")
                .workspace(workspaceA1)
                .organization(orgA)
                .status(TeamStatus.ACTIVE)
                .build());

        teamMemberRepository.save(TeamMember.builder()
                .team(team)
                .user(testUser1)
                .role(TeamRole.LEAD)
                .joinedAt(Instant.now())
                .build());
        entityManager.flush();
        entityManager.clear();

        User managedUser = userRepository.findById(testUser1.getId()).orElseThrow();

        assertThatThrownBy(() -> {
            userRepository.delete(managedUser);
            entityManager.flush();
        })
        .isInstanceOfAny(DataIntegrityViolationException.class, ConstraintViolationException.class)
        .hasMessageContaining("fk_team_members_user");
    }

    @Test
    @DisplayName("restrict delete blocks deleting workspace containing teams")
    void restrictDeleteBlocksDeletingWorkspaceContainingTeams() {
        teamRepository.save(Team.builder()
                .name("Core Team")
                .workspace(workspaceA1)
                .organization(orgA)
                .status(TeamStatus.ACTIVE)
                .build());
        entityManager.flush();
        entityManager.clear();

        Workspace managedWorkspace = workspaceRepository.findById(workspaceA1.getId()).orElseThrow();

        assertThatThrownBy(() -> {
            workspaceRepository.delete(managedWorkspace);
            entityManager.flush();
        })
        .isInstanceOfAny(DataIntegrityViolationException.class, ConstraintViolationException.class)
        .hasMessageContaining("fk_teams_workspace");
    }

    @Test
    @DisplayName("org member removal is rejected when user is active team lead in organization")
    void orgMemberRemovalRejectedWhenUserIsActiveTeamLead() {
        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA)
                .user(owner)
                .role(OrganizationRole.OWNER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA)
                .user(testUser1)
                .role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        Team team = teamRepository.save(Team.builder()
                .name("Alpha Team")
                .workspace(workspaceA1)
                .organization(orgA)
                .status(TeamStatus.ACTIVE)
                .build());

        teamMemberRepository.save(TeamMember.builder()
                .team(team)
                .user(testUser1)
                .role(TeamRole.LEAD)
                .joinedAt(Instant.now())
                .build());
        entityManager.flush();
        entityManager.clear();

        OrganizationAuthorizationService authService = new OrganizationAuthorizationService(organizationMemberRepository);
        OrganizationMembershipService membershipService = new OrganizationMembershipService(
                organizationMemberRepository, organizationRepository, userRepository, authService, teamMemberRepository);

        assertThatThrownBy(() -> membershipService.removeMember(orgA.getId(), testUser1.getId(), owner.getId()))
                .isInstanceOf(InvalidTeamOperationException.class)
                .hasMessageContaining("user is currently an active team lead");
    }

    @Test
    @DisplayName("org member removal cleans up team memberships when user is regular member")
    void orgMemberRemovalCleansUpTeamMembershipsWhenUserIsRegularMember() {
        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA)
                .user(owner)
                .role(OrganizationRole.OWNER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA)
                .user(testUser1)
                .role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        Team team = teamRepository.save(Team.builder()
                .name("Beta Team")
                .workspace(workspaceA1)
                .organization(orgA)
                .status(TeamStatus.ACTIVE)
                .build());

        teamMemberRepository.save(TeamMember.builder()
                .team(team)
                .user(testUser1)
                .role(TeamRole.MEMBER)
                .joinedAt(Instant.now())
                .build());
        entityManager.flush();
        entityManager.clear();

        OrganizationAuthorizationService authService = new OrganizationAuthorizationService(organizationMemberRepository);
        OrganizationMembershipService membershipService = new OrganizationMembershipService(
                organizationMemberRepository, organizationRepository, userRepository, authService, teamMemberRepository);

        membershipService.removeMember(orgA.getId(), testUser1.getId(), owner.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), testUser1.getId())).isEmpty();
        assertThat(teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(orgA.getId(), team.getId(), testUser1.getId())).isEmpty();
    }

    @Test
    @DisplayName("atomic leadership transfer successfully reassigns lead and demotes predecessor")
    void leadershipTransfer_atomicallyReassignsLeadAndDemotesPreviousLead() {
        Team team = teamRepository.save(Team.builder()
                .name("Core Architecture")
                .workspace(workspaceA1)
                .organization(orgA)
                .status(TeamStatus.ACTIVE)
                .build());

        TeamMember lead1 = teamMemberRepository.save(TeamMember.builder()
                .team(team)
                .user(testUser1)
                .role(TeamRole.LEAD)
                .joinedAt(Instant.now())
                .build());

        TeamMember member2 = teamMemberRepository.save(TeamMember.builder()
                .team(team)
                .user(testUser2)
                .role(TeamRole.MEMBER)
                .joinedAt(Instant.now())
                .build());
        entityManager.flush();
        entityManager.clear();

        // Perform leadership transfer: demote current lead, flush to DB, then promote new lead
        TeamMember managedLead1 = teamMemberRepository.findById(lead1.getId()).orElseThrow();
        TeamMember managedMember2 = teamMemberRepository.findById(member2.getId()).orElseThrow();

        managedLead1.demoteToMember();
        entityManager.flush();

        managedMember2.promoteToLead();
        entityManager.flush();
        entityManager.clear();

        Optional<TeamMember> currentLead = teamMemberRepository.findLeadByOrganizationIdAndTeamId(orgA.getId(), team.getId());
        assertThat(currentLead).isPresent();
        assertThat(currentLead.get().getUser().getId()).isEqualTo(testUser2.getId());

        TeamMember demoted = teamMemberRepository.findById(lead1.getId()).orElseThrow();
        assertThat(demoted.getRole()).isEqualTo(TeamRole.MEMBER);
        assertThat(demoted.isLead()).isFalse();
    }

    @Test
    @DisplayName("leadership transfer without demoting current lead violates partial unique index")
    void leadershipTransfer_promotingSecondLeadWithoutDemotion_violatesPartialIndex() {
        Team team = teamRepository.save(Team.builder()
                .name("SRE Team")
                .workspace(workspaceA1)
                .organization(orgA)
                .status(TeamStatus.ACTIVE)
                .build());

        TeamMember lead1 = teamMemberRepository.save(TeamMember.builder()
                .team(team)
                .user(testUser1)
                .role(TeamRole.LEAD)
                .joinedAt(Instant.now())
                .build());

        TeamMember member2 = teamMemberRepository.save(TeamMember.builder()
                .team(team)
                .user(testUser2)
                .role(TeamRole.MEMBER)
                .joinedAt(Instant.now())
                .build());
        entityManager.flush();
        entityManager.clear();

        TeamMember managedMember2 = teamMemberRepository.findById(member2.getId()).orElseThrow();
        managedMember2.promoteToLead(); // Lead 1 is NOT demoted

        assertThatThrownBy(() -> entityManager.flush())
                .isInstanceOfAny(DataIntegrityViolationException.class, ConstraintViolationException.class)
                .hasMessageContaining("uk_team_members_team_lead");
    }

    @Test
    @DisplayName("suspended organization member preserves team membership, allows admin transfer, and restores safely")
    void suspendedMember_preservesTeamMembershipAndAllowsAdminLeadershipTransfer() {
        OrganizationMember orgMember1 = organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA)
                .user(testUser1)
                .role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        OrganizationMember orgMember2 = organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA)
                .user(testUser2)
                .role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        Team team = teamRepository.save(Team.builder()
                .name("Security Team")
                .workspace(workspaceA1)
                .organization(orgA)
                .status(TeamStatus.ACTIVE)
                .build());

        TeamMember lead1 = teamMemberRepository.save(TeamMember.builder()
                .team(team)
                .user(testUser1)
                .role(TeamRole.LEAD)
                .joinedAt(Instant.now())
                .build());

        TeamMember member2 = teamMemberRepository.save(TeamMember.builder()
                .team(team)
                .user(testUser2)
                .role(TeamRole.MEMBER)
                .joinedAt(Instant.now())
                .build());
        entityManager.flush();
        entityManager.clear();

        OrganizationAuthorizationService authService = new OrganizationAuthorizationService(organizationMemberRepository);

        // 1. Suspend testUser1
        OrganizationMember managedOrgMember1 = organizationMemberRepository.findById(orgMember1.getId()).orElseThrow();
        managedOrgMember1.setStatus(OrganizationMembershipStatus.SUSPENDED);
        organizationMemberRepository.save(managedOrgMember1);
        entityManager.flush();
        entityManager.clear();

        // Verify: authService blocks suspended user from member privileges
        assertThat(authService.isMember(orgA.getId(), testUser1.getId())).isFalse();

        // Verify: team membership record is preserved in database (not deleted by suspension)
        assertThat(teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(orgA.getId(), team.getId(), testUser1.getId()))
                .isPresent();

        // 2. Organization admin transfers leadership away from suspended lead to testUser2
        TeamMember managedLead1 = teamMemberRepository.findById(lead1.getId()).orElseThrow();
        TeamMember managedMember2 = teamMemberRepository.findById(member2.getId()).orElseThrow();
        managedLead1.demoteToMember();
        entityManager.flush();
        managedMember2.promoteToLead();
        entityManager.flush();
        entityManager.clear();

        Optional<TeamMember> newLead = teamMemberRepository.findLeadByOrganizationIdAndTeamId(orgA.getId(), team.getId());
        assertThat(newLead).isPresent();
        assertThat(newLead.get().getUser().getId()).isEqualTo(testUser2.getId());

        // 3. Organization admin restores testUser1 membership to ACTIVE
        OrganizationMember suspendedOrgMember1 = organizationMemberRepository.findById(orgMember1.getId()).orElseThrow();
        suspendedOrgMember1.setStatus(OrganizationMembershipStatus.ACTIVE);
        organizationMemberRepository.save(suspendedOrgMember1);
        entityManager.flush();
        entityManager.clear();

        // Verify: user is active again, team membership is consistent as MEMBER
        assertThat(authService.isMember(orgA.getId(), testUser1.getId())).isTrue();
        Optional<TeamMember> restoredMembership = teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(
                orgA.getId(), team.getId(), testUser1.getId());
        assertThat(restoredMembership).isPresent();
        assertThat(restoredMembership.get().getRole()).isEqualTo(TeamRole.MEMBER);
        assertThat(restoredMembership.get().isLead()).isFalse();
    }
}
