package com.devflow.team.controller;

import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.team.domain.Team;
import com.devflow.team.domain.TeamMember;
import com.devflow.team.dto.request.AddTeamMemberRequest;
import com.devflow.team.dto.request.TransferTeamLeadershipRequest;
import com.devflow.team.dto.request.UpdateTeamRequest;
import com.devflow.team.dto.response.TeamLeadResponse;
import com.devflow.team.dto.response.TeamMemberResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller exposing authenticated resource-scoped endpoints for engineering teams.
 *
 * <p>Base URI path: {@code /api/v1/teams}
 *
 * <h2>Design Constraints</h2>
 * <ul>
 *   <li>Thin controller: Delegates all business, domain validation, and authorization logic
 *       exclusively to {@link TeamService}.</li>
 *   <li>Zero direct repository, entity mutation, or database access.</li>
 *   <li>Identifies the acting user solely via {@link AuthenticationPrincipal}; never trusts
 *       client-provided caller IDs.</li>
 *   <li>Adheres to the platform 2-level URI nesting limit by elevating teams to a top-level resource.</li>
 *   <li>Exception propagation: Delegates error mapping to {@link com.devflow.exception.GlobalExceptionHandler}.</li>
 *   <li>Constructor injection only.</li>
 * </ul>
 *
 * @see TeamService
 * @see TeamResponse
 * @see TeamMemberResponse
 * @see TeamLeadResponse
 */
