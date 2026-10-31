package com.devflow.team.dto.response;

import com.devflow.team.domain.Team;
import com.devflow.team.domain.TeamStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/**
 * Data Transfer Object representing an engineering team exposed to API consumers.
 *
 * <p>Preserves multi-tenant safety by exposing scalar IDs rather than nested JPA entity graphs.
 *
 * @see com.devflow.team.domain.Team
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@Schema(description = "Representation of an engineering team")
public class TeamResponse {

    @Schema(description = "Unique identifier of the team", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "Display name of the team", example = "Core Backend")
    private String name;

    @Schema(description = "UUID of the parent workspace", example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID workspaceId;

    @Schema(description = "UUID of the tenant organization", example = "550e8400-e29b-41d4-a716-446655440002")
    private UUID organizationId;

    @Schema(description = "Optional description of the team charter", example = "Core backend APIs and models")
    private String description;

    @Schema(description = "Lifecycle status of the team (ACTIVE, ARCHIVED)", example = "ACTIVE")
    private TeamStatus status;

    @Schema(description = "Timestamp when the team was created", example = "2026-10-31T18:00:00Z")
    private Instant createdAt;

    @Schema(description = "Timestamp when the team was last updated", example = "2026-10-31T18:30:00Z")
    private Instant updatedAt;

    /**
     * Maps a {@link Team} domain entity to its corresponding {@link TeamResponse} DTO.
     *
     * @param team the domain entity to map
     * @return the mapped {@link TeamResponse}, or {@code null} if input is null
     */
    public static TeamResponse from(Team team) {
        if (team == null) {
            return null;
        }

        UUID workspaceId = team.getWorkspace() != null ? team.getWorkspace().getId() : null;
        UUID organizationId = team.getOrganization() != null ? team.getOrganization().getId() :
                (team.getWorkspace() != null && team.getWorkspace().getOrganization() != null
                        ? team.getWorkspace().getOrganization().getId() : null);

        return TeamResponse.builder()
                .id(team.getId())
                .name(team.getName())
                .workspaceId(workspaceId)
                .organizationId(organizationId)
                .description(team.getDescription())
                .status(team.getStatus())
                .createdAt(team.getCreatedAt())
                .updatedAt(team.getUpdatedAt())
                .build();
    }
}

