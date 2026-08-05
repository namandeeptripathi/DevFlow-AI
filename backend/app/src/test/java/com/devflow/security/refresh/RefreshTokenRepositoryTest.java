package com.devflow.security.refresh;

import com.devflow.config.JpaAuditingConfiguration;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaAuditingConfiguration.class)
@ActiveProfiles("test")
@DisplayName("RefreshTokenRepository")
class RefreshTokenRepositoryTest {

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User savedUser;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .email("refresh.user@devflow.com")
                .username("refreshuser")
                .passwordHash("passwordhash")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();
        savedUser = userRepository.save(user);
        entityManager.flush();
    }

    private RefreshToken buildToken(String hash, String deviceId, boolean revoked, Instant expiresAt) {
        return RefreshToken.builder()
                .user(savedUser)
                .tokenHash(hash)
                .deviceId(deviceId)
                .deviceName("Device " + deviceId)
                .ipAddress("127.0.0.1")
                .userAgent("TestAgent")
                .revoked(revoked)
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    @DisplayName("findByTokenHash returns entity when hash exists")
    void findByTokenHash_returnsEntity() {
        String hash = RefreshTokenService.hashToken("token-1");
        RefreshToken token = refreshTokenRepository.save(buildToken(hash, "dev-1", false, Instant.now().plusSeconds(3600)));
        entityManager.flush();
        entityManager.clear();

        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHash(hash);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(token.getId());
        assertThat(found.get().getDeviceId()).isEqualTo("dev-1");
        assertThat(found.get().getUser().getId()).isEqualTo(savedUser.getId());
    }

    @Test
    @DisplayName("findByUserIdAndRevokedFalse returns only active tokens for user")
    void findByUserIdAndRevokedFalse_returnsActiveTokensOnly() {
        refreshTokenRepository.save(buildToken(RefreshTokenService.hashToken("t1"), "dev-1", false, Instant.now().plusSeconds(3600)));
        refreshTokenRepository.save(buildToken(RefreshTokenService.hashToken("t2"), "dev-2", true, Instant.now().plusSeconds(3600))); // Revoked
        refreshTokenRepository.save(buildToken(RefreshTokenService.hashToken("t3"), "dev-3", false, Instant.now().plusSeconds(3600)));
        entityManager.flush();

        List<RefreshToken> active = refreshTokenRepository.findByUserIdAndRevokedFalse(savedUser.getId());

        assertThat(active).hasSize(2);
        assertThat(active).extracting(RefreshToken::getDeviceId).containsExactlyInAnyOrder("dev-1", "dev-3");
    }

    @Test
    @DisplayName("findByUserIdAndDeviceIdAndRevokedFalse returns active token for specific device")
    void findByUserIdAndDeviceIdAndRevokedFalse_returnsSpecificDeviceToken() {
        refreshTokenRepository.save(buildToken(RefreshTokenService.hashToken("t1"), "mobile-ios", false, Instant.now().plusSeconds(3600)));
        refreshTokenRepository.save(buildToken(RefreshTokenService.hashToken("t2"), "desktop-mac", false, Instant.now().plusSeconds(3600)));
        entityManager.flush();

        Optional<RefreshToken> found = refreshTokenRepository.findByUserIdAndDeviceIdAndRevokedFalse(savedUser.getId(), "mobile-ios");

        assertThat(found).isPresent();
        assertThat(found.get().getDeviceId()).isEqualTo("mobile-ios");
    }

    @Test
    @DisplayName("revokeAllByUserId revokes all active tokens for specified user")
    void revokeAllByUserId_revokesAllUserTokens() {
        refreshTokenRepository.save(buildToken(RefreshTokenService.hashToken("t1"), "dev-1", false, Instant.now().plusSeconds(3600)));
        refreshTokenRepository.save(buildToken(RefreshTokenService.hashToken("t2"), "dev-2", false, Instant.now().plusSeconds(3600)));
        entityManager.flush();

        int revokedCount = refreshTokenRepository.revokeAllByUserId(savedUser.getId(), Instant.now());
        entityManager.flush();
        entityManager.clear();

        assertThat(revokedCount).isEqualTo(2);
        List<RefreshToken> active = refreshTokenRepository.findByUserIdAndRevokedFalse(savedUser.getId());
        assertThat(active).isEmpty();
    }

    @Test
    @DisplayName("deleteExpiredTokens removes tokens expired before specified cutoff")
    void deleteExpiredTokens_deletesExpiredRecords() {
        refreshTokenRepository.save(buildToken(RefreshTokenService.hashToken("t1"), "dev-1", false, Instant.now().minusSeconds(3600))); // Expired
        refreshTokenRepository.save(buildToken(RefreshTokenService.hashToken("t2"), "dev-2", false, Instant.now().plusSeconds(3600)));  // Valid
        entityManager.flush();

        int deleted = refreshTokenRepository.deleteExpiredTokens(Instant.now());
        entityManager.flush();
        entityManager.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(refreshTokenRepository.findAll()).hasSize(1);
    }
}
