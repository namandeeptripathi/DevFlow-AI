package com.devflow.workspace.dto;

import com.devflow.workspace.domain.Workspace;
import com.devflow.workspace.domain.WorkspaceVisibility;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/**
 * Data Transfer Object representing the workspace data returned to API clients.
 *
 * <p>Used as the response body for workspace lifecycle endpoints:
 * <ul>
 *   <li>{@code POST   /api/v1/organizations/{organizationId}/workspaces}               — create workspace</li>
 *   <li>{@code GET    /api/v1/organizations/{organizationId}/workspaces}               — list workspaces</li>
 *   <li>{@code GET    /api/v1/organizations/{organizationId}/workspaces/{workspaceId}} — get by ID</li>
 *   <li>{@code PATCH  /api/v1/organizations/{organizationId}/workspaces/{workspaceId}} — update workspace</li>
 * </ul>
 *
 * <p>Preserves tenant boundaries by exposing the scalar {@code organizationId} rather than the full
 * {@link com.devflow.organization.domain.Organization} entity graph or Hibernate proxies.
 *
 * @see com.devflow.workspace.domain.Workspace
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class WorkspaceResponse {

    /** Unique identifier of the workspace. */
    private UUID id;

    /** Display name of the workspace. */
    private String name;

    /** UUID of the parent organization. */
    private UUID organizationId;

    /** Visibility scope of the workspace. */
    private WorkspaceVisibility visibility;

    /** Optional human-readable description. */
    private String description;

    /** Timestamp at which this workspace was created. */
    private Instant createdAt;

    /** Timestamp of the most recent update. */
    private Instant updatedAt;

    /** Optimistic concurrency control version. */
    private Long version;

    /**
     * Maps a {@link Workspace} domain entity to its corresponding {@link WorkspaceResponse} DTO.
     *
     * @param workspace the domain entity to map
     * @return the response DTO, or {@code null} if the entity is {@code null}
     */
    public static WorkspaceResponse from(Workspace workspace) {
        if (workspace == null) {
            return null;
        }
        return WorkspaceResponse.builder()
                .id(workspace.getId())
                .name(workspace.getName())
                .organizationId(workspace.getOrganization() != null ? workspace.getOrganization().getId() : null)
                .visibility(workspace.getVisibility())
                .description(workspace.getDescription())
                .createdAt(workspace.getCreatedAt())
                .updatedAt(workspace.getUpdatedAt())
                .version(workspace.getVersion())
                .build();
    }
}
