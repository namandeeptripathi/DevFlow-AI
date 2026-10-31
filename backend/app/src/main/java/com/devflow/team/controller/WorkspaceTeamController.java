package com.devflow.team.controller;

import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.team.domain.Team;
import com.devflow.team.domain.TeamStatus;
import com.devflow.team.dto.request.CreateTeamRequest;
import com.devflow.team.dto.response.TeamResponse;
import com.devflow.team.service.TeamService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller exposing authenticated endpoints for workspace-scoped team collection operations.
 *
 * <p>Base URI path: {@code /api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams}
 *
 * <h2>Design Constraints</h2>
 * <ul>
 *   <li>Thin controller: Delegates all business and authorization logic exclusively to {@link TeamService}.</li>
 *   <li>Zero direct repository, entity mutation, or database access.</li>
 *   <li>Relies on Spring Security {@link AuthenticationPrincipal} to identify the caller;
 *       clients cannot specify or override acting user IDs.</li>
 *   <li>Multi-tenant boundary enforcement: Organization and workspace IDs are bound strictly from path variables.</li>
 *   <li>Exception propagation: Contains no try/catch blocks; delegates error mapping to
 *       {@link com.devflow.exception.GlobalExceptionHandler}.</li>
 *   <li>Constructor injection only.</li>
 * </ul>
 *
 * @see TeamService
 * @see CreateTeamRequest
 * @see TeamResponse
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/workspaces/{workspaceId}/teams")
@Tag(name = "Workspace Teams", description = "Workspace-scoped team collection management APIs")
public class WorkspaceTeamController {

    private final TeamService teamService;

    public WorkspaceTeamController(TeamService teamService) {
        this.teamService = Objects.requireNonNull(teamService, "teamService must not be null");
    }

    /**
     * Creates a new engineering team inside an existing workspace.
     *
     * <p>Requires organization {@code OWNER} or {@code ADMIN} role. Automatically assigns
     * the authenticated creator as the initial team {@code LEAD}.
     *
     * @param organizationId the UUID of the organization from the URL
     * @param workspaceId    the UUID of the workspace from the URL
     * @param userDetails    the authenticated principal injected by Spring Security
     * @param request        the validated creation payload
     * @return {@link ResponseEntity} containing HTTP 201 Created and the new {@link TeamResponse}
     */
    @PostMapping
    @Operation(
        summary = "Create a team in workspace",
        description = "Creates a new engineering team within the specified workspace. Requires organization ADMIN or OWNER role. " +
                "The creator is automatically assigned as the initial team LEAD.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Team created successfully",
            content = @Content(schema = @Schema(implementation = TeamResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failure — invalid or missing fields",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires organization ADMIN or OWNER role",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Organization or workspace not found",
            content = @Content),
        @ApiResponse(responseCode = "409", description = "Team with the given name already exists in the workspace",
            content = @Content)
    })
    public ResponseEntity<TeamResponse> createTeam(
            @PathVariable UUID organizationId,
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails,
            @Valid @RequestBody CreateTeamRequest request
    ) {
        Team created = teamService.createTeam(
                organizationId,
                workspaceId,
                request.getName(),
                request.getDescription(),
                userDetails.getId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(TeamResponse.from(created));
    }

    /**
     * Lists all engineering teams belonging to the specified workspace, with optional status filtering.
     *
     * <p>For {@code PRIVATE} workspaces, requires organization {@code OWNER} or {@code ADMIN} role.
     * For {@code PUBLIC} workspaces, accessible to any active organization member.
     *
     * @param organizationId the UUID of the organization from the URL
     * @param workspaceId    the UUID of the workspace from the URL
     * @param status         optional status filter (e.g. {@code ACTIVE}, {@code ARCHIVED})
     * @param userDetails    the authenticated principal injected by Spring Security
     * @return {@link ResponseEntity} containing HTTP 200 OK and the list of {@link TeamResponse}
     */
    @GetMapping
    @Operation(
        summary = "List teams in workspace",
        description = "Lists teams belonging to the specified workspace. Supports optional status filtering. " +
                "Respects workspace visibility rules.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Teams retrieved successfully",
            content = @Content(schema = @Schema(implementation = TeamResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires active organization membership (or ADMIN/OWNER for private workspace)",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Organization or workspace not found",
            content = @Content)
    })
    public ResponseEntity<List<TeamResponse>> listTeams(
            @PathVariable UUID organizationId,
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) TeamStatus status,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        List<Team> teams = teamService.listTeams(
                organizationId,
                workspaceId,
                status,
                userDetails.getId()
        );
        List<TeamResponse> responses = teams.stream()
                .map(TeamResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    /**
     * Retrieves a team by its ID within workspace and organization boundaries.
     *
     * @param organizationId the UUID of the organization
     * @param workspaceId    the UUID of the workspace
     * @param teamId         the UUID of the team
     * @param userDetails    the authenticated principal injected by Spring Security
     * @return {@link ResponseEntity} containing HTTP 200 OK and the retrieved {@link TeamResponse}
     */
    @GetMapping("/{teamId}")
    @Operation(
        summary = "Get team within workspace",
        description = "Retrieves a team by ID within the workspace and organization boundary. Respects workspace visibility rules.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Team retrieved successfully",
            content = @Content(schema = @Schema(implementation = TeamResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — access denied to workspace",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Team not found in workspace",
            content = @Content)
    })
    public ResponseEntity<TeamResponse> getTeamInWorkspace(
            @PathVariable UUID organizationId,
            @PathVariable UUID workspaceId,
            @PathVariable UUID teamId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        Team team = teamService.getTeam(
                organizationId,
                workspaceId,
                teamId,
                userDetails.getId()
        );
        return ResponseEntity.ok(TeamResponse.from(team));
    }
}

