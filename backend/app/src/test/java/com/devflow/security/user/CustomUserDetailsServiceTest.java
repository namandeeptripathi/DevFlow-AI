package com.devflow.security.user;

import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomUserDetailsService")
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    private CustomUserDetailsService userDetailsService;

    @BeforeEach
    void setUp() {
        userDetailsService = new CustomUserDetailsService(userRepository);
    }

    private User createTestUser(String email, String username) {
        return User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .username(username)
                .passwordHash("hashed-password-123")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();
    }

    @Test
    @DisplayName("Constructor throws NullPointerException when userRepository is null")
    void constructor_throwsNullPointerException_whenUserRepositoryIsNull() {
        assertThatThrownBy(() -> new CustomUserDetailsService(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userRepository must not be null");
    }

    @Test
    @DisplayName("loadUserByUsername successfully loads user by email")
    void loadUserByUsername_loadsUserByEmail_successfully() {
        String email = "dev@devflow.com";
        User user = createTestUser(email, "devuser");
        when(userRepository.findByEmailOrUsername(email, email)).thenReturn(Optional.of(user));

        UserDetails userDetails = userDetailsService.loadUserByUsername(email);

        assertThat(userDetails).isNotNull();
        assertThat(userDetails).isInstanceOf(DevFlowUserDetails.class);
        assertThat(userDetails.getUsername()).isEqualTo("devuser");
        assertThat(userDetails.getPassword()).isEqualTo("hashed-password-123");
        verify(userRepository).findByEmailOrUsername(email, email);
    }

    @Test
    @DisplayName("loadUserByUsername successfully loads user by username")
    void loadUserByUsername_loadsUserByUsername_successfully() {
        String username = "devuser";
        User user = createTestUser("dev@devflow.com", username);
        when(userRepository.findByEmailOrUsername(username, username)).thenReturn(Optional.of(user));

        UserDetails userDetails = userDetailsService.loadUserByUsername(username);

        assertThat(userDetails).isNotNull();
        assertThat(userDetails.getUsername()).isEqualTo(username);
        verify(userRepository).findByEmailOrUsername(username, username);
    }

    @Test
    @DisplayName("loadUserByUsername throws UsernameNotFoundException when user is not found")
    void loadUserByUsername_throwsUsernameNotFoundException_whenUserNotFound() {
        String identifier = "nonexistent@devflow.com";
        when(userRepository.findByEmailOrUsername(identifier, identifier)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername(identifier))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("User not found with username or email: " + identifier);

        verify(userRepository).findByEmailOrUsername(identifier, identifier);
    }

    @Test
    @DisplayName("loadUserByUsername throws UsernameNotFoundException when input identifier is null or blank")
    void loadUserByUsername_throwsUsernameNotFoundException_whenIdentifierIsBlank() {
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername(""))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("User identifier must not be empty");

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("   "))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("User identifier must not be empty");

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername(null))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("User identifier must not be empty");
    }
}
