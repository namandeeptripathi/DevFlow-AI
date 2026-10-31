package com.devflow.team.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Data Transfer Object representing the {@code PATCH /api/v1/teams/{teamId}} request payload.
 *
 * <p>Supports partial updates (PATCH semantics). All fields are optional. A {@code null} field
 * signals that the caller does not wish to mutate that attribute; existing values will be preserved.
 *
 * @see com.devflow.team.domain.Team
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@Schema(description = "Request payload for partially updating team attributes")
public class UpdateTeamRequest {

    @Size(max = 100, message = "Team name cannot exceed 100 characters")
    @Schema(description = "Updated display name of the team", example = "Platform Engineering")
    private String name;

    @Size(max = 500, message = "Team description cannot exceed 500 characters")
    @Schema(description = "Updated description of the team charter", example = "Platform infrastructure, developer experience, and deployment pipelines")
    private String description;
}

