package com.devflow.team.domain;

import com.devflow.team.exception.InvalidTeamOperationException;
import com.devflow.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TeamMember Domain Entity")
class TeamMemberTest {

    private Team activeTeam;
    private Team archivedTeam;
    private User testUser;

    @BeforeEach
    void setUp() {
        activeTeam = Team.builder()
                .id(UUID.randomUUID())
                .name("Core Platform")
                .status(TeamStatus.ACTIVE)
                .build();

        archivedTeam = Team.builder()
                .id(UUID.randomUUID())
                .name("Legacy Platform")
                .status(TeamStatus.ARCHIVED)
                .build();

        testUser = User.builder()
                .id(UUID.randomUUID())
                .email("engineer@devflow.com")
                .username("engineer")
                .build();
    }

    @Nested
    @DisplayName("Creation & Invariants")
    class CreationAndInvariants {

        @Test
        @DisplayName("builds team member with default MEMBER role")
        void buildsTeamMemberWithDefaultMemberRole() {
            UUID id = UUID.randomUUID();
            Instant now = Instant.now();

            TeamMember member = TeamMember.builder()
                    .id(id)
                    .team(activeTeam)
                    .user(testUser)
                    .joinedAt(now)
                    .build();

            assertThat(member.getId()).isEqualTo(id);
            assertThat(member.getTeam()).isEqualTo(activeTeam);
            assertThat(member.getUser()).isEqualTo(testUser);
            assertThat(member.getRole()).isEqualTo(TeamRole.MEMBER);
            assertThat(member.isLead()).isFalse();
            assertThat(member.getJoinedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("builds team member with LEAD role")
        void buildsTeamMemberWithLeadRole() {
            TeamMember member = TeamMember.builder()
                    .id(UUID.randomUUID())
                    .team(activeTeam)
                    .user(testUser)
                    .role(TeamRole.LEAD)
                    .joinedAt(Instant.now())
                    .build();

            assertThat(member.getRole()).isEqualTo(TeamRole.LEAD);
            assertThat(member.isLead()).isTrue();
        }
    }

    @Nested
    @DisplayName("Role Transitions")
    class RoleTransitions {

        @Test
        @DisplayName("promoteToLead changes role from MEMBER to LEAD")
        void promoteToLeadChangesRole() {
            TeamMember member = TeamMember.builder()
                    .id(UUID.randomUUID())
                    .team(activeTeam)
                    .user(testUser)
                    .role(TeamRole.MEMBER)
                    .joinedAt(Instant.now())
                    .build();

            member.promoteToLead();

            assertThat(member.getRole()).isEqualTo(TeamRole.LEAD);
            assertThat(member.isLead()).isTrue();
        }

        @Test
        @DisplayName("demoteToMember changes role from LEAD to MEMBER")
        void demoteToMemberChangesRole() {
            TeamMember member = TeamMember.builder()
                    .id(UUID.randomUUID())
                    .team(activeTeam)
                    .user(testUser)
                    .role(TeamRole.LEAD)
                    .joinedAt(Instant.now())
                    .build();

            member.demoteToMember();

            assertThat(member.getRole()).isEqualTo(TeamRole.MEMBER);
            assertThat(member.isLead()).isFalse();
        }

        @Test
        @DisplayName("changeRole updates role directly")
        void changeRoleUpdatesRole() {
            TeamMember member = TeamMember.builder()
                    .id(UUID.randomUUID())
                    .team(activeTeam)
                    .user(testUser)
                    .role(TeamRole.MEMBER)
                    .joinedAt(Instant.now())
                    .build();

            member.changeRole(TeamRole.LEAD);
            assertThat(member.getRole()).isEqualTo(TeamRole.LEAD);

            member.changeRole(TeamRole.MEMBER);
            assertThat(member.getRole()).isEqualTo(TeamRole.MEMBER);
        }

        @Test
        @DisplayName("changeRole throws IllegalArgumentException when role is null")
        void changeRoleThrowsWhenNull() {
            TeamMember member = TeamMember.builder()
                    .id(UUID.randomUUID())
                    .team(activeTeam)
                    .user(testUser)
                    .joinedAt(Instant.now())
                    .build();

            assertThatThrownBy(() -> member.changeRole(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null");
        }

        @Test
        @DisplayName("promoteToLead throws InvalidTeamOperationException when team is archived")
        void promoteToLeadThrowsWhenArchived() {
            TeamMember member = TeamMember.builder()
                    .id(UUID.randomUUID())
                    .team(archivedTeam)
                    .user(testUser)
                    .role(TeamRole.MEMBER)
                    .joinedAt(Instant.now())
                    .build();

            assertThatThrownBy(member::promoteToLead)
                    .isInstanceOf(InvalidTeamOperationException.class)
                    .hasMessageContaining("Cannot mutate membership for archived team");
        }

        @Test
        @DisplayName("demoteToMember throws InvalidTeamOperationException when team is archived")
        void demoteToMemberThrowsWhenArchived() {
            TeamMember member = TeamMember.builder()
                    .id(UUID.randomUUID())
                    .team(archivedTeam)
                    .user(testUser)
                    .role(TeamRole.LEAD)
                    .joinedAt(Instant.now())
                    .build();

            assertThatThrownBy(member::demoteToMember)
                    .isInstanceOf(InvalidTeamOperationException.class)
                    .hasMessageContaining("Cannot mutate membership for archived team");
        }
    }

    @Nested
    @DisplayName("Equality & Identity")
    class EqualityAndIdentity {

        @Test
        @DisplayName("team members with same id are equal")
        void membersWithSameIdAreEqual() {
            UUID id = UUID.randomUUID();
            TeamMember member1 = TeamMember.builder().id(id).role(TeamRole.MEMBER).build();
            TeamMember member2 = TeamMember.builder().id(id).role(TeamRole.LEAD).build();

            assertThat(member1).isEqualTo(member2);
            assertThat(member1.hashCode()).isEqualTo(member2.hashCode());
        }

        @Test
        @DisplayName("team members with different id are not equal")
        void membersWithDifferentIdAreNotEqual() {
            TeamMember member1 = TeamMember.builder().id(UUID.randomUUID()).role(TeamRole.MEMBER).build();
            TeamMember member2 = TeamMember.builder().id(UUID.randomUUID()).role(TeamRole.MEMBER).build();

            assertThat(member1).isNotEqualTo(member2);
        }
    }
}

