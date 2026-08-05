package com.devflow.auth.controller;

import com.devflow.auth.dto.AuthenticationResponse;
import com.devflow.auth.dto.LoginRequest;
import com.devflow.auth.dto.RefreshTokenRequest;
import com.devflow.auth.dto.RegisterRequest;
import com.devflow.auth.service.AuthenticationService;
import com.devflow.security.user.DevFlowUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * REST Controller exposing public and authenticated endpoints for identity management.
 *
 * <p>Base URI path: {@code /api/v1/auth}
 *
 * <h2>Design Constraints</h2>
 * <ul>
 *   <li>Thin controller: Delegates all identity workflows strictly to {@link AuthenticationService}.</li>
 *   <li>Zero direct repository, password encoding, JWT, or database access.</li>
 *   <li>Exception propagation: Contains no try/catch blocks; delegates error mapping to global exception handling.</li>
 *   <li>Constructor injection only.</li>
 * </ul>
 *
 * @see AuthenticationService
 * @see <a href="../../../../../docs/api/API_STANDARDS.md">API Design Standards</a>
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Authentication & Identity Management APIs")
public class AuthController {

    private final AuthenticationService authenticationService;

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = Objects.requireNonNull(authenticationService, "authenticationService must not be null");
    }

    /**
     * Registers a new user identity within the platform.
     *
     * @param request user registration payload
     * @param servletRequest HTTP request for IP and User-Agent resolution
     * @return {@link ResponseEntity} containing HTTP 201 Created and {@link AuthenticationResponse}
     */
    @PostMapping("/register")
    @Operation(summary = "Register a new user", description = "Registers a new user identity and returns authentication tokens.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "User registered successfully",
            content = @Content(schema = @Schema(implementation = AuthenticationResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request payload or validation failure"),
        @ApiResponse(responseCode = "409", description = "Email or username already exists")
    })
    public ResponseEntity<AuthenticationResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest servletRequest
    ) {
        AuthenticationResponse response = authenticationService.register(
                request,
                getClientIp(servletRequest),
                getClientUserAgent(servletRequest)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Authenticates a user by email or username credentials.
     *
     * @param request login credential payload
     * @param servletRequest HTTP request for IP and User-Agent resolution
     * @return {@link ResponseEntity} containing HTTP 200 OK and {@link AuthenticationResponse}
     */
    @PostMapping("/login")
    @Operation(summary = "Authenticate user", description = "Authenticates user credentials and issues access and refresh tokens.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Authentication successful",
            content = @Content(schema = @Schema(implementation = AuthenticationResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request payload"),
        @ApiResponse(responseCode = "401", description = "Invalid credentials"),
        @ApiResponse(responseCode = "403", description = "Account is locked, suspended, or unverified")
    })
    public ResponseEntity<AuthenticationResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest
    ) {
        AuthenticationResponse response = authenticationService.authenticate(
                request,
                getClientIp(servletRequest),
                getClientUserAgent(servletRequest)
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Renews access token using a valid refresh token (Refresh Token Rotation).
     *
     * @param request refresh token payload
     * @param servletRequest HTTP request for IP and User-Agent resolution
     * @return {@link ResponseEntity} containing HTTP 200 OK and {@link AuthenticationResponse}
     */
    @PostMapping("/refresh")
    @Operation(summary = "Refresh authentication tokens", description = "Rotates refresh token and issues a new access token.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Token refresh successful",
            content = @Content(schema = @Schema(implementation = AuthenticationResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid or expired refresh token"),
        @ApiResponse(responseCode = "401", description = "Revoked or compromised refresh token")
    })
    public ResponseEntity<AuthenticationResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest request,
            HttpServletRequest servletRequest
    ) {
        AuthenticationResponse response = authenticationService.refreshAuthentication(
                request,
                getClientIp(servletRequest),
                getClientUserAgent(servletRequest)
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Revokes the current device's refresh token session.
     *
     * @param request refresh token payload to revoke
     * @return {@link ResponseEntity} containing HTTP 204 No Content
     */
    @PostMapping("/logout")
    @Operation(summary = "Logout current device", description = "Revokes the specified refresh token session.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Successfully logged out current device session"),
        @ApiResponse(responseCode = "400", description = "Invalid request payload")
    })
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authenticationService.logout(request.getRefreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * Revokes all active refresh token sessions for the authenticated user.
     *
     * @param userDetails authenticated principal
     * @return {@link ResponseEntity} containing HTTP 204 No Content
     */
    @PostMapping("/logout-all")
    @Operation(summary = "Logout all devices", description = "Revokes all active refresh token sessions across all devices for the authenticated user.",
        security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Successfully logged out all device sessions"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - Valid Bearer token required")
    })
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal DevFlowUserDetails userDetails) {
        authenticationService.logoutAllDevices(userDetails.getId());
        return ResponseEntity.noContent().build();
    }

    private String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "127.0.0.1";
        }
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xForwardedFor)) {
            return xForwardedFor.split(",")[0].trim();
        }
        return StringUtils.hasText(request.getRemoteAddr()) ? request.getRemoteAddr() : "127.0.0.1";
    }

    private String getClientUserAgent(HttpServletRequest request) {
        if (request == null) {
            return "Unknown";
        }
        String userAgent = request.getHeader("User-Agent");
        return StringUtils.hasText(userAgent) ? userAgent : "Unknown";
    }
}
