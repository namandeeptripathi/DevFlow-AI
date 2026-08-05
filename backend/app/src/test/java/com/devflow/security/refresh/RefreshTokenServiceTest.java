package com.devflow.security.refresh;

import com.devflow.security.jwt.JwtProperties;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;

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
@DisplayName("RefreshTokenService")
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenService refreshTokenService;
    private JwtProperties jwtProperties;
    private User testUser;
    private UUID userId;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret("404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970337336763979244226452948404D635166546A576E5A7234753778214125442A");
        jwtProperties.setRefreshTokenExpiration(604800000L); // 7 days

        refreshTokenService = new RefreshTokenService(refreshTokenRepository, jwtProperties);

        userId = UUID.randomUUID();
        testUser = User.builder()
                .id(userId)
                .email("test@devflow.com")
                .username("testuser")
                .passwordHash("hash")
                .accountStatus(AccountStatus.ACTIVE)
                .build();
    }

    @Nested
    @DisplayName("Token Creation & Hashing")
    class CreationAndHashing {

        @Test
        @DisplayName("createRefreshToken persists SHA-256 hash and returns plaintext raw token")
        void createRefreshToken_persistsHashAndReturnsRawToken() {
            String rawToken = refreshTokenService.createRefreshToken(
                    testUser,
                    "device-mac-1",
                    "MacBook Pro",
                    "192.168.1.10",
                    "Mozilla/5.0"
            );

            assertThat(rawToken).isNotBlank();

            ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository).save(captor.capture());

            RefreshToken saved = captor.getValue();
            assertThat(saved.getUser()).isEqualTo(testUser);
            assertThat(saved.getDeviceId()).isEqualTo("device-mac-1");
            assertThat(saved.getDeviceName()).isEqualTo("MacBook Pro");
            assertThat(saved.getTokenHash()).isNotEqualTo(rawToken); // Only hash is stored!
            assertThat(saved.getTokenHash()).isEqualTo(RefreshTokenService.hashToken(rawToken));
            assertThat(saved.isRevoked()).isFalse();
            assertThat(saved.getExpiresAt()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("hashToken produces consistent SHA-256 hex string")
        void hashToken_isDeterministic() {
            String token = "sample-raw-token-12345";
            String hash1 = RefreshTokenService.hashToken(token);
            String hash2 = RefreshTokenService.hashToken(token);

            assertThat(hash1).isEqualTo(hash2);
            assertThat(hash1).hasSize(64); // 64 hex chars for SHA-256
        }
    }

    @Nested
    @DisplayName("Token Validation")
    class Validation {

        @Test
        @DisplayName("validateRefreshToken returns entity when token is valid and unexpired")
        void validateRefreshToken_validToken_returnsEntity() {
            String rawToken = "valid-raw-token-abc";
            String hash = RefreshTokenService.hashToken(rawToken);

            RefreshToken tokenEntity = RefreshToken.builder()
                    .id(UUID.randomUUID())
                    .user(testUser)
                    .tokenHash(hash)
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .revoked(false)
                    .build();

            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(tokenEntity));

            Optional<RefreshToken> result = refreshTokenService.validateRefreshToken(rawToken);

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(tokenEntity);
        }

        @Test
        @DisplayName("validateRefreshToken returns empty when token is revoked")
        void validateRefreshToken_revokedToken_returnsEmpty() {
            String rawToken = "revoked-token-xyz";
            String hash = RefreshTokenService.hashToken(rawToken);

            RefreshToken tokenEntity = RefreshToken.builder()
                    .user(testUser)
                    .tokenHash(hash)
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .revoked(true)
                    .build();

            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(tokenEntity));

            assertThat(refreshTokenService.validateRefreshToken(rawToken)).isEmpty();
        }

        @Test
        @DisplayName("validateRefreshToken returns empty when token is expired")
        void validateRefreshToken_expiredToken_returnsEmpty() {
            String rawToken = "expired-token-123";
            String hash = RefreshTokenService.hashToken(rawToken);

            RefreshToken tokenEntity = RefreshToken.builder()
                    .user(testUser)
                    .tokenHash(hash)
                    .expiresAt(Instant.now().minusSeconds(10)) // Expired
                    .revoked(false)
                    .build();

            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(tokenEntity));

            assertThat(refreshTokenService.validateRefreshToken(rawToken)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Token Revocation")
    class Revocation {

        @Test
        @DisplayName("revokeRefreshToken marks token as revoked")
        void revokeRefreshToken_marksRevoked() {
            String rawToken = "token-to-revoke";
            String hash = RefreshTokenService.hashToken(rawToken);

            RefreshToken tokenEntity = RefreshToken.builder()
                    .id(UUID.randomUUID())
                    .user(testUser)
                    .tokenHash(hash)
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .revoked(false)
                    .build();

            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(tokenEntity));

            refreshTokenService.revokeRefreshToken(rawToken);

            assertThat(tokenEntity.isRevoked()).isTrue();
            assertThat(tokenEntity.getRevokedAt()).isNotNull();
            verify(refreshTokenRepository).save(tokenEntity);
        }

        @Test
        @DisplayName("revokeAllUserTokens calls repository bulk revocation")
        void revokeAllUserTokens_invokesRepository() {
            refreshTokenService.revokeAllUserTokens(userId);

            verify(refreshTokenRepository).revokeAllByUserId(eq(userId), any(Instant.class));
        }
    }

    @Nested
    @DisplayName("Token Rotation & Theft Detection")
    class RotationAndTheftDetection {

        @Test
        @DisplayName("rotateRefreshToken revokes current token and issues new raw token")
        void rotateRefreshToken_validToken_rotatesSuccessfully() {
            String rawToken = "active-token-to-rotate";
            String hash = RefreshTokenService.hashToken(rawToken);

            RefreshToken activeToken = RefreshToken.builder()
                    .id(UUID.randomUUID())
                    .user(testUser)
                    .tokenHash(hash)
                    .deviceId("device-1")
                    .deviceName("Chrome Mac")
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .revoked(false)
                    .build();

            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(activeToken));

            String newRawToken = refreshTokenService.rotateRefreshToken(rawToken, "10.0.0.1", "Mozilla");

            assertThat(newRawToken).isNotBlank();
            assertThat(newRawToken).isNotEqualTo(rawToken);
            assertThat(activeToken.isRevoked()).isTrue();

            verify(refreshTokenRepository, org.mockito.Mockito.times(2)).save(any(RefreshToken.class));
        }

        @Test
        @DisplayName("rotateRefreshToken triggers THEFT DETECTION when revoked token is presented")
        void rotateRefreshToken_revokedToken_triggersTheftDetectionAndRevokesAllSessions() {
            String rawToken = "already-revoked-stolen-token";
            String hash = RefreshTokenService.hashToken(rawToken);

            RefreshToken revokedToken = RefreshToken.builder()
                    .id(UUID.randomUUID())
                    .user(testUser)
                    .tokenHash(hash)
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .revoked(true) // ALREADY REVOKED!
                    .build();

            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(revokedToken));

            assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(rawToken, "10.0.0.1", "HackerBot"))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("All user sessions have been terminated");

            // Verify bulk revocation of all user tokens was triggered due to theft signal!
            verify(refreshTokenRepository).revokeAllByUserId(eq(userId), any(Instant.class));
        }

        @Test
        @DisplayName("rotateRefreshToken throws BadCredentialsException for expired token")
        void rotateRefreshToken_expiredToken_throwsException() {
            String rawToken = "expired-token";
            String hash = RefreshTokenService.hashToken(rawToken);

            RefreshToken expiredToken = RefreshToken.builder()
                    .id(UUID.randomUUID())
                    .user(testUser)
                    .tokenHash(hash)
                    .expiresAt(Instant.now().minusSeconds(60))
                    .revoked(false)
                    .build();

            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(expiredToken));

            assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(rawToken, "10.0.0.1", "Browser"))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Refresh token has expired");
        }
    }

    @Nested
    @DisplayName("Multiple Device Sessions & Cleanup")
    class MultiDeviceAndCleanup {

        @Test
        @DisplayName("cleanupExpiredTokens delegates to repository deleteExpiredTokens")
        void cleanupExpiredTokens_invokesRepository() {
            when(refreshTokenRepository.deleteExpiredTokens(any(Instant.class))).thenReturn(5);

            int purged = refreshTokenService.cleanupExpiredTokens();

            assertThat(purged).isEqualTo(5);
            verify(refreshTokenRepository).deleteExpiredTokens(any(Instant.class));
        }
    }
}
