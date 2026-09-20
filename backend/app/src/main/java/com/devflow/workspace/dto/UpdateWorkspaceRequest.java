package com.devflow.workspace.dto;

import com.devflow.workspace.domain.WorkspaceVisibility;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Data Transfer Object representing the {@code PATCH /api/v1/organizations/{organizationId}/workspaces/{workspaceId}}
 * request body.
 *
 * <p>Supports partial updates (PATCH semantics). All fields are optional. A {@code null} field
 * signals that the caller does not wish to update that attribute — the existing value on the workspace
 * will be preserved.
 *
 * <p>Immutable attributes ({@code id}, {@code organizationId}, {@code createdAt}, {@code updatedAt},
 * {@code version}) are intentionally absent to protect domain invariants.
 *
 * @see com.devflow.workspace.domain.Workspace
 * @see com.devflow.workspace.domain.WorkspaceVisibility
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class UpdateWorkspaceRequest {

    @Size(max = 100, message = "Workspace name cannot exceed 100 characters")
    private String name;

    private WorkspaceVisibility visibility;

    @Size(max = 500, message = "Workspace description cannot exceed 500 characters")
    private String description;
}
