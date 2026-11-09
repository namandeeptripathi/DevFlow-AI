package com.devflow.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Request payload for creating an organization-scoped custom role (Stage 7).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Payload to create an organization-scoped custom role")
public class CreateCustomRoleRequest {

    @NotBlank(message = "Role name is required")
    @Size(max = 50, message = "Role name cannot exceed 50 characters")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "Role name must contain only alphanumeric characters, underscores, or hyphens")
    @Schema(description = "Unique programmatic role identifier within the organization", example = "QA_ENGINEER")
    private String name;

    @NotBlank(message = "Display name is required")
    @Size(max = 100, message = "Display name cannot exceed 100 characters")
    @Schema(description = "Human-readable display name", example = "QA Engineer")
    private String displayName;

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    @Schema(description = "Optional description of role responsibilities", example = "Responsible for test automation and quality sign-off")
    private String description;

    @NotEmpty(message = "At least one permission must be assigned")
    @Schema(description = "Set of canonical permission strings assigned to the role", example = "[\"project.read\", \"task.read\", \"task.write\", \"repo.scan\"]")
    private Set<String> permissions;
}
