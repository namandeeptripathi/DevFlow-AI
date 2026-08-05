package com.devflow.auth.password;

import com.devflow.auth.exception.AuthenticationDomainException;
import com.devflow.security.refresh.RefreshTokenService;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing database-backed Password Reset Tokens and credential updates.
 *
 * <h2>Security Guarantees</h2>
 * <ul>
 *   <li>Database-backed (never stateless JWTs) for one-time use enforcement and immediate revocation capability.</li>
 *   <li>Only SHA-256 hashes are persisted; plaintext tokens are generated via {@link SecureRandom} and returned to caller once.</li>
 *   <li>Silent non-disclosure: Requesting a reset for an unregistered email returns empty without revealing user existence.</li>
 *   <li>Resetting password updates BCrypt hash, marks token used, and <strong>revokes ALL active refresh sessions</strong> for the user.</li>
 * </ul>
 *
 * @see PasswordResetToken
 * @see PasswordResetTokenRepository
 * @see RefreshTokenService
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 15-minute expiration duration in milliseconds */
    private static final long RESET_TOKEN_EXPIRATION_MS = 900000L;

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    public PasswordResetService(
            PasswordResetTokenRepository tokenRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService
    ) {
        this.tokenRepository = Objects.requireNonNull(tokenRepository, "tokenRepository must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.refreshTokenService = Objects.requireNonNull(refreshTokenService, "refreshTokenService must not be null");
    }

    /**
     * Initiates a password reset flow for a given user email address.
     *
     * <p>If the user exists, invalidates prior pending reset tokens and issues a new token.
     * If the email is unknown, returns empty without disclosing non-existence.
     *
     * @param email target user email
     * @return an {@link Optional} containing the raw plaintext token if user exists; {@link Optional#empty()} otherwise
     */
    @Transactional
    public Optional<String> requestPasswordReset(String email) {
        if (email == null || email.trim().isEmpty()) {
            return Optional.empty();
        }

        String normalizedEmail = email.trim().toLowerCase();
        Optional<User> userOpt = userRepository.findByEmail(normalizedEmail);

        if (userOpt.isEmpty()) {
            log.info("Password reset requested for unknown email address. Silent non-disclosure applied.");
            return Optional.empty();
        }

        User user = userOpt.get();

        // Invalidate prior pending reset tokens for this user
        tokenRepository.deleteByUserId(user.getId());

        String rawToken = generateRawToken();
        String tokenHash = hashToken(rawToken);

        Instant expiresAt = Instant.now().plusMillis(RESET_TOKEN_EXPIRATION_MS);

        PasswordResetToken resetToken = PasswordResetToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .expiresAt(expiresAt)
                .used(false)
                .build();

        tokenRepository.save(resetToken);
        log.info("Generated password reset token for user ID [{}]", user.getId());

        return Optional.of(rawToken);
    }

    /**
     * Resets a user's password using a valid raw reset token.
     *
     * @param rawToken raw plaintext reset token string
     * @param newPassword new raw password string
     * @return {@code true} if password reset succeeded
     * @throws AuthenticationDomainException if the token is invalid, expired, or already used
     */
    @Transactional
    public boolean resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.trim().isEmpty()) {
            throw new AuthenticationDomainException("Reset token must not be empty");
        }

        if (newPassword == null || newPassword.length() < 8) {
            throw new AuthenticationDomainException("New password must be at least 8 characters long");
        }

        String tokenHash = hashToken(rawToken.trim());
        Optional<PasswordResetToken> tokenOpt = tokenRepository.findByTokenHash(tokenHash);

        if (tokenOpt.isEmpty()) {
            log.warn("Password reset failed: token hash not found");
            throw new AuthenticationDomainException("Invalid password reset token");
        }

        PasswordResetToken token = tokenOpt.get();

        if (token.isUsed()) {
            log.warn("Password reset failed: token ID [{}] already used", token.getId());
            throw new AuthenticationDomainException("Password reset token has already been used");
        }

        if (token.getExpiresAt().isBefore(Instant.now())) {
            log.warn("Password reset failed: token ID [{}] expired", token.getId());
            throw new AuthenticationDomainException("Password reset token has expired");
        }

        User user = token.getUser();

        // 1. Encode new password using BCrypt
        String encodedPassword = passwordEncoder.encode(newPassword);
        user.setPasswordHash(encodedPassword);
        userRepository.save(user);

        // 2. Mark token used
        token.markUsed();
        tokenRepository.save(token);

        // 3. Security: Revoke ALL active refresh token sessions for the user!
        refreshTokenService.revokeAllUserTokens(user.getId());

        log.info("Successfully reset password for user ID [{}] and revoked all active sessions", user.getId());
        return true;
    }

    /**
     * Cleans up expired and used password reset tokens from database storage.
     *
     * @return total count of deleted records
     */
    @Transactional
    public int cleanupExpiredTokens() {
        int expiredDeleted = tokenRepository.deleteExpiredTokens(Instant.now());
        int usedDeleted = tokenRepository.deleteUsedTokens();
        int total = expiredDeleted + usedDeleted;

        if (total > 0) {
            log.info("Cleaned up {} expired/used password reset tokens", total);
        }
        return total;
    }

    private String generateRawToken() {
        byte[] randomBytes = new byte[64];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Computes SHA-256 hex hash of a raw token string.
     *
     * @param rawToken raw plaintext string
     * @return hex-encoded SHA-256 hash string
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
