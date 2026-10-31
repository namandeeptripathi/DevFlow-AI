package com.devflow.team.repository;

import com.devflow.config.JpaAuditingConfiguration;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.team.domain.Team;
import com.devflow.team.domain.TeamStatus;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import com.devflow.workspace.domain.Workspace;
import com.devflow.workspace.domain.WorkspaceVisibility;
import com.devflow.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaAuditingConfiguration.class)
@ActiveProfiles("test")
@DisplayName("TeamRepository JPA Queries")
class TeamRepositoryTest {

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Organization orgA;
    private Organization orgB;
    private Workspace workspaceA1;
    private Workspace workspaceA2;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(User.builder()
                .email("owner-" + UUID.randomUUID() + "@devflow.com")
                .username("owner_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        orgA = organizationRepository.save(Organization.builder()
                .name("Org A")
                .slug("org-a-" + UUID.randomUUID().toString().substring(0, 8))
                .owner(owner)
                .build());

        orgB = organizationRepository.save(Organization.builder()
                .name("Org B")
                .slug("org-b-" + UUID.randomUUID().toString().substring(0, 8))
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

    private Team createTeam(String name, Workspace workspace, Organization org, TeamStatus status) {
        return teamRepository.save(Team.builder()
                .name(name)
                .workspace(workspace)
                .organization(org)
                .status(status)
                .description(name + " description")
                .build());
    }

    @Test
    @DisplayName("saving team populates auditing fields and initial version")
    void savingTeamPopulatesAuditingAndVersion() {
        Team saved = createTeam("Infra Team", workspaceA1, orgA, TeamStatus.ACTIVE);
        entityManager.flush();
        entityManager.clear();

        Team fetched = teamRepository.findById(saved.getId()).orElseThrow();

        assertThat(fetched.getId()).isNotNull();
        assertThat(fetched.getCreatedAt()).isNotNull();
        assertThat(fetched.getUpdatedAt()).isNotNull();
        assertThat(fetched.getVersion()).isNotNull().isEqualTo(0L);
        assertThat(fetched.getName()).isEqualTo("Infra Team");
        assertThat(fetched.getStatus()).isEqualTo(TeamStatus.ACTIVE);
    }

    @Test
    @DisplayName("findByIdAndWorkspaceId returns team when workspace matches")
    void findByIdAndWorkspaceIdMatches() {
        Team saved = createTeam("Backend Team", workspaceA1, orgA, TeamStatus.ACTIVE);
        entityManager.flush();
        entityManager.clear();

        Optional<Team> found = teamRepository.findByIdAndWorkspaceId(saved.getId(), workspaceA1.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());

        Optional<Team> notFound = teamRepository.findByIdAndWorkspaceId(saved.getId(), workspaceA2.getId());
        assertThat(notFound).isEmpty();
    }

    @Test
    @DisplayName("findByIdAndOrganizationId returns team when organization matches")
    void findByIdAndOrganizationIdMatches() {
        Team saved = createTeam("Frontend Team", workspaceA1, orgA, TeamStatus.ACTIVE);
        entityManager.flush();
        entityManager.clear();

        Optional<Team> found = teamRepository.findByIdAndOrganizationId(saved.getId(), orgA.getId());
        assertThat(found).isPresent();

        Optional<Team> notFound = teamRepository.findByIdAndOrganizationId(saved.getId(), orgB.getId());
        assertThat(notFound).isEmpty();
    }

    @Test
    @DisplayName("findByIdAndWorkspaceIdAndOrganizationId enforces composite tenant scoping")
    void findByIdAndWorkspaceIdAndOrganizationIdMatches() {
        Team saved = createTeam("QA Team", workspaceA1, orgA, TeamStatus.ACTIVE);
        entityManager.flush();
        entityManager.clear();

        Optional<Team> found = teamRepository.findByIdAndWorkspaceIdAndOrganizationId(
                saved.getId(), workspaceA1.getId(), orgA.getId());
        assertThat(found).isPresent();

        Optional<Team> wrongOrg = teamRepository.findByIdAndWorkspaceIdAndOrganizationId(
                saved.getId(), workspaceA1.getId(), orgB.getId());
        assertThat(wrongOrg).isEmpty();

        Optional<Team> wrongWs = teamRepository.findByIdAndWorkspaceIdAndOrganizationId(
                saved.getId(), workspaceA2.getId(), orgA.getId());
        assertThat(wrongWs).isEmpty();
    }

    @Test
    @DisplayName("findByWorkspaceId returns all teams in the workspace")
    void findByWorkspaceIdReturnsWorkspaceTeams() {
        createTeam("Team 1", workspaceA1, orgA, TeamStatus.ACTIVE);
        createTeam("Team 2", workspaceA1, orgA, TeamStatus.ARCHIVED);
        createTeam("Team 3", workspaceA2, orgA, TeamStatus.ACTIVE);
        entityManager.flush();
        entityManager.clear();

        List<Team> teams = teamRepository.findByWorkspaceId(workspaceA1.getId());

        assertThat(teams).hasSize(2)
                .extracting(Team::getName)
                .containsExactlyInAnyOrder("Team 1", "Team 2");
    }

    @Test
    @DisplayName("findByWorkspaceIdAndStatus filters teams by status")
    void findByWorkspaceIdAndStatusFilters() {
        createTeam("Active Team", workspaceA1, orgA, TeamStatus.ACTIVE);
        createTeam("Archived Team", workspaceA1, orgA, TeamStatus.ARCHIVED);
        entityManager.flush();
        entityManager.clear();

        List<Team> activeTeams = teamRepository.findByWorkspaceIdAndStatus(workspaceA1.getId(), TeamStatus.ACTIVE);
        assertThat(activeTeams).hasSize(1);
        assertThat(activeTeams.get(0).getName()).isEqualTo("Active Team");

        List<Team> archivedTeams = teamRepository.findByWorkspaceIdAndStatus(workspaceA1.getId(), TeamStatus.ARCHIVED);
        assertThat(archivedTeams).hasSize(1);
        assertThat(archivedTeams.get(0).getName()).isEqualTo("Archived Team");
    }

    @Test
    @DisplayName("existsByWorkspaceIdAndNameIgnoreCase performs case-insensitive check")
    void existsByWorkspaceIdAndNameIgnoreCaseChecks() {
        createTeam("Platform Architecture", workspaceA1, orgA, TeamStatus.ACTIVE);
        entityManager.flush();
        entityManager.clear();

        assertThat(teamRepository.existsByWorkspaceIdAndNameIgnoreCase(workspaceA1.getId(), "platform architecture")).isTrue();
        assertThat(teamRepository.existsByWorkspaceIdAndNameIgnoreCase(workspaceA1.getId(), "PLATFORM ARCHITECTURE")).isTrue();
        assertThat(teamRepository.existsByWorkspaceIdAndNameIgnoreCase(workspaceA1.getId(), "Platform Architecture")).isTrue();
        assertThat(teamRepository.existsByWorkspaceIdAndNameIgnoreCase(workspaceA1.getId(), "Different Team")).isFalse();
        assertThat(teamRepository.existsByWorkspaceIdAndNameIgnoreCase(workspaceA2.getId(), "Platform Architecture")).isFalse();
    }

    @Test
    @DisplayName("existsByWorkspaceIdAndOrganizationIdAndNameIgnoreCaseAndIdNot checks collision excluding self")
    void existsByWorkspaceIdAndOrganizationIdAndNameIgnoreCaseAndIdNotChecks() {
        Team team1 = createTeam("Data Platform", workspaceA1, orgA, TeamStatus.ACTIVE);
        Team team2 = createTeam("ML Platform", workspaceA1, orgA, TeamStatus.ACTIVE);
        entityManager.flush();
        entityManager.clear();

        // Checking team1's own name with team1's ID excluded returns false (no conflict with others)
        assertThat(teamRepository.existsByWorkspaceIdAndOrganizationIdAndNameIgnoreCaseAndIdNot(
                workspaceA1.getId(), orgA.getId(), "data platform", team1.getId())).isFalse();

        // Checking team2's name with team1's ID excluded returns true (conflict with team2)
        assertThat(teamRepository.existsByWorkspaceIdAndOrganizationIdAndNameIgnoreCaseAndIdNot(
                workspaceA1.getId(), orgA.getId(), "ml platform", team1.getId())).isTrue();

        // Checking with different workspace returns false
        assertThat(teamRepository.existsByWorkspaceIdAndOrganizationIdAndNameIgnoreCaseAndIdNot(
                workspaceA2.getId(), orgA.getId(), "ml platform", team1.getId())).isFalse();

        // Checking with different organization returns false
        assertThat(teamRepository.existsByWorkspaceIdAndOrganizationIdAndNameIgnoreCaseAndIdNot(
                workspaceA1.getId(), orgB.getId(), "ml platform", team1.getId())).isFalse();
    }

    @Test
    @DisplayName("countByWorkspaceIdAndStatus counts accurately")
    void countByWorkspaceIdAndStatusCounts() {
        createTeam("Team Alpha", workspaceA1, orgA, TeamStatus.ACTIVE);
        createTeam("Team Beta", workspaceA1, orgA, TeamStatus.ACTIVE);
        createTeam("Team Gamma", workspaceA1, orgA, TeamStatus.ARCHIVED);
        entityManager.flush();
        entityManager.clear();

        assertThat(teamRepository.countByWorkspaceIdAndStatus(workspaceA1.getId(), TeamStatus.ACTIVE)).isEqualTo(2);
        assertThat(teamRepository.countByWorkspaceIdAndStatus(workspaceA1.getId(), TeamStatus.ARCHIVED)).isEqualTo(1);
    }

    @Test
    @DisplayName("findByOrganizationId returns all teams across workspaces in the organization")
    void findByOrganizationIdReturnsAllOrgTeams() {
        createTeam("Team Alpha", workspaceA1, orgA, TeamStatus.ACTIVE);
        createTeam("Team Beta", workspaceA2, orgA, TeamStatus.ACTIVE);
        createTeam("Team Gamma", workspaceA1, orgA, TeamStatus.ARCHIVED);
        entityManager.flush();
        entityManager.clear();

        List<Team> allOrgTeams = teamRepository.findByOrganizationId(orgA.getId());
        assertThat(allOrgTeams).hasSize(3)
                .extracting(Team::getName)
                .containsExactlyInAnyOrder("Team Alpha", "Team Beta", "Team Gamma");

        List<Team> activeOrgTeams = teamRepository.findByOrganizationIdAndStatus(orgA.getId(), TeamStatus.ACTIVE);
        assertThat(activeOrgTeams).hasSize(2)
                .extracting(Team::getName)
                .containsExactlyInAnyOrder("Team Alpha", "Team Beta");

        assertThat(teamRepository.countByOrganizationIdAndStatus(orgA.getId(), TeamStatus.ACTIVE)).isEqualTo(2);
        assertThat(teamRepository.countByWorkspaceIdAndOrganizationIdAndStatus(workspaceA1.getId(), orgA.getId(), TeamStatus.ACTIVE)).isEqualTo(1);
    }
}

