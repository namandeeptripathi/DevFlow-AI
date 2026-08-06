package com.devflow.user.controller;

import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.user.domain.UserProfile;
import com.devflow.user.dto.UpdateUserProfileRequest;
import com.devflow.user.dto.UserProfileResponse;
import com.devflow.user.service.AvatarService;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
    private final AvatarService avatarService;

    public UserProfileController(
            UserProfileService userProfileService,
            AvatarService avatarService
    ) {
        this.userProfileService = Objects.requireNonNull(
                userProfileService, "userProfileService must not be null");
        this.avatarService = Objects.requireNonNull(
                avatarService, "avatarService must not be null");
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

    /**
     * Uploads or replaces the authenticated user's avatar.
     *
     * <p>Accepts a single image file via {@code multipart/form-data}.
     * Accepted formats: JPEG, PNG, GIF, WebP. Maximum size: 5 MB.
     * If an avatar already exists it is replaced and the previous file is removed.
     *
     * @param userDetails the authenticated principal
     * @param file        the avatar image file
     * @return {@link ResponseEntity} containing HTTP 200 OK and the updated {@link UserProfileResponse}
     */
    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
        summary = "Upload or replace avatar",
        description = """
                Uploads a new avatar image for the authenticated user. \
                Accepted formats: JPEG, PNG, GIF, WebP. Maximum size: 5 MB. \
                If an avatar already exists it is replaced atomically and the previous file is removed.\
                """,
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Avatar uploaded successfully",
            content = @Content(schema = @Schema(implementation = UserProfileResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid file — unsupported type, exceeds 5 MB, or empty",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Profile not found for the authenticated user",
            content = @Content)
    })
    public ResponseEntity<UserProfileResponse> uploadAvatar(
            @AuthenticationPrincipal DevFlowUserDetails userDetails,
            @RequestParam("file") MultipartFile file
    ) {
        UserProfile updated = avatarService.uploadAvatar(userDetails.getId(), file);
        return ResponseEntity.ok(toResponse(updated));
    }

    /**
     * Removes the authenticated user's avatar.
     *
     * <p>Deletes the avatar file from storage and clears {@code avatarUrl} on the profile.
     * If no avatar is currently set the call succeeds silently.
     *
     * @param userDetails the authenticated principal
     * @return {@link ResponseEntity} containing HTTP 204 No Content
     */
    @DeleteMapping("/me/avatar")
    @Operation(
        summary = "Delete avatar",
        description = "Removes the authenticated user's avatar image. No-op if no avatar is currently set.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Avatar deleted successfully",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "Profile not found for the authenticated user",
            content = @Content)
    })
    public ResponseEntity<Void> deleteAvatar(
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        avatarService.deleteAvatar(userDetails.getId());
        return ResponseEntity.noContent().build();
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
