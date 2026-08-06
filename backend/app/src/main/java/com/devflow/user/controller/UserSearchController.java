package com.devflow.user.controller;

import com.devflow.user.dto.UserSearchResponse;
import com.devflow.user.service.UserSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * REST Controller exposing authenticated user search endpoints.
 *
 * <p>Base URI path: {@code /api/v1/users/search}
 *
 * @see UserSearchService
 * @see UserSearchResponse
 */
@RestController
@RequestMapping("/api/v1/users/search")
@Tag(name = "User Search", description = "Authenticated user search and discovery APIs")
public class UserSearchController {

    private final UserSearchService userSearchService;

    public UserSearchController(UserSearchService userSearchService) {
        this.userSearchService = Objects.requireNonNull(userSearchService, "userSearchService must not be null");
    }

    /**
     * Searches users by partial username or profile display name.
     *
     * @param q        partial search query string
     * @param pageable pagination parameters (page, size, sort)
     * @return {@link ResponseEntity} containing HTTP 200 OK and paged {@link UserSearchResponse}
     */
    @GetMapping
    @Operation(
        summary = "Search users",
        description = """
                Performs a case-insensitive partial match search against usernames and display names. \
                Returns paged results containing only safe public profile data.\
                """,
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "User search completed successfully",
            content = @Content(schema = @Schema(implementation = UserSearchResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid search query — query is blank, exceeds 100 characters, or size exceeds 100",
            content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
            content = @Content)
    })
    public ResponseEntity<UserSearchResponse> searchUsers(
            @Parameter(description = "Partial search term for username or display name", required = true)
            @RequestParam("q") String q,
            @PageableDefault(size = 20, sort = "username", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        UserSearchResponse response = userSearchService.searchUsers(q, pageable);
        return ResponseEntity.ok(response);
    }
}
