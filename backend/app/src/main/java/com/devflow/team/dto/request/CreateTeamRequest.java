package com.devflow.team.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Data Transfer Object representing the {@code POST /api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams}
 * request payload.
 *
 * <p>Captures client-provided attributes to provision a new team within a workspace boundary.
 * The parent organization and workspace identities are derived from URL path parameters.
 * The creator identity is derived from the authenticated security context.
 *
 * @see com.devflow.team.domain.Team
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@Schema(description = "Request payload for creating an engineering team within a workspace")
public class CreateTeamRequest {

    @NotBlank(message = "Team name is required")
    @Size(max = 100, message = "Team name cannot exceed 100 characters")
    @Schema(
        description = "Unique display name of the team within the workspace",
        example = "Core Backend",
        requiredMode = Schema.RequiredMode.REQUIRED,
        maxLength = 100
    )
    private String name;

    @Size(max = 500, message = "Team description cannot exceed 500 characters")
    @Schema(
        description = "Optional description of the team charter and responsibilities",
        example = "Core backend APIs, microservices, and database models",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        maxLength = 500
    )
    private String description;
}

