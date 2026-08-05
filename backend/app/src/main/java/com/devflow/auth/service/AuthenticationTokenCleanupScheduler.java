package com.devflow.auth.service;

import com.devflow.auth.password.PasswordResetService;
import com.devflow.auth.verification.EmailVerificationService;
import com.devflow.security.refresh.RefreshTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Scheduled background maintenance task performing automated cleanup of expired and consumed security tokens.
 *
 * <p>Executes daily at 03:00 AM server time (or according to configured cron pattern).
 *
 * <h2>Cleanup Operations</h2>
 * <ul>
 *   <li>Deletes expired refresh tokens from {@code refresh_tokens}.</li>
 *   <li>Deletes expired email verification tokens from {@code email_verification_tokens}.</li>
 *   <li>Deletes expired and used password reset tokens from {@code password_reset_tokens}.</li>
 * </ul>
 */
@Component
public class AuthenticationTokenCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationTokenCleanupScheduler.class);

    private final RefreshTokenService refreshTokenService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;

    public AuthenticationTokenCleanupScheduler(
            RefreshTokenService refreshTokenService,
            EmailVerificationService emailVerificationService,
            PasswordResetService passwordResetService
    ) {
        this.refreshTokenService = Objects.requireNonNull(refreshTokenService, "refreshTokenService must not be null");
        this.emailVerificationService = Objects.requireNonNull(emailVerificationService, "emailVerificationService must not be null");
        this.passwordResetService = Objects.requireNonNull(passwordResetService, "passwordResetService must not be null");
    }

    /**
     * Executes scheduled token cleanup. Cron pattern defaults to 03:00 AM daily.
     */
    @Scheduled(cron = "${devflow.security.token-cleanup-cron:0 0 3 * * ?}")
    public void cleanupExpiredTokens() {
        log.info("Starting scheduled cleanup of expired security tokens...");

        try {
            int refreshPurged = refreshTokenService.cleanupExpiredTokens();
            int verificationPurged = emailVerificationService.cleanupExpiredTokens();
            int resetPurged = passwordResetService.cleanupExpiredTokens();

            log.info("Completed scheduled token cleanup: {} refresh tokens, {} email verification tokens, {} password reset tokens purged.",
                    refreshPurged, verificationPurged, resetPurged);
        } catch (Exception e) {
            log.error("Error occurred during scheduled token cleanup execution", e);
        }
    }
}
