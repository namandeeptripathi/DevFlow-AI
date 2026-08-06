package com.devflow.user.controller;

import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.user.domain.UserPreferences;
import com.devflow.user.dto.NotificationPreferencesDto;
import com.devflow.user.dto.UpdateUserPreferencesRequest;
import com.devflow.user.dto.UserPreferencesResponse;
import com.devflow.user.service.UserPreferencesService;
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
 * REST Controller exposing authenticated endpoints for user application preferences management.
 *
 * <p>Base URI path: {@code /api/v1/users/me/preferences}
 *
 * <h2>Design Constraints</h2>
 * <ul>
 *   <li>Thin controller: Delegates all preference retrieval and mutation exclusively to {@link UserPreferencesService}.</li>
 *   <li>Exception propagation: Delegates error mapping to {@link com.devflow.exception.GlobalExceptionHandler}.</li>
 *   <li>All endpoints require a valid Bearer JWT.</li>
 * </ul>
 *
 * @see UserPreferencesService
 * @see UserPreferencesResponse
 * @see UpdateUserPreferencesRequest
 */
@RestController
@RequestMapping("/api/v1/users/me/preferences")
@Tag(name = "User Preferences", description = "Authenticated user preferences retrieval and update APIs")
public class UserPreferencesController {

    private final UserPreferencesService userPreferencesService;

    public UserPreferencesController(UserPreferencesService userPreferencesService) {
        this.userPreferencesService = Objects.requireNonNull(
                userPreferencesService, "userPreferencesService must not be null");
    }

    /**
     * Retrieves the authenticated user's preferences.
     *
     * @param userDetails the authenticated principal
     * @return {@link ResponseEntity} containing HTTP 200 OK and {@link UserPreferencesResponse}
     */
    @GetMapping
    @Operation(
        summary = "Get current user preferences",
        description = "Retrieves the authenticated user's UI theme, timezone, language, date/time format, and notification preferences.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Preferences retrieved successfully",
            content = @Content(schema = @Schema(implementation = UserPreferencesResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "User or preferences record not found",
            content = @Content)
    })
    public ResponseEntity<UserPreferencesResponse> getMyPreferences(
            @AuthenticationPrincipal DevFlowUserDetails userDetails
    ) {
        UserPreferences preferences = userPreferencesService.getPreferences(userDetails.getId());
        return ResponseEntity.ok(toResponse(preferences));
    }

    /**
     * Updates the authenticated user's preferences.
     *
     * @param userDetails the authenticated principal
     * @param request     the validated preferences update payload
     * @return {@link ResponseEntity} containing HTTP 200 OK and updated {@link UserPreferencesResponse}
     */
    @PatchMapping
    @Operation(
        summary = "Update current user preferences",
        description = """
                Applies a partial update to the authenticated user's preferences. \
                Only provided (non-null) fields are updated; omitted fields retain their existing values. \
                Timezone strings must be valid IANA ZoneId identifiers.\
                """,
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Preferences updated successfully",
            content = @Content(schema = @Schema(implementation = UserPreferencesResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request payload or invalid timezone identifier",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content),
        @ApiResponse(responseCode = "404", description = "User or preferences record not found",
            content = @Content)
    })
    public ResponseEntity<UserPreferencesResponse> updateMyPreferences(
            @AuthenticationPrincipal DevFlowUserDetails userDetails,
            @Valid @RequestBody UpdateUserPreferencesRequest request
    ) {
        UserPreferences updated = userPreferencesService.updatePreferences(userDetails.getId(), request);
        return ResponseEntity.ok(toResponse(updated));
    }

    // ── Private mapping helpers ───────────────────────────────────────────────

    private UserPreferencesResponse toResponse(UserPreferences preferences) {
        NotificationPreferencesDto notificationDto = userPreferencesService
                .mapNotificationMapToDto(preferences.getNotificationPreferences());

        return UserPreferencesResponse.builder()
                .id(preferences.getId())
                .userId(preferences.getUser().getId())
                .theme(preferences.getTheme())
                .timezone(preferences.getTimezone())
                .language(preferences.getLanguage())
                .dateFormat(preferences.getDateFormat())
                .timeFormat(preferences.getTimeFormat())
                .notificationPreferences(notificationDto)
                .createdAt(preferences.getCreatedAt())
                .updatedAt(preferences.getUpdatedAt())
                .build();
    }
}
