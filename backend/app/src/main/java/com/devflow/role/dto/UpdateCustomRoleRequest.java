package com.devflow.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Request payload for updating an existing custom role (Stage 7).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Payload to update an organization-scoped custom role")
public class UpdateCustomRoleRequest {

    @Size(max = 100, message = "Display name cannot exceed 100 characters")
    @Schema(description = "Updated display name", example = "Senior QA Engineer")
    private String displayName;

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    @Schema(description = "Updated role description", example = "Lead test engineer with release verification privileges")
    private String description;

    @Schema(description = "Updated set of canonical permission strings (replaces existing permissions if provided)", example = "[\"project.read\", \"task.read\", \"task.write\", \"repo.scan\", \"ai.review\"]")
    private Set<String> permissions;

    @Schema(description = "Active state of the custom role. Inactive roles cannot be assigned and do not grant permissions.", example = "true")
    private Boolean active;
}
