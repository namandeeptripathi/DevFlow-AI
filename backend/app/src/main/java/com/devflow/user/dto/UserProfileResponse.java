package com.devflow.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/**
 * Data Transfer Object representing the public-facing profile data returned
 * to API clients.
 *
 * <p>Used as the response body for both:
 * <ul>
 *   <li>{@code GET  /api/v1/users/me} — retrieve current profile</li>
 *   <li>{@code PATCH /api/v1/users/me} — update current profile</li>
 * </ul>
 *
 * <p>Never exposes JPA entity internals (e.g., {@code @Version}, persistence state)
 * or authentication-owned fields ({@code email}, {@code username}, {@code passwordHash}).
 *
 * @see com.devflow.user.domain.UserProfile
 * @see com.devflow.user.controller.UserProfileController
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class UserProfileResponse {

    /** Unique identifier of the profile record. */
    private UUID id;

    /** UUID of the owning {@link com.devflow.user.domain.User}. */
    private UUID userId;

    private String displayName;

    private String firstName;

    private String lastName;

    private String bio;

    /** Fully-qualified URL to the user's avatar asset; {@code null} if not set. */
    private String avatarUrl;

    /** Timestamp at which this profile record was first created. */
    private Instant createdAt;

    /** Timestamp of the most recent update to this profile record. */
    private Instant updatedAt;
}
