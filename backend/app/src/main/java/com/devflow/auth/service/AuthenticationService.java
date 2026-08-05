package com.devflow.auth.service;

import com.devflow.auth.dto.AuthenticationResponse;
import com.devflow.auth.dto.LoginRequest;
import com.devflow.auth.dto.RefreshTokenRequest;
import com.devflow.auth.dto.RegisterRequest;
import com.devflow.auth.exception.AccountLockedException;
import com.devflow.auth.exception.EmailNotVerifiedException;
import com.devflow.auth.exception.InvalidCredentialsException;
import com.devflow.auth.exception.UserAlreadyExistsException;
import com.devflow.security.jwt.JwtProperties;
import com.devflow.security.jwt.JwtTokenProvider;
import com.devflow.security.refresh.RefreshToken;
import com.devflow.security.refresh.RefreshTokenService;
import com.devflow.security.user.CustomUserDetailsService;
import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Primary domain service orchestrating the authentication lifecycle for DevFlow.
 *
 * <p>Encapsulates all authentication workflows:
 * <ul>
 *   <li>User registration with uniqueness validation and password hashing</li>
 *   <li>Credential authentication by email or username with status checks</li>
 *   <li>Refresh token rotation and access token reissue</li>
 *   <li>Single-session and multi-device logout</li>
 * </ul>
 *
 * <h2>Architectural Boundary</h2>
 * <p>Future REST controllers will delegate to this service exclusively.
 * Controllers will perform zero direct database, repository, or token operations.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final CustomUserDetailsService userDetailsService;
    private final JwtProperties jwtProperties;
    private final com.devflow.auth.verification.EmailVerificationService emailVerificationService;

    public AuthenticationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenService refreshTokenService,
            CustomUserDetailsService userDetailsService,
            JwtProperties jwtProperties,
            com.devflow.auth.verification.EmailVerificationService emailVerificationService
    ) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.jwtTokenProvider = Objects.requireNonNull(jwtTokenProvider, "jwtTokenProvider must not be null");
        this.refreshTokenService = Objects.requireNonNull(refreshTokenService, "refreshTokenService must not be null");
        this.userDetailsService = Objects.requireNonNull(userDetailsService, "userDetailsService must not be null");
        this.jwtProperties = Objects.requireNonNull(jwtProperties, "jwtProperties must not be null");
        this.emailVerificationService = Objects.requireNonNull(emailVerificationService, "emailVerificationService must not be null");
    }

    /**
     * Registers a new user identity in the platform and generates an initial email verification token.
     *
     * @param request user registration payload
     * @param ipAddress client IP address
     * @param userAgent client browser/device User-Agent string
     * @return {@link AuthenticationResponse} containing tokens and user metadata
     * @throws UserAlreadyExistsException if email or username is already registered
     */
    @Transactional
    public AuthenticationResponse register(RegisterRequest request, String ipAddress, String userAgent) {
        Objects.requireNonNull(request, "RegisterRequest must not be null");

        String normalizedEmail = request.getEmail().trim().toLowerCase();
        String normalizedUsername = request.getUsername().trim();

        if (userRepository.existsByEmail(normalizedEmail)) {
            log.warn("Registration rejected: email [{}] is already registered", normalizedEmail);
            throw new UserAlreadyExistsException("User already exists with email: " + normalizedEmail);
        }

        if (userRepository.existsByUsername(normalizedUsername)) {
            log.warn("Registration rejected: username [{}] is already taken", normalizedUsername);
            throw new UserAlreadyExistsException("User already exists with username: " + normalizedUsername);
        }

        String encodedPassword = passwordEncoder.encode(request.getPassword());

        User user = User.builder()
                .email(normalizedEmail)
                .username(normalizedUsername)
                .passwordHash(encodedPassword)
                .emailVerified(false)
                .accountStatus(AccountStatus.PENDING_VERIFICATION)
                .build();

        User savedUser = userRepository.save(user);
        log.info("Registered new user identity ID [{}] email [{}]", savedUser.getId(), savedUser.getEmail());

        // Automatically generate database-backed email verification token
        String verificationToken = emailVerificationService.generateVerificationToken(savedUser);
        log.debug("Generated email verification token for user [{}]", savedUser.getId());

        DevFlowUserDetails userDetails = new DevFlowUserDetails(savedUser);
        String accessToken = jwtTokenProvider.generateAccessToken(userDetails);
        String refreshToken = refreshTokenService.createRefreshToken(
                savedUser,
                request.getDeviceId(),
                request.getDeviceName(),
                ipAddress,
                userAgent
        );

        return buildAuthResponse(accessToken, refreshToken, savedUser);
    }

    /**
     * Authenticates a user by email or username credentials.
     *
     * @param request login credential payload
     * @param ipAddress client IP address
     * @param userAgent client browser/device User-Agent string
     * @return {@link AuthenticationResponse} containing issued tokens
     * @throws InvalidCredentialsException if login fails or credentials do not match
     * @throws AccountLockedException if account is locked or suspended
     * @throws EmailNotVerifiedException if account status requires email verification
     */
    @Transactional
    public AuthenticationResponse authenticate(LoginRequest request, String ipAddress, String userAgent) {
        Objects.requireNonNull(request, "LoginRequest must not be null");

        String identifier = request.getLogin().trim();

        User user = userRepository.findByEmailOrUsername(identifier, identifier)
                .orElseThrow(() -> {
                    log.warn("Authentication failed: user identifier [{}] not found", identifier);
                    return new InvalidCredentialsException("Invalid username/email or password");
                });

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("Authentication failed: invalid password for user [{}]", user.getId());
            throw new InvalidCredentialsException("Invalid username/email or password");
        }

        if (user.getAccountStatus() == AccountStatus.LOCKED || user.getAccountStatus() == AccountStatus.SUSPENDED) {
            log.warn("Authentication rejected: account [{}] status is {}", user.getId(), user.getAccountStatus());
            throw new AccountLockedException("Account is locked or suspended");
        }

        if (user.getAccountStatus() == AccountStatus.INACTIVE) {
            log.warn("Authentication rejected: account [{}] is inactive", user.getId());
            throw new InvalidCredentialsException("Account is inactive");
        }

        if (user.getAccountStatus() == AccountStatus.PENDING_VERIFICATION || !user.isEmailVerified()) {
            log.warn("Authentication rejected: account [{}] email is not verified", user.getId());
            throw new EmailNotVerifiedException("Email address has not been verified. Please verify your email to log in.");
        }

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        DevFlowUserDetails userDetails = new DevFlowUserDetails(user);
        String accessToken = jwtTokenProvider.generateAccessToken(userDetails);
        String refreshToken = refreshTokenService.createRefreshToken(
                user,
                request.getDeviceId(),
                request.getDeviceName(),
                ipAddress,
                userAgent
        );

        log.info("User [{}] authenticated successfully from IP [{}]", user.getId(), ipAddress);
        return buildAuthResponse(accessToken, refreshToken, user);
    }

    /**
     * Renews access and refresh tokens using Refresh Token Rotation.
     *
     * @param request refresh token request payload
     * @param ipAddress client IP address
     * @param userAgent client browser/device User-Agent string
     * @return {@link AuthenticationResponse} containing new tokens
     */
    @Transactional
    public AuthenticationResponse refreshAuthentication(RefreshTokenRequest request, String ipAddress, String userAgent) {
        Objects.requireNonNull(request, "RefreshTokenRequest must not be null");

        String newRefreshToken = refreshTokenService.rotateRefreshToken(request.getRefreshToken(), ipAddress, userAgent);

        RefreshToken refreshTokenEntity = refreshTokenService.validateRefreshToken(newRefreshToken)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid refresh token"));

        User user = refreshTokenEntity.getUser();
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        String newAccessToken = jwtTokenProvider.generateAccessToken(userDetails);

        log.debug("Refreshed access token for user [{}]", user.getId());
        return buildAuthResponse(newAccessToken, newRefreshToken, user);
    }

    /**
     * Revokes a single refresh token session (logout current device).
     *
     * @param refreshToken raw refresh token string to revoke
     */
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.trim().isEmpty()) {
            refreshTokenService.revokeRefreshToken(refreshToken.trim());
            log.info("Logged out refresh token session");
        }
    }

    /**
     * Revokes all active refresh token sessions for a specified user (logout all devices).
     *
     * @param userId user UUID
     */
    @Transactional
    public void logoutAllDevices(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");

        refreshTokenService.revokeAllUserTokens(userId);
        log.info("Logged out all device sessions for user [{}]", userId);
    }

    private AuthenticationResponse buildAuthResponse(String accessToken, String refreshToken, User user) {
        return AuthenticationResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtProperties.getAccessTokenExpiration())
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .build();
    }
}
