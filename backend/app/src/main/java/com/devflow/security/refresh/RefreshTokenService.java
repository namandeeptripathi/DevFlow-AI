package com.devflow.security.refresh;

import com.devflow.security.jwt.JwtProperties;
import com.devflow.user.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing the lifecycle of Refresh Tokens.
 *
 * <p>Implements the Refresh Token Rotation and theft detection strategy per
 * Authentication Strategy §6.
 *
 * <h2>Security Guarantees</h2>
 * <ul>
 *   <li>Only SHA-256 hashed representations are stored in database.</li>
 *   <li>Raw tokens are generated via {@link SecureRandom} and returned to the caller once.</li>
 *   <li>Token rotation revokes the consumed token and issues a new token pair.</li>
 *   <li>Reuse of an already-revoked refresh token triggers <strong>theft detection</strong>,
 *       immediately revoking all active token sessions for the affected user.</li>
 * </ul>
 *
 * @see RefreshToken
 * @see RefreshTokenRepository
 * @see <a href="../../../../../docs/security/AUTHENTICATION_STRATEGY.md">Authentication Strategy §6</a>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            JwtProperties jwtProperties
    ) {
        this.refreshTokenRepository = Objects.requireNonNull(refreshTokenRepository, "refreshTokenRepository must not be null");
        this.jwtProperties = Objects.requireNonNull(jwtProperties, "jwtProperties must not be null");
    }

    /**
     * Creates and persists a new refresh token for a user and device session.
     *
     * @param user the user entity for whom the token is created
     * @param deviceId optional client device identifier
     * @param deviceName optional human-readable device name
     * @param ipAddress client IP address
     * @param userAgent client browser/device User-Agent string
     * @return the raw plaintext refresh token string (must be returned to client)
     */
    @Transactional
    public String createRefreshToken(
            User user,
            String deviceId,
            String deviceName,
            String ipAddress,
            String userAgent
    ) {
        Objects.requireNonNull(user, "User entity must not be null");

        String rawToken = generateRawToken();
        String tokenHash = hashToken(rawToken);

        Instant expiresAt = Instant.now().plusMillis(jwtProperties.getRefreshTokenExpiration());

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .deviceId(deviceId)
                .deviceName(deviceName)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .expiresAt(expiresAt)
                .revoked(false)
                .build();

        refreshTokenRepository.save(refreshToken);
        log.debug("Created refresh token ID [{}] for user [{}] device [{}]", refreshToken.getId(), user.getId(), deviceId);

        return rawToken;
    }

    /**
     * Validates a raw refresh token against the persistent store.
     *
     * @param rawToken the raw plaintext token string
     * @return an {@link Optional} containing the {@link RefreshToken} entity if valid and active
     */
    @Transactional(readOnly = true)
    public Optional<RefreshToken> validateRefreshToken(String rawToken) {
        if (rawToken == null || rawToken.trim().isEmpty()) {
            return Optional.empty();
        }

        String tokenHash = hashToken(rawToken.trim());
        Optional<RefreshToken> tokenOpt = refreshTokenRepository.findByTokenHash(tokenHash);

        if (tokenOpt.isEmpty()) {
            log.debug("Refresh token hash not found in repository");
            return Optional.empty();
        }

        RefreshToken token = tokenOpt.get();

        if (token.isRevoked()) {
            log.warn("Attempted to validate revoked refresh token ID [{}] for user [{}]", token.getId(), token.getUser().getId());
            return Optional.empty();
        }

        if (token.getExpiresAt().isBefore(Instant.now())) {
            log.debug("Refresh token ID [{}] has expired", token.getId());
            return Optional.empty();
        }

        return Optional.of(token);
    }

    /**
     * Revokes a specific refresh token given its raw plaintext value.
     *
     * @param rawToken the raw token to revoke
     */
    @Transactional
    public void revokeRefreshToken(String rawToken) {
        if (rawToken == null || rawToken.trim().isEmpty()) {
            return;
        }

        String tokenHash = hashToken(rawToken.trim());
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            if (!token.isRevoked()) {
                token.revoke();
                refreshTokenRepository.save(token);
                log.info("Revoked refresh token ID [{}] for user [{}]", token.getId(), token.getUser().getId());
            }
        });
    }

    /**
     * Revokes all active refresh tokens for a specified user ID across all devices.
     *
     * @param userId the user's UUID
     */
    @Transactional
    public void revokeAllUserTokens(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");

        int count = refreshTokenRepository.revokeAllByUserId(userId, Instant.now());
        log.info("Revoked all {} active refresh tokens for user [{}]", count, userId);
    }

    /**
     * Performs Refresh Token Rotation.
     *
     * <p>Invalidates the presented token and issues a new refresh token for the same user and device session.
     * If an already-revoked token is presented, <strong>theft is detected</strong> and all active tokens
     * for that user are immediately revoked.
     *
     * @param rawToken the presented raw refresh token
     * @param ipAddress client IP address of the refresh request
     * @param userAgent client User-Agent of the refresh request
     * @return the new raw plaintext refresh token string
     * @throws BadCredentialsException if the token is invalid, expired, or stolen
     */
    @Transactional
    public String rotateRefreshToken(String rawToken, String ipAddress, String userAgent) {
        if (rawToken == null || rawToken.trim().isEmpty()) {
            throw new BadCredentialsException("Invalid refresh token");
        }

        String tokenHash = hashToken(rawToken.trim());
        Optional<RefreshToken> tokenOpt = refreshTokenRepository.findByTokenHash(tokenHash);

        if (tokenOpt.isEmpty()) {
            log.warn("Rotation requested for unknown refresh token");
            throw new BadCredentialsException("Invalid refresh token");
        }

        RefreshToken token = tokenOpt.get();

        // ── Theft Detection ──────────────────────────────────────────────────────
        // Presenting an already-revoked refresh token indicates prior token interception.
        // Revoke ALL tokens for this user immediately per Authentication Strategy §6.1.
        if (token.isRevoked()) {
            log.error("THEFT DETECTED: Reuse of revoked refresh token ID [{}] for user [{}]! Revoking all sessions.",
                    token.getId(), token.getUser().getId());
            revokeAllUserTokens(token.getUser().getId());
            throw new BadCredentialsException("Invalid or revoked refresh token. All user sessions have been terminated.");
        }

        // ── Expiration check ─────────────────────────────────────────────────────
        if (token.getExpiresAt().isBefore(Instant.now())) {
            log.debug("Rotation requested for expired refresh token ID [{}]", token.getId());
            throw new BadCredentialsException("Refresh token has expired. Please log in again.");
        }

        // ── Consume existing token ───────────────────────────────────────────────
        token.revoke();
        refreshTokenRepository.save(token);

        // ── Issue new token in family ────────────────────────────────────────────
        return createRefreshToken(
                token.getUser(),
                token.getDeviceId(),
                token.getDeviceName(),
                ipAddress != null ? ipAddress : token.getIpAddress(),
                userAgent != null ? userAgent : token.getUserAgent()
        );
    }

    /**
     * Purges expired refresh tokens from database storage.
     *
     * @return the number of purged rows
     */
    @Transactional
    public int cleanupExpiredTokens() {
        int purged = refreshTokenRepository.deleteExpiredTokens(Instant.now());
        if (purged > 0) {
            log.info("Cleaned up {} expired refresh tokens", purged);
        }
        return purged;
    }

    /**
     * Generates a cryptographically strong, 64-byte random URL-safe Base64 string.
     *
     * @return 64-byte random plaintext token
     */
    private String generateRawToken() {
        byte[] randomBytes = new byte[64];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Computes the SHA-256 hash of a raw token string.
     *
     * @param rawToken plaintext raw token
     * @return hex-encoded SHA-256 hash
     */
    public static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
