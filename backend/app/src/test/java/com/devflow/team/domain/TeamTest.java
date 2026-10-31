package com.devflow.team.domain;

import com.devflow.organization.domain.Organization;
import com.devflow.team.exception.InvalidTeamOperationException;
import com.devflow.workspace.domain.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Team Domain Entity")
class TeamTest {

    private Workspace workspace;
    private Organization organization;

    @BeforeEach
    void setUp() {
        organization = Organization.builder()
                .id(UUID.randomUUID())
                .name("Acme Corp")
                .slug("acme-corp")
                .build();

        workspace = Workspace.builder()
                .id(UUID.randomUUID())
                .name("Engineering")
                .organization(organization)
                .build();
    }

    @Nested
    @DisplayName("Creation & Invariants")
    class CreationAndInvariants {

        @Test
        @DisplayName("builds team with default ACTIVE status")
        void buildsTeamWithDefaultActiveStatus() {
            UUID id = UUID.randomUUID();
            Team team = Team.builder()
                    .id(id)
                    .name("Core Platform")
                    .description("Core engineering platform team")
                    .workspace(workspace)
                    .organization(organization)
                    .build();

            assertThat(team.getId()).isEqualTo(id);
            assertThat(team.getName()).isEqualTo("Core Platform");
            assertThat(team.getDescription()).isEqualTo("Core engineering platform team");
            assertThat(team.getStatus()).isEqualTo(TeamStatus.ACTIVE);
            assertThat(team.isActive()).isTrue();
            assertThat(team.isArchived()).isFalse();
            assertThat(team.getWorkspace()).isEqualTo(workspace);
            assertThat(team.getOrganization()).isEqualTo(organization);
        }
    }

    @Nested
    @DisplayName("Lifecycle Transitions")
    class LifecycleTransitions {

        @Test
        @DisplayName("archive transitions status to ARCHIVED")
        void archiveTransitionsStatusToArchived() {
            Team team = Team.builder()
                    .id(UUID.randomUUID())
                    .name("Core Platform")
                    .workspace(workspace)
                    .organization(organization)
                    .status(TeamStatus.ACTIVE)
                    .build();

            team.archive();

            assertThat(team.getStatus()).isEqualTo(TeamStatus.ARCHIVED);
            assertThat(team.isArchived()).isTrue();
            assertThat(team.isActive()).isFalse();
        }

        @Test
        @DisplayName("archive throws InvalidTeamOperationException when already archived")
        void archiveThrowsWhenAlreadyArchived() {
            Team team = Team.builder()
                    .id(UUID.randomUUID())
                    .name("Core Platform")
                    .workspace(workspace)
                    .organization(organization)
                    .status(TeamStatus.ARCHIVED)
                    .build();

            assertThatThrownBy(team::archive)
                    .isInstanceOf(InvalidTeamOperationException.class)
                    .hasMessageContaining("already archived");
        }
    }

    @Nested
    @DisplayName("Mutations & Guards")
    class MutationsAndGuards {

        @Test
        @DisplayName("rename updates team name when active")
        void renameUpdatesNameWhenActive() {
            Team team = Team.builder()
                    .id(UUID.randomUUID())
                    .name("Old Name")
                    .workspace(workspace)
                    .organization(organization)
                    .status(TeamStatus.ACTIVE)
                    .build();

            team.rename("New Name");

            assertThat(team.getName()).isEqualTo("New Name");
        }

        @Test
        @DisplayName("rename trims leading and trailing whitespace")
        void renameTrimsWhitespace() {
            Team team = Team.builder()
                    .id(UUID.randomUUID())
                    .name("Old Name")
                    .workspace(workspace)
                    .organization(organization)
                    .status(TeamStatus.ACTIVE)
                    .build();

            team.rename("   Trimmed Name   ");

            assertThat(team.getName()).isEqualTo("Trimmed Name");
        }

