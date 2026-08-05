package com.devflow.auth.password;

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
@DisplayName("PasswordResetTokenRepository")
class PasswordResetTokenRepositoryTest {

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User savedUser;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .email("reset.repo@devflow.com")
                .username("resetrepouser")
                .passwordHash("passwordhash")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();
        savedUser = userRepository.save(user);
        entityManager.flush();
    }

    private PasswordResetToken buildToken(String hash, boolean used, Instant expiresAt) {
        return PasswordResetToken.builder()
                .user(savedUser)
                .tokenHash(hash)
                .used(used)
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    @DisplayName("findByTokenHash returns entity when hash exists")
    void findByTokenHash_returnsEntity() {
        String hash = PasswordResetService.hashToken("reset-token-1");
        PasswordResetToken token = tokenRepository.save(buildToken(hash, false, Instant.now().plusSeconds(900)));
        entityManager.flush();
        entityManager.clear();

        Optional<PasswordResetToken> found = tokenRepository.findByTokenHash(hash);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(token.getId());
        assertThat(found.get().getUser().getId()).isEqualTo(savedUser.getId());
    }

    @Test
    @DisplayName("existsByTokenHash returns true when hash exists")
    void existsByTokenHash_returnsTrue() {
        String hash = PasswordResetService.hashToken("reset-token-2");
        tokenRepository.save(buildToken(hash, false, Instant.now().plusSeconds(900)));
        entityManager.flush();

        assertThat(tokenRepository.existsByTokenHash(hash)).isTrue();
        assertThat(tokenRepository.existsByTokenHash("non-existent-hash")).isFalse();
    }

    @Test
    @DisplayName("findPendingByUserId returns only pending (unused) tokens for user")
    void findPendingByUserId_returnsPendingTokens() {
        tokenRepository.save(buildToken(PasswordResetService.hashToken("t1"), false, Instant.now().plusSeconds(900)));
        tokenRepository.save(buildToken(PasswordResetService.hashToken("t2"), true, Instant.now().plusSeconds(900))); // Used
        entityManager.flush();

        List<PasswordResetToken> pending = tokenRepository.findPendingByUserId(savedUser.getId());

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).isUsed()).isFalse();
    }

    @Test
    @DisplayName("deleteByUserId removes all reset tokens for specified user")
    void deleteByUserId_removesAllUserTokens() {
        tokenRepository.save(buildToken(PasswordResetService.hashToken("t1"), false, Instant.now().plusSeconds(900)));
        tokenRepository.save(buildToken(PasswordResetService.hashToken("t2"), false, Instant.now().plusSeconds(900)));
        entityManager.flush();

        int deleted = tokenRepository.deleteByUserId(savedUser.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(deleted).isEqualTo(2);
        assertThat(tokenRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("deleteExpiredTokens removes tokens expired before cutoff")
    void deleteExpiredTokens_deletesExpiredRecords() {
        tokenRepository.save(buildToken(PasswordResetService.hashToken("t1"), false, Instant.now().minusSeconds(900))); // Expired
        tokenRepository.save(buildToken(PasswordResetService.hashToken("t2"), false, Instant.now().plusSeconds(900)));  // Valid
        entityManager.flush();

        int deleted = tokenRepository.deleteExpiredTokens(Instant.now());
        entityManager.flush();
        entityManager.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(tokenRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("deleteUsedTokens removes tokens marked as used")
    void deleteUsedTokens_deletesUsedRecords() {
        tokenRepository.save(buildToken(PasswordResetService.hashToken("t1"), true, Instant.now().plusSeconds(900)));  // Used
        tokenRepository.save(buildToken(PasswordResetService.hashToken("t2"), false, Instant.now().plusSeconds(900))); // Unused
        entityManager.flush();

        int deleted = tokenRepository.deleteUsedTokens();
        entityManager.flush();
        entityManager.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(tokenRepository.findAll()).hasSize(1);
    }
}
