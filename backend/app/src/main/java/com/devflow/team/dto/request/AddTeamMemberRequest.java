package com.devflow.team.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

/**
 * Data Transfer Object representing the {@code POST /api/v1/teams/{teamId}/members} request payload.
 *
 * <p>Identifies an active organization member to be added as a {@code MEMBER} of the team.
 *
 * @see com.devflow.team.domain.TeamMember
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@Schema(description = "Request payload for adding an organization member to a team")
public class AddTeamMemberRequest {

    @NotNull(message = "User ID is required")
    @Schema(description = "UUID of the active organization member to add to the team", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID userId;
}

