package com.devflow.auth.password;

import com.devflow.auth.exception.AuthenticationDomainException;
import com.devflow.security.refresh.RefreshTokenService;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordResetService")
class PasswordResetServiceTest {

    @Mock
    private PasswordResetTokenRepository tokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RefreshTokenService refreshTokenService;

    private PasswordResetService resetService;
    private User testUser;
    private UUID userId;

    @BeforeEach
    void setUp() {
        resetService = new PasswordResetService(
                tokenRepository,
                userRepository,
                passwordEncoder,
                refreshTokenService
        );

        userId = UUID.randomUUID();
        testUser = User.builder()
                .id(userId)
                .email("reset@devflow.com")
                .username("resetuser")
                .passwordHash("oldPasswordHash")
                .emailVerified(true)
                .accountStatus(AccountStatus.ACTIVE)
                .build();
    }

    @Nested
    @DisplayName("Password Reset Request Workflow")
    class ResetRequestWorkflow {

        @Test
        @DisplayName("requestPasswordReset for existing user returns raw token and stores SHA-256 hash")
        void requestPasswordReset_existingUser_returnsRawToken() {
            when(userRepository.findByEmail("reset@devflow.com")).thenReturn(Optional.of(testUser));

            Optional<String> tokenOpt = resetService.requestPasswordReset("reset@devflow.com");

            assertThat(tokenOpt).isPresent();
            String rawToken = tokenOpt.get();
            assertThat(rawToken).isNotBlank();

            verify(tokenRepository).deleteByUserId(userId);

            ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
            verify(tokenRepository).save(captor.capture());

            PasswordResetToken saved = captor.getValue();
            assertThat(saved.getUser()).isEqualTo(testUser);
            assertThat(saved.getTokenHash()).isEqualTo(PasswordResetService.hashToken(rawToken));
            assertThat(saved.isUsed()).isFalse();
            assertThat(saved.getExpiresAt()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("requestPasswordReset for unknown email returns Optional.empty() without disclosing user absence")
        void requestPasswordReset_unknownEmail_returnsEmpty() {
            when(userRepository.findByEmail("unknown@devflow.com")).thenReturn(Optional.empty());

            Optional<String> tokenOpt = resetService.requestPasswordReset("unknown@devflow.com");

            assertThat(tokenOpt).isEmpty();
            verify(tokenRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Password Reset Execution Workflow")
    class ResetExecutionWorkflow {

        @Test
        @DisplayName("resetPassword updates BCrypt hash, marks token used, and revokes ALL refresh tokens for user")
        void resetPassword_successful() {
            String rawToken = "valid-reset-token-123";
            String hash = PasswordResetService.hashToken(rawToken);

            PasswordResetToken tokenEntity = PasswordResetToken.builder()
                    .id(UUID.randomUUID())
                    .user(testUser)
                    .tokenHash(hash)
                    .expiresAt(Instant.now().plusSeconds(900))
                    .used(false)
                    .build();

            when(tokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(tokenEntity));
            when(passwordEncoder.encode("NewSecretPassword123")).thenReturn("newPasswordHash");

            boolean result = resetService.resetPassword(rawToken, "NewSecretPassword123");

            assertThat(result).isTrue();
            assertThat(tokenEntity.isUsed()).isTrue();
            assertThat(tokenEntity.getUsedAt()).isNotNull();

            // Verify password updated
            verify(userRepository).save(testUser);
            assertThat(testUser.getPasswordHash()).isEqualTo("newPasswordHash");

            // Verify token marked used
            verify(tokenRepository).save(tokenEntity);

            // Verify security rule: ALL refresh tokens for user are revoked!
            verify(refreshTokenService).revokeAllUserTokens(userId);
        }

        @Test
        @DisplayName("resetPassword throws exception when token is expired")
        void resetPassword_expiredToken_throwsException() {
            String rawToken = "expired-token-123";
            String hash = PasswordResetService.hashToken(rawToken);

            PasswordResetToken expiredToken = PasswordResetToken.builder()
                    .user(testUser)
                    .tokenHash(hash)
                    .expiresAt(Instant.now().minusSeconds(10))
                    .used(false)
                    .build();

            when(tokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(expiredToken));

            assertThatThrownBy(() -> resetService.resetPassword(rawToken, "NewPassword123"))
                    .isInstanceOf(AuthenticationDomainException.class)
                    .hasMessageContaining("has expired");
        }

        @Test
        @DisplayName("resetPassword throws exception when token has already been used")
        void resetPassword_reusedToken_throwsException() {
            String rawToken = "reused-token-123";
            String hash = PasswordResetService.hashToken(rawToken);

            PasswordResetToken usedToken = PasswordResetToken.builder()
                    .user(testUser)
                    .tokenHash(hash)
                    .expiresAt(Instant.now().plusSeconds(900))
                    .used(true)
                    .build();

            when(tokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(usedToken));

            assertThatThrownBy(() -> resetService.resetPassword(rawToken, "NewPassword123"))
                    .isInstanceOf(AuthenticationDomainException.class)
                    .hasMessageContaining("already been used");
        }
    }

    @Nested
    @DisplayName("Token Cleanup")
    class CleanupWorkflow {

        @Test
        @DisplayName("cleanupExpiredTokens deletes expired and used reset tokens")
        void cleanupExpiredTokens_invokesRepository() {
            when(tokenRepository.deleteExpiredTokens(any(Instant.class))).thenReturn(2);
            when(tokenRepository.deleteUsedTokens()).thenReturn(3);

            int totalDeleted = resetService.cleanupExpiredTokens();

            assertThat(totalDeleted).isEqualTo(5);
            verify(tokenRepository).deleteExpiredTokens(any(Instant.class));
            verify(tokenRepository).deleteUsedTokens();
        }
    }
}
