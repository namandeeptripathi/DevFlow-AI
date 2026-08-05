package com.devflow.auth.verification;

import com.devflow.auth.exception.AuthenticationDomainException;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Service managing database-backed email verification tokens and user activation workflows.
 *
 * <h2>Business Rules</h2>
 * <ul>
 *   <li>Generates 64-byte random plaintext tokens returned to the caller for delivery.</li>
 *   <li>Only SHA-256 hashes are persisted in the database.</li>
 *   <li>Successful verification marks the token verified, sets {@code user.emailVerified = true},
 *       and transitions {@code user.accountStatus = AccountStatus.ACTIVE}.</li>
 *   <li>Resend invalidates previous pending verification tokens for the user.</li>
 * </ul>
 *
 * @see EmailVerificationToken
 * @see EmailVerificationTokenRepository
 * @see UserRepository
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 24-hour expiration duration in milliseconds */
    private static final long VERIFICATION_TOKEN_EXPIRATION_MS = 86400000L;

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;

    public EmailVerificationService(
            EmailVerificationTokenRepository tokenRepository,
            UserRepository userRepository
    ) {
        this.tokenRepository = Objects.requireNonNull(tokenRepository, "tokenRepository must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
    }

    /**
     * Generates a new database-backed email verification token for a user.
     * Invalidates any prior pending verification tokens for this user.
     *
     * @param user the target user
     * @return the raw plaintext verification token string (must be sent via email)
     */
    @Transactional
    public String generateVerificationToken(User user) {
        Objects.requireNonNull(user, "User entity must not be null");

        // Revoke / delete previous pending tokens for this user
        tokenRepository.deleteByUserId(user.getId());

        String rawToken = generateRawToken();
        String tokenHash = hashToken(rawToken);

        Instant expiresAt = Instant.now().plusMillis(VERIFICATION_TOKEN_EXPIRATION_MS);

        EmailVerificationToken verificationToken = EmailVerificationToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .expiresAt(expiresAt)
                .verified(false)
                .build();

        tokenRepository.save(verificationToken);
        log.info("Generated email verification token for user [{}]", user.getId());

        return rawToken;
    }

    /**
     * Verifies a user's email address using the presented raw verification token string.
     *
     * @param rawToken the raw plaintext verification token string
     * @return {@code true} if verification succeeded and user is activated
     * @throws AuthenticationDomainException if the token is invalid, expired, or already used
     */
    @Transactional
    public boolean verifyEmail(String rawToken) {
        if (rawToken == null || rawToken.trim().isEmpty()) {
            throw new AuthenticationDomainException("Verification token must not be empty");
        }

        String tokenHash = hashToken(rawToken.trim());
        Optional<EmailVerificationToken> tokenOpt = tokenRepository.findByTokenHash(tokenHash);

        if (tokenOpt.isEmpty()) {
            log.warn("Email verification failed: token hash not found");
            throw new AuthenticationDomainException("Invalid email verification token");
        }

        EmailVerificationToken token = tokenOpt.get();

        if (token.isVerified()) {
            log.warn("Email verification failed: token ID [{}] already verified", token.getId());
            throw new AuthenticationDomainException("Email verification token has already been used");
        }

        if (token.getExpiresAt().isBefore(Instant.now())) {
            log.warn("Email verification failed: token ID [{}] expired", token.getId());
            throw new AuthenticationDomainException("Email verification token has expired");
        }

        // Mark token verified
        token.markVerified();
        tokenRepository.save(token);

        // Update user state: emailVerified = true, accountStatus = ACTIVE
        User user = token.getUser();
        user.setEmailVerified(true);
        user.setAccountStatus(AccountStatus.ACTIVE);
        userRepository.save(user);

        log.info("User [{}] email address verified successfully. Account status set to ACTIVE.", user.getId());
        return true;
    }

    /**
     * Resends a verification token for a user, invalidating prior pending tokens.
     *
     * @param user target user
     * @return new raw plaintext verification token string
     */
    @Transactional
    public String resendVerification(User user) {
        Objects.requireNonNull(user, "User entity must not be null");

        if (user.isEmailVerified() && user.getAccountStatus() == AccountStatus.ACTIVE) {
            log.info("Resend verification ignored: user [{}] is already verified and ACTIVE", user.getId());
            throw new AuthenticationDomainException("User email is already verified");
        }

        return generateVerificationToken(user);
    }

    /**
     * Cleans up expired verification tokens from database storage.
     *
     * @return count of deleted records
     */
    @Transactional
    public int cleanupExpiredTokens() {
        int deleted = tokenRepository.deleteExpiredTokens(Instant.now());
        if (deleted > 0) {
            log.info("Cleaned up {} expired email verification tokens", deleted);
        }
        return deleted;
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
