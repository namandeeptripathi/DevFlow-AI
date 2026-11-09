package com.devflow.role.dto;

import com.devflow.role.domain.CustomRoleEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST API response DTO representing an organization custom role (Stage 7).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Custom role details")
public class CustomRoleResponse {

    @Schema(description = "Unique identifier of the custom role", example = "123e4567-e89b-12d3-a456-426614174000")
    private UUID id;

    @Schema(description = "Tenant organization UUID", example = "123e4567-e89b-12d3-a456-426614174001")
    private UUID organizationId;

    @Schema(description = "Programmatic unique role identifier", example = "QA_ENGINEER")
    private String name;

    @Schema(description = "Human-readable display name", example = "QA Engineer")
    private String displayName;

    @Schema(description = "Role description", example = "Responsible for test automation and quality sign-off")
    private String description;

    @Schema(description = "Whether the custom role is currently active", example = "true")
    private boolean active;

    @Schema(description = "Assigned canonical permissions", example = "[\"project.read\", \"task.read\", \"task.write\"]")
    private Set<String> permissions;

    @Schema(description = "UUID of the user who created the custom role", example = "123e4567-e89b-12d3-a456-426614174002")
    private UUID createdBy;

    @Schema(description = "Timestamp when the custom role was created", example = "2026-11-09T10:00:00Z")
    private Instant createdAt;

    @Schema(description = "Timestamp when the custom role was last modified", example = "2026-11-09T10:00:00Z")
    private Instant updatedAt;

    @Schema(description = "Entity version for optimistic concurrency control", example = "0")
    private Long version;

    /**
     * Converts a {@link CustomRoleEntity} to a {@link CustomRoleResponse} DTO.
     */
    public static CustomRoleResponse from(CustomRoleEntity entity) {
        if (entity == null) {
            return null;
        }

        Set<String> permStrings;
        if (entity.getRolePermissions() != null) {
            permStrings = entity.getRolePermissions().stream()
                    .filter(rp -> rp.getPermission() != null)
                    .map(rp -> rp.getPermission().getName())
                    .collect(Collectors.toCollection(TreeSet::new));
        } else {
            permStrings = Collections.emptySet();
        }

        return CustomRoleResponse.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganization() != null ? entity.getOrganization().getId() : null)
                .name(entity.getName())
                .displayName(entity.getDisplayName())
                .description(entity.getDescription())
                .active(entity.isActive())
                .permissions(permStrings)
                .createdBy(entity.getCreatedBy() != null ? entity.getCreatedBy().getId() : null)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .version(entity.getVersion())
                .build();
    }
}
