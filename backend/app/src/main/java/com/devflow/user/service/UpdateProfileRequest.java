package com.devflow.user.service;

import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Immutable command object carrying the fields a user is permitted to update on their profile.
 *
 * <p>Only the four mutable profile fields are exposed here. Authentication-owned fields
 * ({@code email}, {@code username}, {@code passwordHash}) and asset fields ({@code avatarUrl})
 * are intentionally absent and must never be mutated via this path.
 *
 * <p>All validation annotations mirror the constraints declared on
 * {@link com.devflow.user.domain.UserProfile} so that validation semantics are consistent
 * across both the domain model and the service layer.
 *
 * @see UserProfileService
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {

    @Size(max = 100, message = "Display name cannot exceed 100 characters")
    private String displayName;

    @Size(max = 100, message = "First name cannot exceed 100 characters")
    private String firstName;

    @Size(max = 100, message = "Last name cannot exceed 100 characters")
    private String lastName;

    @Size(max = 1000, message = "Bio cannot exceed 1000 characters")
    private String bio;
}
