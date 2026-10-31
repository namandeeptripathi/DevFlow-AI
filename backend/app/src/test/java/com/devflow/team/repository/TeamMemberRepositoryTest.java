package com.devflow.team.repository;

import com.devflow.config.JpaAuditingConfiguration;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.team.domain.Team;
import com.devflow.team.domain.TeamMember;
import com.devflow.team.domain.TeamRole;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaAuditingConfiguration.class)
@ActiveProfiles("test")
@DisplayName("TeamMemberRepository JPA Queries")
class TeamMemberRepositoryTest {

    @Autowired
    private TeamMemberRepository teamMemberRepository;

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

    private Team team1;
    private Team team2;
    private User leadUser;
    private User memberUser1;
    private User memberUser2;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(User.builder()
                .email("owner-" + UUID.randomUUID() + "@devflow.com")
                .username("owner_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        leadUser = userRepository.save(User.builder()
                .email("lead-" + UUID.randomUUID() + "@devflow.com")
                .username("lead_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        memberUser1 = userRepository.save(User.builder()
                .email("mem1-" + UUID.randomUUID() + "@devflow.com")
                .username("mem1_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        memberUser2 = userRepository.save(User.builder()
                .email("mem2-" + UUID.randomUUID() + "@devflow.com")
                .username("mem2_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        Organization org = organizationRepository.save(Organization.builder()
                .name("Acme Corp")
                .slug("acme-" + UUID.randomUUID().toString().substring(0, 8))
                .owner(owner)
                .build());

        Workspace ws = workspaceRepository.save(Workspace.builder()
                .name("Engineering")
                .organization(org)
                .visibility(WorkspaceVisibility.PUBLIC)
                .build());

        team1 = teamRepository.save(Team.builder()
                .name("Core Infra")
                .workspace(ws)
                .organization(org)
                .status(TeamStatus.ACTIVE)
                .build());

        team2 = teamRepository.save(Team.builder()
                .name("Product Eng")
                .workspace(ws)
                .organization(org)
                .status(TeamStatus.ACTIVE)
                .build());

        entityManager.flush();
        entityManager.clear();
    }

    private TeamMember addMember(Team team, User user, TeamRole role) {
        return teamMemberRepository.save(TeamMember.builder()
                .team(team)
                .user(user)
                .role(role)
                .joinedAt(Instant.now())
                .build());
    }

    @Test
    @DisplayName("saving team member populates auditing fields and initial version")
    void savingMemberPopulatesAuditingAndVersion() {
        TeamMember saved = addMember(team1, leadUser, TeamRole.LEAD);
        entityManager.flush();
        entityManager.clear();

        TeamMember fetched = teamMemberRepository.findById(saved.getId()).orElseThrow();

        assertThat(fetched.getId()).isNotNull();
        assertThat(fetched.getCreatedAt()).isNotNull();
        assertThat(fetched.getUpdatedAt()).isNotNull();
        assertThat(fetched.getVersion()).isNotNull().isEqualTo(0L);
        assertThat(fetched.getRole()).isEqualTo(TeamRole.LEAD);
        assertThat(fetched.isLead()).isTrue();
    }

    @Test
    @DisplayName("findLeadByTeamId retrieves the unique team lead")
    void findLeadByTeamIdRetrievesLead() {
        addMember(team1, leadUser, TeamRole.LEAD);
        addMember(team1, memberUser1, TeamRole.MEMBER);
        entityManager.flush();
        entityManager.clear();

        Optional<TeamMember> lead = teamMemberRepository.findLeadByTeamId(team1.getId());

        assertThat(lead).isPresent();
        assertThat(lead.get().getUser().getId()).isEqualTo(leadUser.getId());
        assertThat(lead.get().getRole()).isEqualTo(TeamRole.LEAD);

        Optional<TeamMember> noLead = teamMemberRepository.findLeadByTeamId(team2.getId());
        assertThat(noLead).isEmpty();
    }

    @Test
    @DisplayName("findByTeamIdAndUserId matches specific member")
    void findByTeamIdAndUserIdMatches() {
        TeamMember member = addMember(team1, memberUser1, TeamRole.MEMBER);
        entityManager.flush();
        entityManager.clear();

        Optional<TeamMember> found = teamMemberRepository.findByTeamIdAndUserId(team1.getId(), memberUser1.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(member.getId());

        Optional<TeamMember> notFound = teamMemberRepository.findByTeamIdAndUserId(team1.getId(), memberUser2.getId());
        assertThat(notFound).isEmpty();
    }

    @Test
    @DisplayName("existsByTeamIdAndUserId returns true when member exists and false when absent")
    void existsByTeamIdAndUserIdChecks() {
        addMember(team1, memberUser1, TeamRole.MEMBER);
        entityManager.flush();
        entityManager.clear();

        assertThat(teamMemberRepository.existsByTeamIdAndUserId(team1.getId(), memberUser1.getId())).isTrue();
        assertThat(teamMemberRepository.existsByTeamIdAndUserId(team1.getId(), memberUser2.getId())).isFalse();
        assertThat(teamMemberRepository.existsByTeamIdAndUserId(team2.getId(), memberUser1.getId())).isFalse();
    }

    @Test
    @DisplayName("findByTeamId returns all members of team")
    void findByTeamIdReturnsAllMembers() {
        addMember(team1, leadUser, TeamRole.LEAD);
        addMember(team1, memberUser1, TeamRole.MEMBER);
        addMember(team2, memberUser2, TeamRole.MEMBER);
        entityManager.flush();
        entityManager.clear();

        List<TeamMember> members = teamMemberRepository.findByTeamId(team1.getId());

        assertThat(members).hasSize(2)
                .extracting(m -> m.getUser().getId())
                .containsExactlyInAnyOrder(leadUser.getId(), memberUser1.getId());
    }

    @Test
    @DisplayName("findByOrganizationIdAndTeamId returns members scoped to organization")
    void findByOrganizationIdAndTeamIdReturnsScopedMembers() {
        addMember(team1, leadUser, TeamRole.LEAD);
        addMember(team1, memberUser1, TeamRole.MEMBER);
        entityManager.flush();
        entityManager.clear();

        UUID orgId = team1.getOrganization().getId();
        List<TeamMember> orgTeamMembers = teamMemberRepository.findByOrganizationIdAndTeamId(orgId, team1.getId());
        assertThat(orgTeamMembers).hasSize(2)
                .extracting(m -> m.getUser().getId())
                .containsExactlyInAnyOrder(leadUser.getId(), memberUser1.getId());

        List<TeamMember> wrongOrgMembers = teamMemberRepository.findByOrganizationIdAndTeamId(UUID.randomUUID(), team1.getId());
        assertThat(wrongOrgMembers).isEmpty();
    }

    @Test
    @DisplayName("findByUserId returns all team memberships held by user")
    void findByUserIdReturnsAllMemberships() {
        addMember(team1, memberUser1, TeamRole.MEMBER);
        addMember(team2, memberUser1, TeamRole.LEAD);
        entityManager.flush();
        entityManager.clear();

        List<TeamMember> memberships = teamMemberRepository.findByUserId(memberUser1.getId());

        assertThat(memberships).hasSize(2)
                .extracting(m -> m.getTeam().getId())
                .containsExactlyInAnyOrder(team1.getId(), team2.getId());
    }

    @Test
    @DisplayName("countByTeamId counts total members")
    void countByTeamIdCounts() {
        addMember(team1, leadUser, TeamRole.LEAD);
        addMember(team1, memberUser1, TeamRole.MEMBER);
        entityManager.flush();
        entityManager.clear();

        assertThat(teamMemberRepository.countByTeamId(team1.getId())).isEqualTo(2);
        assertThat(teamMemberRepository.countByTeamId(team2.getId())).isEqualTo(0);
    }

    @Test
    @DisplayName("countByTeamIdAndRole counts members by role")
    void countByTeamIdAndRoleCounts() {
        addMember(team1, leadUser, TeamRole.LEAD);
        addMember(team1, memberUser1, TeamRole.MEMBER);
        addMember(team1, memberUser2, TeamRole.MEMBER);
        entityManager.flush();
        entityManager.clear();

        assertThat(teamMemberRepository.countByTeamIdAndRole(team1.getId(), TeamRole.LEAD)).isEqualTo(1);
        assertThat(teamMemberRepository.countByTeamIdAndRole(team1.getId(), TeamRole.MEMBER)).isEqualTo(2);
    }

    @Test
    @DisplayName("deleteByTeamIdAndUserId removes member")
    void deleteByTeamIdAndUserIdDeletes() {
        addMember(team1, memberUser1, TeamRole.MEMBER);
        entityManager.flush();
        entityManager.clear();

        assertThat(teamMemberRepository.existsByTeamIdAndUserId(team1.getId(), memberUser1.getId())).isTrue();

        teamMemberRepository.deleteByTeamIdAndUserId(team1.getId(), memberUser1.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(teamMemberRepository.existsByTeamIdAndUserId(team1.getId(), memberUser1.getId())).isFalse();
    }

    @Test
    @DisplayName("findByUserIdAndOrganizationId returns memberships scoped to organization")
    void findByUserIdAndOrganizationIdReturnsScopedMemberships() {
        addMember(team1, memberUser1, TeamRole.MEMBER);
        addMember(team2, memberUser1, TeamRole.LEAD);
        entityManager.flush();
        entityManager.clear();

        UUID orgId = team1.getOrganization().getId();
        List<TeamMember> orgMemberships = teamMemberRepository.findByUserIdAndOrganizationId(memberUser1.getId(), orgId);

        assertThat(orgMemberships).hasSize(2)
                .extracting(m -> m.getTeam().getId())
                .containsExactlyInAnyOrder(team1.getId(), team2.getId());

        List<TeamMember> otherOrgMemberships = teamMemberRepository.findByUserIdAndOrganizationId(
                memberUser1.getId(), UUID.randomUUID());
        assertThat(otherOrgMemberships).isEmpty();
    }

    @Test
    @DisplayName("existsByUserIdAndRoleAndOrganizationIdAndTeamStatus accurately detects active team leads")
    void existsByUserIdAndRoleAndOrganizationIdAndTeamStatusDetectsLeads() {
        addMember(team1, leadUser, TeamRole.LEAD);
        addMember(team1, memberUser1, TeamRole.MEMBER);
        entityManager.flush();
        entityManager.clear();

        UUID orgId = team1.getOrganization().getId();

        assertThat(teamMemberRepository.existsByUserIdAndRoleAndOrganizationIdAndTeamStatus(
                leadUser.getId(), TeamRole.LEAD, orgId, TeamStatus.ACTIVE)).isTrue();

        assertThat(teamMemberRepository.existsByUserIdAndRoleAndOrganizationIdAndTeamStatus(
                memberUser1.getId(), TeamRole.LEAD, orgId, TeamStatus.ACTIVE)).isFalse();

        assertThat(teamMemberRepository.existsByUserIdAndRoleAndOrganizationIdAndTeamStatus(
                leadUser.getId(), TeamRole.LEAD, orgId, TeamStatus.ARCHIVED)).isFalse();
    }

    @Test
    @DisplayName("deleteByUserIdAndOrganizationId removes all team memberships for user in organization")
    void deleteByUserIdAndOrganizationIdRemovesUserMembershipsInOrg() {
        addMember(team1, memberUser1, TeamRole.MEMBER);
        addMember(team2, memberUser1, TeamRole.MEMBER);
        entityManager.flush();
        entityManager.clear();

        UUID orgId = team1.getOrganization().getId();
        assertThat(teamMemberRepository.findByUserIdAndOrganizationId(memberUser1.getId(), orgId)).hasSize(2);

        teamMemberRepository.deleteByUserIdAndOrganizationId(memberUser1.getId(), orgId);
        entityManager.flush();
        entityManager.clear();

        assertThat(teamMemberRepository.findByUserIdAndOrganizationId(memberUser1.getId(), orgId)).isEmpty();
    }

    @Test
    @DisplayName("findByOrganizationIdAndTeamIdAndUserId matches correctly within tenant boundary")
    void findByOrganizationIdAndTeamIdAndUserIdMatches() {
        TeamMember member = addMember(team1, memberUser1, TeamRole.MEMBER);
        entityManager.flush();
        entityManager.clear();

        UUID orgId = team1.getOrganization().getId();
        Optional<TeamMember> found = teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(
                orgId, team1.getId(), memberUser1.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(member.getId());

        Optional<TeamMember> wrongOrg = teamMemberRepository.findByOrganizationIdAndTeamIdAndUserId(
                UUID.randomUUID(), team1.getId(), memberUser1.getId());
        assertThat(wrongOrg).isEmpty();
    }
}

