package com.devflow.organization.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/**
 * Data Transfer Object representing the organization data returned to API clients.
 *
 * <p>Used as the response body for:
 * <ul>
 *   <li>{@code POST   /api/v1/organizations}          — create organization</li>
 *   <li>{@code GET    /api/v1/organizations/{id}}      — get by ID</li>
 *   <li>{@code GET    /api/v1/organizations/slug/{slug}} — get by slug</li>
 *   <li>{@code PATCH  /api/v1/organizations/{id}}      — update organization</li>
 * </ul>
 *
 * <p>Never exposes JPA entity internals (e.g., {@code @Version}, persistence state)
 * or the full {@link com.devflow.user.domain.User} entity graph. Only the owner's UUID
 * is surfaced.
 *
 * @see com.devflow.organization.domain.Organization
 * @see com.devflow.organization.controller.OrganizationController
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class OrganizationResponse {

    /** Unique identifier of the organization. */
    private UUID id;

    /** Display name of the organization. */
    private String name;

    /** URL-safe, globally unique slug. */
    private String slug;

    /** Optional human-readable description. */
    private String description;

    /** UUID of the founding owner. */
    private UUID ownerId;

    /** Timestamp at which this organization was created. */
    private Instant createdAt;

    /** Timestamp of the most recent update. */
    private Instant updatedAt;
}
