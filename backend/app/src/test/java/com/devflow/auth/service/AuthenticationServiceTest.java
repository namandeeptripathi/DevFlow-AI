package com.devflow.auth.service;

import com.devflow.auth.dto.AuthenticationResponse;
import com.devflow.auth.dto.LoginRequest;
import com.devflow.auth.dto.RefreshTokenRequest;
import com.devflow.auth.dto.RegisterRequest;
import com.devflow.auth.exception.AccountLockedException;
import com.devflow.auth.exception.EmailNotVerifiedException;
import com.devflow.auth.exception.InvalidCredentialsException;
import com.devflow.auth.exception.UserAlreadyExistsException;
import com.devflow.security.jwt.JwtProperties;
import com.devflow.security.jwt.JwtTokenProvider;
import com.devflow.security.refresh.RefreshToken;
import com.devflow.security.refresh.RefreshTokenService;
import com.devflow.security.user.CustomUserDetailsService;
import com.devflow.security.user.DevFlowUserDetails;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthenticationService")
class AuthenticationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private CustomUserDetailsService userDetailsService;

    private JwtProperties jwtProperties;
    private AuthenticationService authenticationService;
    private UUID userId;
    private User activeUser;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setAccessTokenExpiration(900000L);

        authenticationService = new AuthenticationService(
                userRepository,
                passwordEncoder,
                jwtTokenProvider,
                refreshTokenService,
                userDetailsService,
                jwtProperties
        );

        userId = UUID.randomUUID();
        activeUser = User.builder()
                .id(userId)
                .email("alex@devflow.com")
                .username("alexdev")
                .passwordHash("encoded-password")
                .emailVerified(true)
                .accountStatus(AccountStatus.ACTIVE)
                .build();
    }

    @Nested
    @DisplayName("Registration Flow")
    class RegistrationFlow {

        @Test
        @DisplayName("register creates user with BCrypt hash and returns AuthenticationResponse")
        void register_successful() {
            RegisterRequest request = RegisterRequest.builder()
                    .email("new@devflow.com")
                    .username("newuser")
                    .password("rawPassword123")
                    .deviceId("mac-1")
                    .deviceName("MacBook")
                    .build();

            when(userRepository.existsByEmail("new@devflow.com")).thenReturn(false);
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            when(passwordEncoder.encode("rawPassword123")).thenReturn("encodedPassword123");

            User savedUser = User.builder()
                    .id(userId)
                    .email("new@devflow.com")
                    .username("newuser")
                    .passwordHash("encodedPassword123")
                    .emailVerified(false)
                    .accountStatus(AccountStatus.PENDING_VERIFICATION)
                    .build();

            when(userRepository.save(any(User.class))).thenReturn(savedUser);
            when(jwtTokenProvider.generateAccessToken(any(DevFlowUserDetails.class))).thenReturn("access-token-123");
            when(refreshTokenService.createRefreshToken(eq(savedUser), eq("mac-1"), eq("MacBook"), eq("127.0.0.1"), eq("Mozilla"))).thenReturn("refresh-token-123");

            AuthenticationResponse response = authenticationService.register(request, "127.0.0.1", "Mozilla");

            assertThat(response).isNotNull();
            assertThat(response.getAccessToken()).isEqualTo("access-token-123");
            assertThat(response.getRefreshToken()).isEqualTo("refresh-token-123");
            assertThat(response.getUserId()).isEqualTo(userId);
            assertThat(response.getEmail()).isEqualTo("new@devflow.com");
            assertThat(response.getUsername()).isEqualTo("newuser");

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("encodedPassword123");
            assertThat(userCaptor.getValue().getAccountStatus()).isEqualTo(AccountStatus.PENDING_VERIFICATION);
        }

        @Test
        @DisplayName("register throws UserAlreadyExistsException when email is taken")
        void register_duplicateEmail_throwsException() {
            RegisterRequest request = RegisterRequest.builder()
                    .email("existing@devflow.com")
                    .username("newuser")
                    .password("rawPassword123")
                    .build();

            when(userRepository.existsByEmail("existing@devflow.com")).thenReturn(true);

            assertThatThrownBy(() -> authenticationService.register(request, "127.0.0.1", "Mozilla"))
                    .isInstanceOf(UserAlreadyExistsException.class)
                    .hasMessageContaining("User already exists with email: existing@devflow.com");
        }

        @Test
        @DisplayName("register throws UserAlreadyExistsException when username is taken")
        void register_duplicateUsername_throwsException() {
            RegisterRequest request = RegisterRequest.builder()
                    .email("new@devflow.com")
                    .username("existinguser")
                    .password("rawPassword123")
                    .build();

            when(userRepository.existsByEmail("new@devflow.com")).thenReturn(false);
            when(userRepository.existsByUsername("existinguser")).thenReturn(true);

            assertThatThrownBy(() -> authenticationService.register(request, "127.0.0.1", "Mozilla"))
                    .isInstanceOf(UserAlreadyExistsException.class)
                    .hasMessageContaining("User already exists with username: existinguser");
        }
    }

    @Nested
    @DisplayName("Authentication (Login) Flow")
    class LoginFlow {

        @Test
        @DisplayName("authenticate succeeds with correct credentials and updates lastLoginAt")
        void authenticate_successful() {
            LoginRequest request = LoginRequest.builder()
                    .login("alex@devflow.com")
                    .password("correctPassword")
                    .deviceId("device-1")
                    .build();

            when(userRepository.findByEmailOrUsername("alex@devflow.com", "alex@devflow.com"))
                    .thenReturn(Optional.of(activeUser));
            when(passwordEncoder.matches("correctPassword", "encoded-password")).thenReturn(true);
            when(jwtTokenProvider.generateAccessToken(any(DevFlowUserDetails.class))).thenReturn("access-token");
            when(refreshTokenService.createRefreshToken(eq(activeUser), eq("device-1"), any(), eq("127.0.0.1"), eq("Mozilla")))
                    .thenReturn("refresh-token");

            AuthenticationResponse response = authenticationService.authenticate(request, "127.0.0.1", "Mozilla");

            assertThat(response).isNotNull();
            assertThat(response.getAccessToken()).isEqualTo("access-token");
            assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
            verify(userRepository).save(activeUser); // Verified lastLoginAt update
        }

        @Test
        @DisplayName("authenticate throws InvalidCredentialsException when password fails")
        void authenticate_wrongPassword_throwsException() {
            LoginRequest request = LoginRequest.builder()
                    .login("alex@devflow.com")
                    .password("wrongPassword")
                    .build();

            when(userRepository.findByEmailOrUsername("alex@devflow.com", "alex@devflow.com"))
                    .thenReturn(Optional.of(activeUser));
            when(passwordEncoder.matches("wrongPassword", "encoded-password")).thenReturn(false);

            assertThatThrownBy(() -> authenticationService.authenticate(request, "127.0.0.1", "Mozilla"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessageContaining("Invalid username/email or password");
        }

        @Test
        @DisplayName("authenticate throws InvalidCredentialsException when user is not found")
        void authenticate_userNotFound_throwsException() {
            LoginRequest request = LoginRequest.builder()
                    .login("nonexistent@devflow.com")
                    .password("password")
                    .build();

            when(userRepository.findByEmailOrUsername("nonexistent@devflow.com", "nonexistent@devflow.com"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> authenticationService.authenticate(request, "127.0.0.1", "Mozilla"))
                    .isInstanceOf(InvalidCredentialsException.class)
                    .hasMessageContaining("Invalid username/email or password");
        }

        @Test
        @DisplayName("authenticate throws AccountLockedException when account is locked")
        void authenticate_lockedAccount_throwsException() {
            User lockedUser = User.builder()
                    .id(userId)
                    .email("locked@devflow.com")
                    .username("lockeduser")
                    .passwordHash("encoded-password")
                    .accountStatus(AccountStatus.LOCKED)
                    .emailVerified(true)
                    .build();

            LoginRequest request = LoginRequest.builder()
                    .login("locked@devflow.com")
                    .password("correctPassword")
                    .build();

            when(userRepository.findByEmailOrUsername("locked@devflow.com", "locked@devflow.com"))
                    .thenReturn(Optional.of(lockedUser));
            when(passwordEncoder.matches("correctPassword", "encoded-password")).thenReturn(true);

            assertThatThrownBy(() -> authenticationService.authenticate(request, "127.0.0.1", "Mozilla"))
                    .isInstanceOf(AccountLockedException.class)
                    .hasMessageContaining("Account is locked or suspended");
        }

        @Test
        @DisplayName("authenticate throws EmailNotVerifiedException when email is not verified")
        void authenticate_unverifiedEmail_throwsException() {
            User unverifiedUser = User.builder()
                    .id(userId)
                    .email("unverified@devflow.com")
                    .username("unverifieduser")
                    .passwordHash("encoded-password")
                    .accountStatus(AccountStatus.PENDING_VERIFICATION)
                    .emailVerified(false)
                    .build();

            LoginRequest request = LoginRequest.builder()
                    .login("unverified@devflow.com")
                    .password("correctPassword")
                    .build();

            when(userRepository.findByEmailOrUsername("unverified@devflow.com", "unverified@devflow.com"))
                    .thenReturn(Optional.of(unverifiedUser));
            when(passwordEncoder.matches("correctPassword", "encoded-password")).thenReturn(true);

            assertThatThrownBy(() -> authenticationService.authenticate(request, "127.0.0.1", "Mozilla"))
                    .isInstanceOf(EmailNotVerifiedException.class)
                    .hasMessageContaining("Email address has not been verified");
        }
    }

    @Nested
    @DisplayName("Token Refresh & Logout")
    class RefreshAndLogout {

        @Test
        @DisplayName("refreshAuthentication rotates token and issues new AuthenticationResponse")
        void refreshAuthentication_successful() {
            RefreshTokenRequest request = RefreshTokenRequest.builder()
                    .refreshToken("old-refresh-token")
                    .build();

            String newRefreshToken = "new-refresh-token";
            when(refreshTokenService.rotateRefreshToken("old-refresh-token", "127.0.0.1", "Mozilla")).thenReturn(newRefreshToken);

            RefreshToken tokenEntity = RefreshToken.builder()
                    .user(activeUser)
                    .tokenHash("hash")
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();

            when(refreshTokenService.validateRefreshToken(newRefreshToken)).thenReturn(Optional.of(tokenEntity));
            when(userDetailsService.loadUserByUsername("alexdev")).thenReturn(new DevFlowUserDetails(activeUser));
            when(jwtTokenProvider.generateAccessToken(any())).thenReturn("new-access-token");

            AuthenticationResponse response = authenticationService.refreshAuthentication(request, "127.0.0.1", "Mozilla");

            assertThat(response).isNotNull();
            assertThat(response.getAccessToken()).isEqualTo("new-access-token");
            assertThat(response.getRefreshToken()).isEqualTo("new-refresh-token");
        }

        @Test
        @DisplayName("logout delegates token revocation to RefreshTokenService")
        void logout_successful() {
            authenticationService.logout("refresh-token-to-revoke");

            verify(refreshTokenService).revokeRefreshToken("refresh-token-to-revoke");
        }

        @Test
        @DisplayName("logoutAllDevices delegates bulk revocation to RefreshTokenService")
        void logoutAllDevices_successful() {
            authenticationService.logoutAllDevices(userId);

            verify(refreshTokenService).revokeAllUserTokens(userId);
        }
    }
}
