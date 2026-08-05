package com.devflow.user.repository;

import com.devflow.config.JpaAuditingConfiguration;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaAuditingConfiguration.class)
@ActiveProfiles("test")
@DisplayName("UserRepository")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User buildTestUser(String email, String username) {
        return User.builder()
                .email(email)
                .username(username)
                .passwordHash("$2a$12$eImiTXuWVxfM37uY4JANjO.GkZ.o5.Vq0f3Lw6M5b0gE5Vq0f3Lw6")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();
    }

    @Test
    @DisplayName("findByEmail finds user when email matches")
    void findByEmail_returnsUser_whenEmailMatches() {
        User user = userRepository.save(buildTestUser("find.email@devflow.com", "findemailuser"));
        entityManager.flush();
        entityManager.clear();

        Optional<User> found = userRepository.findByEmail("find.email@devflow.com");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(user.getId());
        assertThat(found.get().getUsername()).isEqualTo("findemailuser");
    }

    @Test
    @DisplayName("findByEmail returns empty when email does not exist")
    void findByEmail_returnsEmpty_whenEmailDoesNotExist() {
        Optional<User> found = userRepository.findByEmail("nonexistent@devflow.com");

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findByUsername finds user when username matches")
    void findByUsername_returnsUser_whenUsernameMatches() {
        User user = userRepository.save(buildTestUser("username@devflow.com", "uniqueusername"));
        entityManager.flush();
        entityManager.clear();

        Optional<User> found = userRepository.findByUsername("uniqueusername");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(user.getId());
        assertThat(found.get().getEmail()).isEqualTo("username@devflow.com");
    }

    @Test
    @DisplayName("existsByEmail returns true when email exists and false when absent")
    void existsByEmail_returnsCorrectBoolean() {
        userRepository.save(buildTestUser("exists.email@devflow.com", "existsemailuser"));
        entityManager.flush();

        assertThat(userRepository.existsByEmail("exists.email@devflow.com")).isTrue();
        assertThat(userRepository.existsByEmail("absent@devflow.com")).isFalse();
    }

    @Test
    @DisplayName("existsByUsername returns true when username exists and false when absent")
    void existsByUsername_returnsCorrectBoolean() {
        userRepository.save(buildTestUser("exists.user@devflow.com", "existsusername"));
        entityManager.flush();

        assertThat(userRepository.existsByUsername("existsusername")).isTrue();
        assertThat(userRepository.existsByUsername("absentusername")).isFalse();
    }

    @Test
    @DisplayName("findByEmailOrUsername matches by either email or username")
    void findByEmailOrUsername_matchesByEmailOrUsername() {
        userRepository.save(buildTestUser("or.email@devflow.com", "oruser"));
        entityManager.flush();
        entityManager.clear();

        Optional<User> byEmail = userRepository.findByEmailOrUsername("or.email@devflow.com", "or.email@devflow.com");
        Optional<User> byUsername = userRepository.findByEmailOrUsername("oruser", "oruser");

        assertThat(byEmail).isPresent();
        assertThat(byUsername).isPresent();
        assertThat(byEmail.get().getId()).isEqualTo(byUsername.get().getId());
    }

    @Test
    @DisplayName("Entity persistence populates audit fields and version")
    void save_populatesAuditFieldsAndVersion() {
        User user = userRepository.save(buildTestUser("audit@devflow.com", "audituser"));
        entityManager.flush();

        assertThat(user.getId()).isNotNull();
        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(user.getUpdatedAt()).isNotNull();
        assertThat(user.getVersion()).isEqualTo(0L);
    }
}
