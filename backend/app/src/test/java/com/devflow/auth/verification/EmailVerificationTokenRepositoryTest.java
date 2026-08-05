package com.devflow.auth.verification;

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
@DisplayName("EmailVerificationTokenRepository")
class EmailVerificationTokenRepositoryTest {

    @Autowired
    private EmailVerificationTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User savedUser;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .email("repo.user@devflow.com")
                .username("repouser")
                .passwordHash("passwordhash")
                .accountStatus(AccountStatus.PENDING_VERIFICATION)
                .emailVerified(false)
                .build();
        savedUser = userRepository.save(user);
        entityManager.flush();
    }

    private EmailVerificationToken buildToken(String hash, boolean verified, Instant expiresAt) {
        return EmailVerificationToken.builder()
                .user(savedUser)
                .tokenHash(hash)
                .verified(verified)
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    @DisplayName("findByTokenHash returns entity when hash exists")
    void findByTokenHash_returnsEntity() {
        String hash = EmailVerificationService.hashToken("token-1");
        EmailVerificationToken token = tokenRepository.save(buildToken(hash, false, Instant.now().plusSeconds(3600)));
        entityManager.flush();
        entityManager.clear();

        Optional<EmailVerificationToken> found = tokenRepository.findByTokenHash(hash);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(token.getId());
        assertThat(found.get().getUser().getId()).isEqualTo(savedUser.getId());
    }

    @Test
    @DisplayName("existsByTokenHash returns true when token hash exists")
    void existsByTokenHash_returnsTrue() {
        String hash = EmailVerificationService.hashToken("token-2");
        tokenRepository.save(buildToken(hash, false, Instant.now().plusSeconds(3600)));
        entityManager.flush();

        assertThat(tokenRepository.existsByTokenHash(hash)).isTrue();
        assertThat(tokenRepository.existsByTokenHash("non-existent-hash")).isFalse();
    }

    @Test
    @DisplayName("findPendingByUserId returns pending tokens ordered by createdAt desc")
    void findPendingByUserId_returnsPendingTokens() {
        tokenRepository.save(buildToken(EmailVerificationService.hashToken("t1"), false, Instant.now().plusSeconds(3600)));
        tokenRepository.save(buildToken(EmailVerificationService.hashToken("t2"), true, Instant.now().plusSeconds(3600))); // Verified
        entityManager.flush();

        List<EmailVerificationToken> pending = tokenRepository.findPendingByUserId(savedUser.getId());

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).isVerified()).isFalse();
    }

    @Test
    @DisplayName("deleteByUserId deletes all tokens for specified user")
    void deleteByUserId_removesAllUserTokens() {
        tokenRepository.save(buildToken(EmailVerificationService.hashToken("t1"), false, Instant.now().plusSeconds(3600)));
        tokenRepository.save(buildToken(EmailVerificationService.hashToken("t2"), false, Instant.now().plusSeconds(3600)));
        entityManager.flush();

        int deleted = tokenRepository.deleteByUserId(savedUser.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(deleted).isEqualTo(2);
        assertThat(tokenRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("deleteExpiredTokens deletes tokens expired before specified cutoff")
    void deleteExpiredTokens_deletesExpiredRecords() {
        tokenRepository.save(buildToken(EmailVerificationService.hashToken("t1"), false, Instant.now().minusSeconds(3600))); // Expired
        tokenRepository.save(buildToken(EmailVerificationService.hashToken("t2"), false, Instant.now().plusSeconds(3600)));  // Valid
        entityManager.flush();

        int deleted = tokenRepository.deleteExpiredTokens(Instant.now());
        entityManager.flush();
        entityManager.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(tokenRepository.findAll()).hasSize(1);
    }
}