        @Test
        @DisplayName("rename throws InvalidTeamOperationException when team is archived")
        void renameThrowsWhenArchived() {
            Team team = Team.builder()
                    .id(UUID.randomUUID())
                    .name("Core Platform")
                    .workspace(workspace)
                    .organization(organization)
                    .status(TeamStatus.ARCHIVED)
                    .build();

            assertThatThrownBy(() -> team.rename("New Name"))
                    .isInstanceOf(InvalidTeamOperationException.class)
                    .hasMessageContaining("Cannot rename archived team");
        }

        @Test
        @DisplayName("rename throws IllegalArgumentException for blank name")
        void renameThrowsForBlankName() {
            Team team = Team.builder()
                    .id(UUID.randomUUID())
                    .name("Core Platform")
                    .workspace(workspace)
                    .organization(organization)
                    .status(TeamStatus.ACTIVE)
                    .build();

            assertThatThrownBy(() -> team.rename("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("rename throws IllegalArgumentException when name exceeds 100 chars")
        void renameThrowsWhenNameExceeds100Chars() {
            Team team = Team.builder()
                    .id(UUID.randomUUID())
                    .name("Core Platform")
                    .workspace(workspace)
                    .organization(organization)
                    .status(TeamStatus.ACTIVE)
                    .build();

            String tooLong = "A".repeat(101);
            assertThatThrownBy(() -> team.rename(tooLong))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot exceed 100 characters");
        }

        @Test
        @DisplayName("updateDescription updates description when active")
        void updateDescriptionUpdatesDescription() {
            Team team = Team.builder()
                    .id(UUID.randomUUID())
                    .name("Core Platform")
                    .workspace(workspace)
                    .organization(organization)
                    .status(TeamStatus.ACTIVE)
                    .build();

            team.updateDescription("Updated description");

            assertThat(team.getDescription()).isEqualTo("Updated description");
        }

        @Test
        @DisplayName("updateDescription throws InvalidTeamOperationException when archived")
        void updateDescriptionThrowsWhenArchived() {
            Team team = Team.builder()
                    .id(UUID.randomUUID())
                    .name("Core Platform")
                    .workspace(workspace)
                    .organization(organization)
                    .status(TeamStatus.ARCHIVED)
                    .build();

            assertThatThrownBy(() -> team.updateDescription("New description"))
                    .isInstanceOf(InvalidTeamOperationException.class)
                    .hasMessageContaining("Cannot update description of archived team");
        }

        @Test
        @DisplayName("updateDescription throws IllegalArgumentException when description exceeds 500 chars")
        void updateDescriptionThrowsWhenTooLong() {
            Team team = Team.builder()
                    .id(UUID.randomUUID())
                    .name("Core Platform")
                    .workspace(workspace)
                    .organization(organization)
                    .status(TeamStatus.ACTIVE)
                    .build();

            String tooLong = "D".repeat(501);
            assertThatThrownBy(() -> team.updateDescription(tooLong))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot exceed 500 characters");
        }
    }

    @Nested
    @DisplayName("Equality & Identity")
    class EqualityAndIdentity {

        @Test
        @DisplayName("teams with same id are equal")
        void teamsWithSameIdAreEqual() {
            UUID id = UUID.randomUUID();
            Team team1 = Team.builder().id(id).name("Alpha").build();
            Team team2 = Team.builder().id(id).name("Beta").build();

            assertThat(team1).isEqualTo(team2);
            assertThat(team1.hashCode()).isEqualTo(team2.hashCode());
        }

        @Test
        @DisplayName("teams with different id are not equal")
        void teamsWithDifferentIdAreNotEqual() {
            Team team1 = Team.builder().id(UUID.randomUUID()).name("Alpha").build();
            Team team2 = Team.builder().id(UUID.randomUUID()).name("Alpha").build();

            assertThat(team1).isNotEqualTo(team2);
        }
    }
}

