package com.devflow.workspace.dto;

import com.devflow.workspace.domain.WorkspaceVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Data Transfer Object representing the {@code POST /api/v1/organizations/{organizationId}/workspaces}
 * request body.
 *
 * <p>Captures the client-controlled attributes required to create a new workspace within an organization.
 * The parent organization identity is derived from the URL path parameter and must not be supplied in this body.
 *
 * <p>Validation constraints mirror those declared on {@link com.devflow.workspace.domain.Workspace}
 * so that invalid payloads are rejected at the HTTP layer before domain services are invoked.
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
public class CreateWorkspaceRequest {

    @NotBlank(message = "Workspace name is required")
    @Size(max = 100, message = "Workspace name cannot exceed 100 characters")
    private String name;

    /**
     * Optional visibility scope. If omitted ({@code null}), defaults to {@link WorkspaceVisibility#PUBLIC}.
     */
    private WorkspaceVisibility visibility;

    @Size(max = 500, message = "Workspace description cannot exceed 500 characters")
    private String description;
}
