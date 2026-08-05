package com.devflow.auth.controller;

import com.devflow.auth.dto.AuthenticationResponse;
import com.devflow.auth.dto.LoginRequest;
import com.devflow.auth.dto.RefreshTokenRequest;
import com.devflow.auth.dto.RegisterRequest;
import com.devflow.auth.exception.AccountLockedException;
import com.devflow.auth.exception.EmailNotVerifiedException;
import com.devflow.auth.exception.InvalidCredentialsException;
import com.devflow.auth.exception.UserAlreadyExistsException;
import com.devflow.auth.service.AuthenticationService;
import com.devflow.common.ApiPaths;
import com.devflow.config.SecurityProperties;
import com.devflow.exception.GlobalExceptionHandler;
import com.devflow.security.PasswordEncoderConfiguration;
import com.devflow.security.SecurityConfiguration;
import com.devflow.security.jwt.JwtAuthenticationEntryPoint;
import com.devflow.security.jwt.JwtAuthenticationFilter;
import com.devflow.security.jwt.JwtClaimsFactory;
import com.devflow.security.jwt.JwtProperties;
import com.devflow.security.jwt.JwtTokenProvider;
import com.devflow.security.user.CustomUserDetailsService;
import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({
        SecurityConfiguration.class,
        PasswordEncoderConfiguration.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        JwtTokenProvider.class,
        JwtClaimsFactory.class,
        CustomUserDetailsService.class,
        GlobalExceptionHandler.class
})
@EnableConfigurationProperties({SecurityProperties.class, JwtProperties.class})
@TestPropertySource(properties = {
        "devflow.security.jwt.secret=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970337336763979244226452948404D635166546A576E5A7234753778214125442A",
        "devflow.security.jwt.issuer=devflow-test",
        "devflow.security.jwt.access-token-expiration=900000",
        "devflow.security.jwt.refresh-token-expiration=604800000",
        "devflow.security.cors.allowed-origins=http://localhost:3000",
        "devflow.security.cors.allowed-methods=GET,POST,PUT,DELETE,OPTIONS",
        "devflow.security.cors.allowed-headers=*",
        "devflow.security.cors.exposed-headers=X-Request-ID",
        "devflow.security.cors.allow-credentials=true",
        "devflow.security.cors.max-age-secs=3600"
})
@DisplayName("AuthController REST Endpoints")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthenticationService authenticationService;

    @MockBean
    private UserRepository userRepository;

    private UUID testUserId;
    private AuthenticationResponse sampleResponse;
    private DevFlowUserDetails testUserDetails;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();

        sampleResponse = AuthenticationResponse.builder()
                .accessToken("access-token-xyz")
                .refreshToken("refresh-token-abc")
                .tokenType("Bearer")
                .expiresIn(900000L)
                .userId(testUserId)
                .username("testuser")
                .email("test@devflow.com")
                .build();

        User user = User.builder()
                .id(testUserId)
                .email("test@devflow.com")
                .username("testuser")
                .passwordHash("hash")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();

        testUserDetails = new DevFlowUserDetails(user);
    }

    @Nested
    @DisplayName("POST /api/v1/auth/register")
    class RegisterEndpoint {

        @Test
        @DisplayName("Returns 201 Created and AuthenticationResponse on valid payload")
        void register_validPayload_returns201() throws Exception {
            RegisterRequest request = RegisterRequest.builder()
                    .email("new@devflow.com")
                    .username("newuser")
                    .password("validPassword123")
                    .deviceId("device-1")
                    .deviceName("MacBook Pro")
                    .build();

            when(authenticationService.register(any(RegisterRequest.class), any(), any()))
                    .thenReturn(sampleResponse);

            mockMvc.perform(post(ApiPaths.AUTH_REGISTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accessToken").value("access-token-xyz"))
                    .andExpect(jsonPath("$.refreshToken").value("refresh-token-abc"))
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.username").value("testuser"))
                    .andExpect(jsonPath("$.email").value("test@devflow.com"));
        }

        @Test
        @DisplayName("Returns 400 Bad Request on invalid email format")
        void register_invalidEmail_returns400() throws Exception {
            RegisterRequest request = RegisterRequest.builder()
                    .email("not-an-email")
                    .username("validuser")
                    .password("validPassword123")
                    .build();

            mockMvc.perform(post(ApiPaths.AUTH_REGISTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("email")));
        }

        @Test
        @DisplayName("Returns 409 Conflict when user already exists")
        void register_duplicateUser_returns409() throws Exception {
            RegisterRequest request = RegisterRequest.builder()
                    .email("existing@devflow.com")
                    .username("existinguser")
                    .password("validPassword123")
                    .build();

            when(authenticationService.register(any(RegisterRequest.class), any(), any()))
                    .thenThrow(new UserAlreadyExistsException("User already exists with email: existing@devflow.com"));

            mockMvc.perform(post(ApiPaths.AUTH_REGISTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.error").value("Conflict"))
                    .andExpect(jsonPath("$.message").value("User already exists with email: existing@devflow.com"));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/login")
    class LoginEndpoint {

        @Test
        @DisplayName("Returns 200 OK and AuthenticationResponse on valid credentials")
        void login_validCredentials_returns200() throws Exception {
            LoginRequest request = LoginRequest.builder()
                    .login("test@devflow.com")
                    .password("correctPassword")
                    .build();

            when(authenticationService.authenticate(any(LoginRequest.class), any(), any()))
                    .thenReturn(sampleResponse);

            mockMvc.perform(post(ApiPaths.AUTH_LOGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("access-token-xyz"))
                    .andExpect(jsonPath("$.refreshToken").value("refresh-token-abc"));
        }

        @Test
        @DisplayName("Returns 401 Unauthorized on invalid credentials")
        void login_invalidCredentials_returns401() throws Exception {
            LoginRequest request = LoginRequest.builder()
                    .login("test@devflow.com")
                    .password("wrongPassword")
                    .build();

            when(authenticationService.authenticate(any(LoginRequest.class), any(), any()))
                    .thenThrow(new InvalidCredentialsException("Invalid username/email or password"));

            mockMvc.perform(post(ApiPaths.AUTH_LOGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.error").value("Unauthorized"))
                    .andExpect(jsonPath("$.message").value("Invalid username/email or password"));
        }

        @Test
        @DisplayName("Returns 403 Forbidden when account is locked")
        void login_lockedAccount_returns403() throws Exception {
            LoginRequest request = LoginRequest.builder()
                    .login("locked@devflow.com")
                    .password("password")
                    .build();

            when(authenticationService.authenticate(any(LoginRequest.class), any(), any()))
                    .thenThrow(new AccountLockedException("Account is locked or suspended"));

            mockMvc.perform(post(ApiPaths.AUTH_LOGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.error").value("Forbidden"))
                    .andExpect(jsonPath("$.message").value("Account is locked or suspended"));
        }

        @Test
        @DisplayName("Returns 403 Forbidden when email is not verified")
        void login_unverifiedEmail_returns403() throws Exception {
            LoginRequest request = LoginRequest.builder()
                    .login("unverified@devflow.com")
                    .password("password")
                    .build();

            when(authenticationService.authenticate(any(LoginRequest.class), any(), any()))
                    .thenThrow(new EmailNotVerifiedException("Email address has not been verified"));

            mockMvc.perform(post(ApiPaths.AUTH_LOGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.error").value("Forbidden"))
                    .andExpect(jsonPath("$.message").value("Email address has not been verified"));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/refresh")
    class RefreshEndpoint {

        @Test
        @DisplayName("Returns 200 OK and new AuthenticationResponse on valid refresh token")
        void refresh_validToken_returns200() throws Exception {
            RefreshTokenRequest request = RefreshTokenRequest.builder()
                    .refreshToken("valid-refresh-token")
                    .build();

            when(authenticationService.refreshAuthentication(any(RefreshTokenRequest.class), any(), any()))
                    .thenReturn(sampleResponse);

            mockMvc.perform(post(ApiPaths.AUTH_REFRESH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("access-token-xyz"))
                    .andExpect(jsonPath("$.refreshToken").value("refresh-token-abc"));
        }

        @Test
        @DisplayName("Returns 400 Bad Request when refreshToken is blank")
        void refresh_blankToken_returns400() throws Exception {
            RefreshTokenRequest request = RefreshTokenRequest.builder()
                    .refreshToken("")
                    .build();

            mockMvc.perform(post(ApiPaths.AUTH_REFRESH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/logout")
    class LogoutEndpoint {

        @Test
        @DisplayName("Returns 204 No Content on successful device logout")
        void logout_successful_returns204() throws Exception {
            RefreshTokenRequest request = RefreshTokenRequest.builder()
                    .refreshToken("token-to-revoke")
                    .build();

            doNothing().when(authenticationService).logout("token-to-revoke");

            mockMvc.perform(post(ApiPaths.AUTH_LOGOUT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNoContent());

            verify(authenticationService).logout("token-to-revoke");
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/logout-all")
    class LogoutAllEndpoint {

        @Test
        @DisplayName("Returns 204 No Content for authenticated user")
        void logoutAll_authenticated_returns204() throws Exception {
            doNothing().when(authenticationService).logoutAllDevices(testUserId);

            mockMvc.perform(post("/api/v1/auth/logout-all")
                            .with(user(testUserDetails)))
                    .andExpect(status().isNoContent());

            verify(authenticationService).logoutAllDevices(testUserId);
        }

        @Test
        @DisplayName("Returns 401 Unauthorized for unauthenticated request")
        void logoutAll_unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/auth/logout-all"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
