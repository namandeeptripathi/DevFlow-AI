package com.devflow.workspace.controller;

import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.workspace.dto.CreateWorkspaceRequest;
import com.devflow.workspace.dto.UpdateWorkspaceRequest;
import com.devflow.workspace.dto.WorkspaceResponse;
import com.devflow.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller exposing authenticated endpoints for workspace lifecycle management.
 *
 * <p>Base URI path: {@code /api/v1/organizations/{organizationId}/workspaces}
 *
 * <h2>Design Constraints</h2>
 * <ul>
 *   <li>Thin controller: Delegates all business and authorization logic exclusively to {@link WorkspaceService}.</li>
 *   <li>Zero direct repository, entity, or database access.</li>
 *   <li>Relies on Spring Security {@link AuthenticationPrincipal} to identify the caller;
 *       clients cannot specify or override acting user IDs.</li>
 *   <li>Tenant isolation: Organization ID is bound strictly from the URL path.</li>
 *   <li>Exception propagation: Contains no try/catch blocks; delegates error mapping to
 *       {@link com.devflow.exception.GlobalExceptionHandler}.</li>
 *   <li>Constructor injection only.</li>
 * </ul>
 *
 * @see WorkspaceService
 * @see WorkspaceResponse
 * @see CreateWorkspaceRequest
 * @see UpdateWorkspaceRequest
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/workspaces")
@Tag(name = "Workspaces", description = "Workspace lifecycle management APIs")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = Objects.requireNonNull(
                workspaceService, "workspaceService must not be null");
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    /**
     * Creates a new workspace within the specified organization.
     *
     * <p>Requires the caller to hold {@code ADMIN} or {@code OWNER} role in the organization.
     *
     * @param organizationId the UUID of the target organization from the URL
     * @param userDetails    the authenticated principal injected by Spring Security
     * @param request        the validated creation payload
     * @return {@link ResponseEntity} containing HTTP 201 Created and the new {@link WorkspaceResponse}
     */
    @PostMapping
    @Operation(
        summary = "Create a new workspace",
        description = "Creates a new workspace within the specified organization. Requires ADMIN or OWNER role.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Workspace created successfully",
            content = @Content(schema = @Schema(implementation = WorkspaceResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failure — invalid or missing fields",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN or OWNER role",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Organization not found",
            content = @Content),
        @ApiResponse(responseCode = "409", description = "Workspace with the given name already exists in the organization",
            content = @Content)
    })
    public ResponseEntity<WorkspaceResponse> createWorkspace(
            @PathVariable UUID organizationId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails,
            @Valid @RequestBody CreateWorkspaceRequest request
    ) {
        WorkspaceResponse response = workspaceService.createWorkspace(
                organizationId,
                request,
                userDetails.getId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Updates mutable fields of an existing workspace within an organization.
     *
     * <p>Supports partial updates (PATCH semantics). Only non-null fields in the request body
     * are updated. Requires {@code ADMIN} or {@code OWNER} role in the organization.
     *
     * @param organizationId the UUID of the organization from the URL
     * @param workspaceId    the UUID of the workspace to update
     * @param userDetails    the authenticated principal injected by Spring Security
     * @param request        the validated update payload
     * @return {@link ResponseEntity} containing HTTP 200 OK and the updated {@link WorkspaceResponse}
     */
    @PatchMapping("/{workspaceId}")
    @Operation(
        summary = "Update a workspace",
        description = "Applies a partial update to mutable workspace attributes. Requires ADMIN or OWNER role.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Workspace updated successfully",
            content = @Content(schema = @Schema(implementation = WorkspaceResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failure — invalid fields",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN or OWNER role",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Workspace not found in the organization",
            content = @Content),
        @ApiResponse(responseCode = "409", description = "Workspace with the given name already exists in the organization",
            content = @Content)
    })
    public ResponseEntity<WorkspaceResponse> updateWorkspace(
            @PathVariable UUID organizationId,
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails,
            @Valid @RequestBody UpdateWorkspaceRequest request
    ) {
        WorkspaceResponse response = workspaceService.updateWorkspace(
                organizationId,
                workspaceId,
                request,
                userDetails.getId()
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Permanently deletes a workspace within an organization boundary.
     *
     * <p>Requires {@code ADMIN} or {@code OWNER} role in the organization.
     *
     * @param organizationId the UUID of the organization from the URL
     * @param workspaceId    the UUID of the workspace to delete
     * @param userDetails    the authenticated principal injected by Spring Security
     * @return {@link ResponseEntity} containing HTTP 204 No Content
     */
    @DeleteMapping("/{workspaceId}")
    @Operation(
        summary = "Delete a workspace",
        description = "Permanently deletes the specified workspace within the organization boundary. Requires ADMIN or OWNER role.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Workspace deleted successfully",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN or OWNER role",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Workspace not found in the organization",
            content = @Content)
    })
    public ResponseEntity<Void> deleteWorkspace(
            @PathVariable UUID organizationId,
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        workspaceService.deleteWorkspace(
                organizationId,
                workspaceId,
                userDetails.getId()
        );
        return ResponseEntity.noContent().build();
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Retrieves all workspaces in the organization accessible to the caller.
     *
     * <p>Admins and owners see all workspaces ({@code PUBLIC} and {@code PRIVATE}).
     * Regular members (developers, viewers) see only {@code PUBLIC} workspaces.
     * Requires active organization membership.
     *
     * @param organizationId the UUID of the organization from the URL
     * @param userDetails    the authenticated principal injected by Spring Security
     * @return {@link ResponseEntity} containing HTTP 200 OK and a list of {@link WorkspaceResponse}
     */
    @GetMapping
    @Operation(
        summary = "List workspaces in organization",
        description = "Retrieves workspaces in the organization accessible to the authenticated user. " +
                "ADMIN and OWNER see all workspaces (PUBLIC and PRIVATE). " +
                "DEVELOPER and VIEWER see only PUBLIC workspaces. Requires active organization membership.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Workspaces retrieved successfully",
            content = @Content(schema = @Schema(implementation = WorkspaceResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires active organization membership",
            content = @Content)
    })
    public ResponseEntity<List<WorkspaceResponse>> getWorkspaces(
            @PathVariable UUID organizationId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        List<WorkspaceResponse> responses = workspaceService.getWorkspaces(
                organizationId,
                userDetails.getId()
        );
        return ResponseEntity.ok(responses);
    }

    /**
     * Retrieves a single workspace by its identifier within the organization boundary.
     *
     * <p>Requires active organization membership. For {@code PRIVATE} workspaces, requires
     * {@code ADMIN} or {@code OWNER} role.
     *
     * @param organizationId the UUID of the organization from the URL
     * @param workspaceId    the UUID of the workspace
     * @param userDetails    the authenticated principal injected by Spring Security
     * @return {@link ResponseEntity} containing HTTP 200 OK and the {@link WorkspaceResponse}
     */
    @GetMapping("/{workspaceId}")
    @Operation(
        summary = "Get workspace by ID",
        description = "Retrieves a single workspace by ID within the organization boundary. " +
                "PUBLIC workspaces are accessible to all active organization members. " +
                "PRIVATE workspaces require ADMIN or OWNER role.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Workspace retrieved successfully",
            content = @Content(schema = @Schema(implementation = WorkspaceResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires active membership, or ADMIN/OWNER for private workspaces",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Workspace not found in the organization",
            content = @Content)
    })
    public ResponseEntity<WorkspaceResponse> getWorkspace(
            @PathVariable UUID organizationId,
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        WorkspaceResponse response = workspaceService.getWorkspace(
                organizationId,
                workspaceId,
                userDetails.getId()
        );
        return ResponseEntity.ok(response);
    }
}
