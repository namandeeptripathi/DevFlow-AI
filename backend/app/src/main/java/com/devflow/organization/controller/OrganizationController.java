package com.devflow.organization.controller;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.dto.CreateOrganizationRequest;
import com.devflow.organization.dto.OrganizationResponse;
import com.devflow.organization.dto.UpdateOrganizationRequest;
import com.devflow.organization.service.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * REST Controller exposing endpoints for organization lifecycle management.
 *
 * <p>Base URI path: {@code /api/v1/organizations}
 *
 * <h2>Design Constraints</h2>
 * <ul>
 *   <li>Thin controller: Delegates all domain logic exclusively to {@link OrganizationService}.</li>
 *   <li>Zero direct repository, entity, or database access.</li>
 *   <li>Exception propagation: Contains no try/catch blocks; delegates error mapping to
 *       {@link com.devflow.exception.GlobalExceptionHandler}.</li>
 *   <li>Constructor injection only.</li>
 *   <li>Entity-to-DTO mapping is performed in a private helper method, keeping handler
 *       methods concise and readable.</li>
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
     * Creates a new organization.
     *
     * @param request the validated creation payload
     * @return {@link ResponseEntity} containing HTTP 201 Created and the new {@link OrganizationResponse}
     */
    @PostMapping
    @Operation(summary = "Create a new organization",
        description = "Creates a new organization with a unique slug. The owner must be an existing user.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Organization created successfully",
            content = @Content(schema = @Schema(implementation = OrganizationResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failure — invalid or missing fields",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Owner user not found",
            content = @Content),
        @ApiResponse(responseCode = "409", description = "Organization with the given slug already exists",
            content = @Content)
    })
    public ResponseEntity<OrganizationResponse> createOrganization(
            @Valid @RequestBody CreateOrganizationRequest request
    ) {
        Organization organization = organizationService.createOrganization(
                request.getName(),
                request.getSlug(),
                request.getDescription(),
                request.getOwnerId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(organization));
    }

    /**
     * Updates the mutable fields of an existing organization.
     *
     * <p>Slug is not modifiable through this endpoint. Only {@code name} and
     * {@code description} may be updated.
     *
     * @param id      the UUID of the organization to update
     * @param request the validated update payload
     * @return {@link ResponseEntity} containing HTTP 200 OK and the updated {@link OrganizationResponse}
     */
    @PatchMapping("/{id}")
    @Operation(summary = "Update an organization",
        description = "Updates the name and/or description of an existing organization. Slug is immutable.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Organization updated successfully",
            content = @Content(schema = @Schema(implementation = OrganizationResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failure — invalid or missing fields",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Organization not found",
            content = @Content)
    })
    public ResponseEntity<OrganizationResponse> updateOrganization(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateOrganizationRequest request
    ) {
        Organization organization = organizationService.updateOrganization(
                id,
                request.getName(),
                request.getDescription()
        );
        return ResponseEntity.ok(toResponse(organization));
    }

    /**
     * Deletes an organization by its unique identifier.
     *
     * @param id the UUID of the organization to delete
     * @return {@link ResponseEntity} containing HTTP 204 No Content
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an organization",
        description = "Permanently deletes the specified organization.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Organization deleted successfully",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Organization not found",
            content = @Content)
    })
    public ResponseEntity<Void> deleteOrganization(@PathVariable UUID id) {
        organizationService.deleteOrganization(id);
        return ResponseEntity.noContent().build();
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Retrieves an organization by its unique identifier.
     *
     * @param id the UUID of the organization
     * @return {@link ResponseEntity} containing HTTP 200 OK and the {@link OrganizationResponse}
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get organization by ID",
        description = "Retrieves a single organization by its unique identifier.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Organization retrieved successfully",
            content = @Content(schema = @Schema(implementation = OrganizationResponse.class))),
        @ApiResponse(responseCode = "404", description = "Organization not found",
            content = @Content)
    })
    public ResponseEntity<OrganizationResponse> getOrganizationById(@PathVariable UUID id) {
        Organization organization = organizationService.getOrganizationById(id);
        return ResponseEntity.ok(toResponse(organization));
    }

    /**
     * Retrieves an organization by its unique, URL-safe slug.
     *
     * @param slug the slug to query (e.g., {@code "acme-corp"})
     * @return {@link ResponseEntity} containing HTTP 200 OK and the {@link OrganizationResponse}
     */
    @GetMapping("/slug/{slug}")
    @Operation(summary = "Get organization by slug",
        description = "Retrieves a single organization by its unique URL-safe slug.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Organization retrieved successfully",
            content = @Content(schema = @Schema(implementation = OrganizationResponse.class))),
        @ApiResponse(responseCode = "404", description = "Organization not found for the given slug",
            content = @Content)
    })
    public ResponseEntity<OrganizationResponse> getOrganizationBySlug(@PathVariable String slug) {
        Organization organization = organizationService.getOrganizationBySlug(slug);
        return ResponseEntity.ok(toResponse(organization));
    }

    // ── Private mapping helpers ───────────────────────────────────────────────

    /**
     * Maps an {@link Organization} domain entity to the {@link OrganizationResponse} DTO.
     *
     * <p>Only fields safe for public API exposure are included. JPA internals
     * ({@code @Version}), the full {@link com.devflow.user.domain.User} entity graph,
     * and persistence state are never surfaced in the response.
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
