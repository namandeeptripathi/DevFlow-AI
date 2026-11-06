package com.devflow.role.dto;

import com.devflow.role.domain.OrganizationRoleAssignment;
import com.devflow.role.domain.ProjectRoleAssignment;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * REST API response DTO representing an organization or project role assignment (Stage 6).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Role assignment details")
public class RoleAssignmentResponse {

    @Schema(description = "Unique identifier of the role assignment", example = "123e4567-e89b-12d3-a456-426614174000")
    private UUID id;

    @Schema(description = "Tenant organization UUID", example = "123e4567-e89b-12d3-a456-426614174001")
    private UUID organizationId;

    @Schema(description = "Target project UUID if project-scoped; null for organization-scoped", example = "123e4567-e89b-12d3-a456-426614174002")
    private UUID projectId;

    @Schema(description = "UUID of the user holding the role", example = "123e4567-e89b-12d3-a456-426614174003")
    private UUID userId;

    @Schema(description = "Username of the user", example = "alice_dev")
    private String username;

    @Schema(description = "Email of the user", example = "alice@example.com")
    private String userEmail;

    @Schema(description = "Canonical role name", example = "ORGANIZATION_ADMIN")
    private String role;

    @Schema(description = "Role scope (ORGANIZATION or PROJECT)", example = "ORGANIZATION")
    private String roleScope;

    @Schema(description = "UUID of the user who granted the role", example = "123e4567-e89b-12d3-a456-426614174004")
    private UUID assignedByUserId;

    @Schema(description = "Timestamp when the role was assigned", example = "2026-11-06T12:00:00Z")
    private Instant assignedAt;

    @Schema(description = "Timestamp when the role expires (null if permanent)", example = "2027-11-06T12:00:00Z")
    private Instant expiresAt;

    @Schema(description = "Timestamp when the role was revoked (null if active)", example = "2026-11-07T12:00:00Z")
    private Instant revokedAt;

    @Schema(description = "Revocation reason if revoked", example = "ADMIN_REVOCATION")
    private String revocationReason;

    @Schema(description = "Whether the role assignment is currently active and unexpired", example = "true")
    private boolean active;

    /**
     * Converts an {@link OrganizationRoleAssignment} entity to a {@link RoleAssignmentResponse} DTO.
     */
    public static RoleAssignmentResponse from(OrganizationRoleAssignment assignment) {
        if (assignment == null) {
            return null;
        }
        return RoleAssignmentResponse.builder()
                .id(assignment.getId())
                .organizationId(assignment.getOrganization() != null ? assignment.getOrganization().getId() : null)
                .projectId(null)
                .userId(assignment.getUser() != null ? assignment.getUser().getId() : null)
                .username(assignment.getUser() != null ? assignment.getUser().getUsername() : null)
                .userEmail(assignment.getUser() != null ? assignment.getUser().getEmail() : null)
                .role(assignment.getRole() != null ? assignment.getRole().getName() : null)
                .roleScope(assignment.getRoleScope() != null ? assignment.getRoleScope().name() : null)
                .assignedByUserId(assignment.getAssignedBy() != null ? assignment.getAssignedBy().getId() : null)
                .assignedAt(assignment.getAssignedAt())
                .expiresAt(assignment.getExpiresAt())
                .revokedAt(assignment.getRevokedAt())
                .revocationReason(assignment.getRevocationReason())
                .active(assignment.isActive())
                .build();
    }

    /**
     * Converts a {@link ProjectRoleAssignment} entity to a {@link RoleAssignmentResponse} DTO.
     */
    public static RoleAssignmentResponse from(ProjectRoleAssignment assignment) {
        if (assignment == null) {
            return null;
        }
        return RoleAssignmentResponse.builder()
                .id(assignment.getId())
                .organizationId(assignment.getOrganization() != null ? assignment.getOrganization().getId() : null)
                .projectId(assignment.getProjectId())
                .userId(assignment.getUser() != null ? assignment.getUser().getId() : null)
                .username(assignment.getUser() != null ? assignment.getUser().getUsername() : null)
                .userEmail(assignment.getUser() != null ? assignment.getUser().getEmail() : null)
                .role(assignment.getRole() != null ? assignment.getRole().getName() : null)
                .roleScope(assignment.getRoleScope() != null ? assignment.getRoleScope().name() : null)
                .assignedByUserId(assignment.getAssignedBy() != null ? assignment.getAssignedBy().getId() : null)
                .assignedAt(assignment.getAssignedAt())
                .expiresAt(assignment.getExpiresAt())
                .revokedAt(assignment.getRevokedAt())
                .revocationReason(assignment.getRevocationReason())
                .active(assignment.isActive())
                .build();
    }
}

