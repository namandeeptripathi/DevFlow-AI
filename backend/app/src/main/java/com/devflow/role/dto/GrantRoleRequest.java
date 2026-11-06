package com.devflow.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Request payload for granting an organization or project role assignment (Stage 6).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Payload to grant a role assignment")
public class GrantRoleRequest {

    @NotNull(message = "User ID is required")
    @Schema(description = "Target user UUID receiving the role", example = "123e4567-e89b-12d3-a456-426614174000")
    private UUID userId;

    @NotBlank(message = "Role name is required")
    @Schema(description = "Canonical role name (e.g. ORGANIZATION_ADMIN, PROJECT_LEAD)", example = "ORGANIZATION_ADMIN")
    private String role;

    @Schema(description = "Optional expiration timestamp. If null, assignment does not expire.", example = "2026-12-31T23:59:59Z")
    private Instant expiresAt;
}
