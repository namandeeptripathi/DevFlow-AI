package com.devflow.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Data Transfer Object representing the {@code POST /api/v1/organizations}
 * request body.
 *
 * <p>
 * Captures the minimum required fields to create a new organization. Validation
 * constraints mirror those declared on
 * {@link com.devflow.organization.domain.Organization}
 * so that invalid payloads are rejected at the HTTP layer before the service is
 * invoked.
 *
 * <p>
 * The owner is determined strictly from the authenticated security context —
 * callers
 * cannot specify an owner ID.
 *
 * @see com.devflow.organization.controller.OrganizationController
 * @see com.devflow.organization.service.OrganizationService
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class CreateOrganizationRequest {

    @NotBlank(message = "Organization name is required")
    @Size(max = 100, message = "Organization name cannot exceed 100 characters")
    private String name;

    @NotBlank(message = "Organization slug is required")
    @Size(max = 120, message = "Organization slug cannot exceed 120 characters")
    @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Slug must be lowercase alphanumeric with hyphens (e.g., 'my-org')")
    private String slug;

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    private String description;
}
