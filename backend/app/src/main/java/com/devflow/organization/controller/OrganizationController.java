package com.devflow.organization.controller;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.dto.CreateOrganizationRequest;
import com.devflow.organization.dto.OrganizationResponse;
import com.devflow.organization.dto.UpdateOrganizationRequest;
import com.devflow.organization.service.OrganizationService;
import com.devflow.security.user.DevFlowUserDetails;
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

import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller exposing authenticated endpoints for organization lifecycle management.
 *
 * <p>Base URI path: {@code /api/v1/organizations}
 *
 * <h2>Design Constraints</h2>
 * <ul>
 *   <li>Thin controller: Delegates all domain logic exclusively to {@link OrganizationService}.</li>
 *   <li>Zero direct repository, entity, or database access.</li>
 *   <li>Relies on Spring Security {@link AuthenticationPrincipal} to identify the caller;
 *       clients cannot specify acting user IDs.</li>
 *   <li>Exception propagation: Contains no try/catch blocks; delegates error mapping to
 *       {@link com.devflow.exception.GlobalExceptionHandler}.</li>
 *   <li>Constructor injection only.</li>
 *   <li>Entity-to-DTO mapping is performed in a private helper method.</li>
 * </ul>
 *
 * @see OrganizationService
 * @see OrganizationResponse
 * @see CreateOrganizationRequest
 * @see UpdateOrganizationRequest
 */
@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "Organizations", description = "Organization lifecycle management APIs")
public class OrganizationController {

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = Objects.requireNonNull(
                organizationService, "organizationService must not be null");
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    /**
     * Creates a new organization with the authenticated user as founding owner.
     *
     * @param userDetails the authenticated principal injected by Spring Security
     * @param request the validated creation payload
     * @return {@link ResponseEntity} containing HTTP 201 Created and the new {@link OrganizationResponse}
     */
    @PostMapping
    @Operation(summary = "Create a new organization",
        description = "Creates a new organization with a unique slug. The authenticated user becomes the founding owner.",
        security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Organization created successfully",
            content = @Content(schema = @Schema(implementation = OrganizationResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failure — invalid or missing fields",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "409", description = "Organization with the given slug already exists",
            content = @Content)
    })
    public ResponseEntity<OrganizationResponse> createOrganization(
            @AuthenticationPrincipal DevFlowUserDetails userDetails,
            @Valid @RequestBody CreateOrganizationRequest request
    ) {
        Organization organization = organizationService.createOrganization(
                request.getName(),
                request.getSlug(),
                request.getDescription(),
                userDetails.getId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(organization));
    }

    /**
     * Updates the mutable fields of an existing organization.
     *
     * <p>Slug is not modifiable through this endpoint. Only {@code name} and
     * {@code description} may be updated. Requires ADMIN or OWNER role.
     *
     * @param id the UUID of the organization to update
     * @param userDetails the authenticated principal injected by Spring Security
     * @param request the validated update payload
     * @return {@link ResponseEntity} containing HTTP 200 OK and the updated {@link OrganizationResponse}
     */
    @PatchMapping("/{id}")
    @Operation(summary = "Update an organization",
        description = "Updates the name and/or description of an existing organization. Requires ADMIN or OWNER role.",
        security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Organization updated successfully",
            content = @Content(schema = @Schema(implementation = OrganizationResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failure — invalid or missing fields",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires ADMIN or OWNER role",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Organization not found",
            content = @Content)
    })
    public ResponseEntity<OrganizationResponse> updateOrganization(
            @PathVariable UUID id,
            @AuthenticationPrincipal DevFlowUserDetails userDetails,
            @Valid @RequestBody UpdateOrganizationRequest request
    ) {
        Organization organization = organizationService.updateOrganization(
                id,
                request.getName(),
                request.getDescription(),
                userDetails.getId()
        );
        return ResponseEntity.ok(toResponse(organization));
    }

    /**
     * Deletes an organization by its unique identifier. Requires OWNER role.
     *
     * @param id the UUID of the organization to delete
     * @param userDetails the authenticated principal injected by Spring Security
     * @return {@link ResponseEntity} containing HTTP 204 No Content
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an organization",
        description = "Permanently deletes the specified organization. Requires OWNER role.",
        security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Organization deleted successfully",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires OWNER role",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Organization not found",
            content = @Content)
    })
    public ResponseEntity<Void> deleteOrganization(
            @PathVariable UUID id,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        organizationService.deleteOrganization(id, userDetails.getId());
        return ResponseEntity.noContent().build();
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Retrieves an organization by its unique identifier. Requires active membership.
     *
     * @param id the UUID of the organization
     * @param userDetails the authenticated principal injected by Spring Security
     * @return {@link ResponseEntity} containing HTTP 200 OK and the {@link OrganizationResponse}
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get organization by ID",
        description = "Retrieves a single organization by its unique identifier. Requires active membership.",
        security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Organization retrieved successfully",
            content = @Content(schema = @Schema(implementation = OrganizationResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires active membership",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Organization not found",
            content = @Content)
    })
    public ResponseEntity<OrganizationResponse> getOrganizationById(
            @PathVariable UUID id,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        Organization organization = organizationService.getOrganizationById(id, userDetails.getId());
        return ResponseEntity.ok(toResponse(organization));
    }

    /**
     * Retrieves an organization by its unique, URL-safe slug. Requires active membership.
     *
     * @param slug the slug to query (e.g., {@code "acme-corp"})
     * @param userDetails the authenticated principal injected by Spring Security
     * @return {@link ResponseEntity} containing HTTP 200 OK and the {@link OrganizationResponse}
     */
    @GetMapping("/slug/{slug}")
    @Operation(summary = "Get organization by slug",
        description = "Retrieves a single organization by its unique URL-safe slug. Requires active membership.",
        security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Organization retrieved successfully",
            content = @Content(schema = @Schema(implementation = OrganizationResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden — requires active membership",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Organization not found for the given slug",
            content = @Content)
    })
    public ResponseEntity<OrganizationResponse> getOrganizationBySlug(
            @PathVariable String slug,
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        Organization organization = organizationService.getOrganizationBySlug(slug, userDetails.getId());
        return ResponseEntity.ok(toResponse(organization));
    }

    // ── Private mapping helpers ───────────────────────────────────────────────

    /**
     * Maps an {@link Organization} domain entity to the {@link OrganizationResponse} DTO.
     *
     * @param organization the domain entity returned by the service
     * @return the corresponding API response DTO
     */
    private OrganizationResponse toResponse(Organization organization) {
        return OrganizationResponse.builder()
                .id(organization.getId())
                .name(organization.getName())
                .slug(organization.getSlug())
                .description(organization.getDescription())
                .ownerId(organization.getOwner().getId())
                .createdAt(organization.getCreatedAt())
                .updatedAt(organization.getUpdatedAt())
                .build();
    }
}
