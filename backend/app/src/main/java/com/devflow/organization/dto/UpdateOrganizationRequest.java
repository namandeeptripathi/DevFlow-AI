package com.devflow.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Data Transfer Object representing the {@code PATCH /api/v1/organizations/{id}} request body.
 *
 * <p>Only mutable organization fields are included. The {@code slug} is intentionally
 * absent because it is immutable after creation to preserve URL stability and external
 * reference integrity.
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
public class UpdateOrganizationRequest {

    @NotBlank(message = "Organization name is required")
    @Size(max = 100, message = "Organization name cannot exceed 100 characters")
    private String name;

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    private String description;
}
