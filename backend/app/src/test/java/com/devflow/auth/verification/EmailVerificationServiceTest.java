package com.devflow.auth.verification;

import com.devflow.auth.exception.AuthenticationDomainException;
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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailVerificationService")
class EmailVerificationServiceTest {

    @Mock
    private EmailVerificationTokenRepository tokenRepository;

    @Mock
    private UserRepository userRepository;

    private EmailVerificationService verificationService;
    private User testUser;
    private UUID userId;

    @BeforeEach
    void setUp() {
        verificationService = new EmailVerificationService(tokenRepository, userRepository);

        userId = UUID.randomUUID();
        testUser = User.builder()
                .id(userId)
                .email("verify@devflow.com")
                .username("verifyuser")
                .passwordHash("password")
                .emailVerified(false)
                .accountStatus(AccountStatus.PENDING_VERIFICATION)
                .build();
    }

    @Nested
    @DisplayName("Token Generation & Hashing")
    class GenerationAndHashing {

        @Test
        @DisplayName("generateVerificationToken deletes prior tokens, saves hash, and returns raw token")
        void generateVerificationToken_successful() {
            String rawToken = verificationService.generateVerificationToken(testUser);

            assertThat(rawToken).isNotBlank();
            verify(tokenRepository).deleteByUserId(userId);

            ArgumentCaptor<EmailVerificationToken> captor = ArgumentCaptor.forClass(EmailVerificationToken.class);
            verify(tokenRepository).save(captor.capture());

            EmailVerificationToken saved = captor.getValue();
            assertThat(saved.getUser()).isEqualTo(testUser);
            assertThat(saved.getTokenHash()).isEqualTo(EmailVerificationService.hashToken(rawToken));
            assertThat(saved.isVerified()).isFalse();
            assertThat(saved.getExpiresAt()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("hashToken produces consistent SHA-256 hex string")
        void hashToken_isDeterministic() {
            String token = "sample-raw-verification-token";
            String hash1 = EmailVerificationService.hashToken(token);
            String hash2 = EmailVerificationService.hashToken(token);

            assertThat(hash1).isEqualTo(hash2);
            assertThat(hash1).hasSize(64);
        }
    }

    @Nested
    @DisplayName("Email Verification Workflow")
    class VerificationWorkflow {

        @Test
        @DisplayName("verifyEmail updates token verified=true and sets user emailVerified=true and status=ACTIVE")
        void verifyEmail_successful() {
            String rawToken = "valid-raw-verification-token";
            String hash = EmailVerificationService.hashToken(rawToken);

            EmailVerificationToken token = EmailVerificationToken.builder()
                    .id(UUID.randomUUID())
                    .user(testUser)
                    .tokenHash(hash)
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .verified(false)
                    .build();

            when(tokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(token));

            boolean result = verificationService.verifyEmail(rawToken);

            assertThat(result).isTrue();
            assertThat(token.isVerified()).isTrue();
            assertThat(token.getVerifiedAt()).isNotNull();

            verify(tokenRepository).save(token);

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().isEmailVerified()).isTrue();
            assertThat(userCaptor.getValue().getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("verifyEmail throws exception when token is expired")
        void verifyEmail_expiredToken_throwsException() {
            String rawToken = "expired-token";
            String hash = EmailVerificationService.hashToken(rawToken);

            EmailVerificationToken expiredToken = EmailVerificationToken.builder()
                    .user(testUser)
                    .tokenHash(hash)
                    .expiresAt(Instant.now().minusSeconds(10))
                    .verified(false)
                    .build();

            when(tokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(expiredToken));

            assertThatThrownBy(() -> verificationService.verifyEmail(rawToken))
                    .isInstanceOf(AuthenticationDomainException.class)
                    .hasMessageContaining("has expired");
        }

        @Test
        @DisplayName("verifyEmail throws exception when token has already been used")
        void verifyEmail_reusedToken_throwsException() {
            String rawToken = "reused-token";
            String hash = EmailVerificationService.hashToken(rawToken);

            EmailVerificationToken usedToken = EmailVerificationToken.builder()
                    .user(testUser)
                    .tokenHash(hash)
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .verified(true)
                    .build();

            when(tokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(usedToken));

            assertThatThrownBy(() -> verificationService.verifyEmail(rawToken))
                    .isInstanceOf(AuthenticationDomainException.class)
                    .hasMessageContaining("already been used");
        }
    }

    @Nested
    @DisplayName("Resend Verification & Cleanup")
    class ResendAndCleanup {

        @Test
        @DisplayName("resendVerification revokes prior tokens and generates new verification token")
        void resendVerification_successful() {
            String newRawToken = verificationService.resendVerification(testUser);

            assertThat(newRawToken).isNotBlank();
            verify(tokenRepository).deleteByUserId(userId);
            verify(tokenRepository).save(any(EmailVerificationToken.class));
        }

        @Test
        @DisplayName("resendVerification throws exception if user is already verified and active")
        void resendVerification_alreadyVerified_throwsException() {
            User activeVerifiedUser = User.builder()
                    .id(userId)
                    .email("active@devflow.com")
                    .emailVerified(true)
                    .accountStatus(AccountStatus.ACTIVE)
                    .build();

            assertThatThrownBy(() -> verificationService.resendVerification(activeVerifiedUser))
                    .isInstanceOf(AuthenticationDomainException.class)
                    .hasMessageContaining("already verified");
        }

        @Test
        @DisplayName("cleanupExpiredTokens delegates deletion to token repository")
        void cleanupExpiredTokens_invokesRepository() {
            when(tokenRepository.deleteExpiredTokens(any(Instant.class))).thenReturn(3);

            int deleted = verificationService.cleanupExpiredTokens();

            assertThat(deleted).isEqualTo(3);
            verify(tokenRepository).deleteExpiredTokens(any(Instant.class));
        }
    }
}
