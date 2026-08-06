package com.devflow.user.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Data Transfer Object representing the {@code PATCH /api/v1/users/me} request body.
 *
 * <p>All fields are optional. A {@code null} field signals that the caller does not
 * wish to update that attribute — the existing value on the profile will be preserved.
 * This enables clean PATCH semantics without the caller needing to re-supply every field.
 *
 * <p>Validation constraints mirror those declared on
 * {@link com.devflow.user.domain.UserProfile} so that length violations are caught at the
 * HTTP layer, before the service is invoked.
 *
 * <p>Fields that must never be mutated via this endpoint ({@code email}, {@code username},
 * {@code passwordHash}, {@code avatarUrl}) are intentionally absent.
 *
 * @see com.devflow.user.service.UpdateProfileRequest
 * @see com.devflow.user.controller.UserProfileController
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class UpdateUserProfileRequest {

    @Size(max = 100, message = "Display name cannot exceed 100 characters")
    private String displayName;

    @Size(max = 100, message = "First name cannot exceed 100 characters")
    private String firstName;

    @Size(max = 100, message = "Last name cannot exceed 100 characters")
    private String lastName;

    @Size(max = 1000, message = "Bio cannot exceed 1000 characters")
    private String bio;
}