@RestController
@RequestMapping("/api/v1/teams")
@Tag(name = "Teams", description = "Team lifecycle and membership management APIs")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = Objects.requireNonNull(teamService, "teamService must not be null");
    }

    // ── Team Resource Lifecycle ────────────────────────────────────────────────

    /**
     * Retrieves an engineering team by its unique identifier.
     *
     * <p>Enforces tenant membership and workspace visibility rules.
     *
     * @param teamId      the UUID of the team
     * @param userDetails the authenticated principal injected by Spring Security
     * @return {@link ResponseEntity} containing HTTP 200 OK and the {@link TeamResponse}
     */
    @GetMapping("/{teamId}")
    @Operation(
        summary = "Get team by ID",
        description = "Retrieves an engineering team by its unique identifier. Enforces workspace visibility.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Team retrieved successfully",
            content = @Content(schema = @Schema(implementation = TeamResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — caller not authorized to view this team",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Team not found",
            content = @Content)
    })
    public ResponseEntity<TeamResponse> getTeam(
            @PathVariable UUID teamId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        Team team = teamService.getTeam(teamId, userDetails.getId());
        return ResponseEntity.ok(TeamResponse.from(team));
    }

    /**
     * Applies a partial update (PATCH semantics) to mutable team attributes.
     *
     * <p>Requires organization {@code OWNER}/{@code ADMIN} role or current team {@code LEAD}.
     * Cannot update archived teams.
     *
     * @param teamId      the UUID of the team to update
     * @param userDetails the authenticated principal injected by Spring Security
     * @param request     the validated update payload
     * @return {@link ResponseEntity} containing HTTP 200 OK and the updated {@link TeamResponse}
     */
    @PatchMapping("/{teamId}")
    @Operation(
        summary = "Update team",
        description = "Applies a partial update to mutable team attributes. Requires organization ADMIN/OWNER or team LEAD role.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Team updated successfully",
            content = @Content(schema = @Schema(implementation = TeamResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failure or team is archived",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN, OWNER, or LEAD role",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Team not found",
            content = @Content),
        @ApiResponse(responseCode = "409", description = "Team name already in use within the workspace",
            content = @Content)
    })
    public ResponseEntity<TeamResponse> updateTeam(
            @PathVariable UUID teamId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails,
            @Valid @RequestBody UpdateTeamRequest request
    ) {
        Team updated = teamService.updateTeam(
                teamId,
                request.getName(),
                request.getDescription(),
                userDetails.getId()
        );
        return ResponseEntity.ok(TeamResponse.from(updated));
    }

    /**
     * Archives an engineering team.
     *
     * <p>Transitions the team to {@code ARCHIVED} status. Archived teams become strictly read-only.
     * Requires organization {@code OWNER}/{@code ADMIN} role or current team {@code LEAD}.
     *
     * @param teamId      the UUID of the team to archive
     * @param userDetails the authenticated principal injected by Spring Security
     * @return {@link ResponseEntity} containing HTTP 200 OK and the archived {@link TeamResponse}
     */
    @PostMapping("/{teamId}/archive")
    @Operation(
        summary = "Archive team",
        description = "Archives an active engineering team, making it read-only. Requires organization ADMIN/OWNER or team LEAD role.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Team archived successfully",
            content = @Content(schema = @Schema(implementation = TeamResponse.class))),
        @ApiResponse(responseCode = "400", description = "Team is already archived",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN, OWNER, or LEAD role",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Team not found",
            content = @Content)
    })
    public ResponseEntity<TeamResponse> archiveTeam(
            @PathVariable UUID teamId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        Team archived = teamService.archiveTeam(teamId, userDetails.getId());
        return ResponseEntity.ok(TeamResponse.from(archived));
    }

    // ── Team Membership Management ─────────────────────────────────────────────

    /**
     * Adds an existing organization member to the team with {@code MEMBER} role.
     *
     * <p>Requires organization {@code OWNER}/{@code ADMIN} role or current team {@code LEAD}.
     *
     * @param teamId      the UUID of the team
     * @param userDetails the authenticated principal injected by Spring Security
     * @param request     the validated member addition payload
     * @return {@link ResponseEntity} containing HTTP 201 Created and the new {@link TeamMemberResponse}
     */
    @PostMapping("/{teamId}/members")
    @Operation(
        summary = "Add team member",
        description = "Adds an active organization member to the team. Requires organization ADMIN/OWNER or team LEAD role.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Team member added successfully",
            content = @Content(schema = @Schema(implementation = TeamMemberResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failure, team is archived, or member is suspended",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN, OWNER, or LEAD role",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Team or organization member not found",
            content = @Content),
        @ApiResponse(responseCode = "409", description = "User is already a member of the team",
            content = @Content)
    })
    public ResponseEntity<TeamMemberResponse> addMember(
            @PathVariable UUID teamId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails,
            @Valid @RequestBody AddTeamMemberRequest request
    ) {
        TeamMember member = teamService.addMember(teamId, request.getUserId(), userDetails.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(TeamMemberResponse.from(member));
    }

    /**
     * Lists all members of an engineering team.
     *
     * <p>Accessible to any active organization member authorized to view the workspace.
     *
     * @param teamId      the UUID of the team
     * @param userDetails the authenticated principal injected by Spring Security
     * @return {@link ResponseEntity} containing HTTP 200 OK and the list of {@link TeamMemberResponse}
     */
    @GetMapping("/{teamId}/members")
    @Operation(
        summary = "List team members",
        description = "Lists all members of the specified team. Respects workspace visibility rules.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Team members retrieved successfully",
            content = @Content(schema = @Schema(implementation = TeamMemberResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — caller not authorized to view team",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Team not found",
            content = @Content)
    })
    public ResponseEntity<List<TeamMemberResponse>> listMembers(
            @PathVariable UUID teamId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        List<TeamMember> members = teamService.listMembers(teamId, userDetails.getId());
        List<TeamMemberResponse> responses = members.stream()
                .map(TeamMemberResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    /**
     * Removes a member from an active team.
     *
     * <p>Requires organization {@code OWNER}/{@code ADMIN} role or current team {@code LEAD}.
     * Enforces that the active team lead cannot be removed without prior leadership transfer.
     *
     * @param teamId      the UUID of the team
     * @param userId      the UUID of the member to remove
     * @param userDetails the authenticated principal injected by Spring Security
     * @return {@link ResponseEntity} containing HTTP 204 No Content
     */
    @DeleteMapping("/{teamId}/members/{userId}")
    @Operation(
        summary = "Remove team member",
        description = "Removes a member from the team. The current lead cannot be removed without prior transfer. " +
                "Requires organization ADMIN/OWNER or team LEAD role.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Team member removed successfully",
            content = @Content),
        @ApiResponse(responseCode = "400", description = "Team is archived or attempt to remove active lead",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN, OWNER, or LEAD role",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Team or member record not found",
            content = @Content)
    })
    public ResponseEntity<Void> removeMember(
            @PathVariable UUID teamId,
            @PathVariable UUID userId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        teamService.removeMember(teamId, userId, userDetails.getId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Allows an active team member to voluntarily leave their team.
     *
     * <p>Enforces that the designated team lead cannot leave without transferring leadership.
     * Suspended organization members cannot execute this operation.
     *
     * @param teamId      the UUID of the team
     * @param userDetails the authenticated principal injected by Spring Security
     * @return {@link ResponseEntity} containing HTTP 204 No Content
     */
    @PostMapping("/{teamId}/leave")
    @Operation(
        summary = "Leave team",
        description = "Allows the authenticated member to leave the team. Team lead cannot leave without transfer.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Successfully left the team",
            content = @Content),
        @ApiResponse(responseCode = "400", description = "Team is archived or caller is the active team lead",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — suspended organization member",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Team or membership record not found",
            content = @Content)
    })
    public ResponseEntity<Void> leaveTeam(
            @PathVariable UUID teamId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        teamService.leaveTeam(teamId, userDetails.getId());
        return ResponseEntity.noContent().build();
    }

    // ── Leadership Operations ──────────────────────────────────────────────────

    /**
     * Resolves and returns the designated active team lead.
     *
     * @param teamId      the UUID of the team
     * @param userDetails the authenticated principal injected by Spring Security
     * @return {@link ResponseEntity} containing HTTP 200 OK and the {@link TeamLeadResponse}
     */
    @GetMapping("/{teamId}/lead")
    @Operation(
        summary = "Get team lead",
        description = "Resolves the designated active team lead. Respects workspace visibility rules.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Team lead resolved successfully",
            content = @Content(schema = @Schema(implementation = TeamLeadResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — caller not authorized to view team",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Team or lead not found",
            content = @Content)
    })
    public ResponseEntity<TeamLeadResponse> getTeamLead(
            @PathVariable UUID teamId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        TeamMember lead = teamService.getTeamLead(teamId, userDetails.getId());
        return ResponseEntity.ok(TeamLeadResponse.from(lead));
    }

    /**
     * Atomically transfers leadership of an active team to an existing member.
     *
     * <p>Requires organization {@code OWNER}/{@code ADMIN} role or current team {@code LEAD}.
     * Demotes the current lead and promotes the designated successor.
     *
     * @param teamId      the UUID of the team
     * @param userDetails the authenticated principal injected by Spring Security
     * @param request     the validated leadership transfer payload
     * @return {@link ResponseEntity} containing HTTP 200 OK and the updated {@link TeamLeadResponse}
     */
    @PutMapping("/{teamId}/lead")
    @Operation(
        summary = "Transfer team leadership",
        description = "Transfers team leadership to an existing team member. Requires organization ADMIN/OWNER or current team LEAD role.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Leadership transferred successfully",
            content = @Content(schema = @Schema(implementation = TeamLeadResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failure, team is archived, target already lead, or suspended",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN, OWNER, or current LEAD role",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Team, successor member, or current lead not found",
            content = @Content)
    })
    public ResponseEntity<TeamLeadResponse> transferLeadership(
            @PathVariable UUID teamId,
            @AuthenticationPrincipal DevFlowUserDetails userDetails,
            @Valid @RequestBody TransferTeamLeadershipRequest request
    ) {
        TeamMember newLead = teamService.transferLeadership(
                teamId,
                request.getTargetUserId(),
                userDetails.getId()
        );
        return ResponseEntity.ok(TeamLeadResponse.from(newLead));
    }
}

