package com.devflow.user.controller;

import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.user.domain.UserProfile;
import com.devflow.user.dto.UpdateUserProfileRequest;
import com.devflow.user.dto.UserProfileResponse;
import com.devflow.user.service.UpdateProfileRequest;
import com.devflow.user.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * REST Controller exposing authenticated endpoints for user profile management.
 *
 * <p>Base URI path: {@code /api/v1/users}
 *
 * <h2>Design Constraints</h2>
 * <ul>
 *   <li>Thin controller: Delegates all domain logic exclusively to {@link UserProfileService}.</li>
 *   <li>Zero direct repository, entity, or database access.</li>
 *   <li>Exception propagation: Contains no try/catch blocks; delegates error mapping to
 *       {@link com.devflow.exception.GlobalExceptionHandler}.</li>
 *   <li>Constructor injection only.</li>
 *   <li>All endpoints require a valid Bearer JWT — enforced by the global Security Filter Chain.</li>
 *   <li>DTO-to-service-command mapping and entity-to-DTO mapping are performed in private
 *       helper methods, keeping handler methods concise and readable.</li>
 * </ul>
 *
 * @see UserProfileService
 * @see com.devflow.user.dto.UserProfileResponse
 * @see com.devflow.user.dto.UpdateUserProfileRequest
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "User Profile", description = "Authenticated user profile retrieval and update APIs")
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = Objects.requireNonNull(
                userProfileService, "userProfileService must not be null");
    }

    /**
     * Retrieves the authenticated user's own profile.
     *
     * @param userDetails the authenticated principal injected by Spring Security
     * @return {@link ResponseEntity} containing HTTP 200 OK and the user's {@link UserProfileResponse}
     */
    @GetMapping("/me")
    @Operation(
        summary = "Get current user profile",
        description = "Retrieves the authenticated user's public-facing profile information.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Profile retrieved successfully",
            content = @Content(schema = @Schema(implementation = UserProfileResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Profile not found for the authenticated user",
            content = @Content)
    })
    public ResponseEntity<UserProfileResponse> getMyProfile(
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        UserProfile profile = userProfileService.getProfile(userDetails.getId());
        return ResponseEntity.ok(toResponse(profile));
    }

    /**
     * Updates the authenticated user's own profile.
     *
     * <p>Supports partial updates: only fields present in the request body (non-null)
     * are applied. Omitted fields retain their current values.
     *
     * @param userDetails the authenticated principal injected by Spring Security
     * @param request     the validated update payload
     * @return {@link ResponseEntity} containing HTTP 200 OK and the updated {@link UserProfileResponse}
     */
    @PatchMapping("/me")
    @Operation(
        summary = "Update current user profile",
        description = """
                Applies a partial update to the authenticated user's profile. \
                Only the fields included in the request body are updated; \
                omitted fields are preserved as-is. \
                Authentication-owned fields (email, username, password) and avatar cannot be changed here.\
                """,
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Profile updated successfully",
            content = @Content(schema = @Schema(implementation = UserProfileResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failure — one or more fields exceed maximum length",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Profile not found for the authenticated user",
            content = @Content)
    })
    public ResponseEntity<UserProfileResponse> updateMyProfile(
            @AuthenticationPrincipal DevFlowUserDetails userDetails,
            @Valid @RequestBody UpdateUserProfileRequest request
    ) {
        UpdateProfileRequest command = toCommand(request);
        UserProfile updated = userProfileService.updateProfile(userDetails.getId(), command);
        return ResponseEntity.ok(toResponse(updated));
    }

    // ── Private mapping helpers ───────────────────────────────────────────────

    /**
     * Maps an {@link UpdateUserProfileRequest} HTTP DTO to the service-layer
     * {@link UpdateProfileRequest} command object.
     *
     * @param request the validated HTTP request body
     * @return the corresponding service command
     */
    private UpdateProfileRequest toCommand(UpdateUserProfileRequest request) {
        return UpdateProfileRequest.builder()
                .displayName(request.getDisplayName())
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .bio(request.getBio())
                .build();
    }

    /**
     * Maps a {@link UserProfile} domain entity to the {@link UserProfileResponse} DTO.
     *
     * <p>Only fields safe for public API exposure are included. JPA internals
     * ({@code @Version}), authentication fields, and persistence state are never
     * surfaced in the response.
     *
     * @param profile the domain entity returned by the service
     * @return the corresponding API response DTO
     */
    private UserProfileResponse toResponse(UserProfile profile) {
        return UserProfileResponse.builder()
                .id(profile.getId())
                .userId(profile.getUser().getId())
                .displayName(profile.getDisplayName())
                .firstName(profile.getFirstName())
                .lastName(profile.getLastName())
                .bio(profile.getBio())
                .avatarUrl(profile.getAvatarUrl())
                .createdAt(profile.getCreatedAt())
                .updatedAt(profile.getUpdatedAt())
                .build();
    }
}
