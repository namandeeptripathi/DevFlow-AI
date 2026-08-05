package com.devflow.auth;

import com.devflow.auth.dto.AuthenticationResponse;
import com.devflow.auth.dto.LoginRequest;
import com.devflow.auth.dto.RefreshTokenRequest;
import com.devflow.auth.dto.RegisterRequest;
import com.devflow.auth.password.PasswordResetService;
import com.devflow.auth.verification.EmailVerificationToken;
import com.devflow.auth.verification.EmailVerificationTokenRepository;

import com.devflow.common.ApiPaths;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Authentication Full Lifecycle Integration Test")
class AuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private PasswordResetService passwordResetService;

    @Test
    @DisplayName("Complete Authentication Lifecycle: Registration -> Verification -> Login -> Refresh -> Logout -> Reset Password")
    void completeAuthenticationLifecycle() throws Exception {
        // ── 1. Register User ───────────────────────────────────────────────────
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("e2e.user@devflow.com")
                .username("e2euser")
                .password("Password123!")
                .deviceId("device-mac-e2e")
                .deviceName("MacBook Pro")
                .build();

        MvcResult registerResult = mockMvc.perform(post(ApiPaths.AUTH_REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.email").value("e2e.user@devflow.com"))
                .andExpect(jsonPath("$.username").value("e2euser"))
                .andReturn();

        AuthenticationResponse regResponse = objectMapper.readValue(
                registerResult.getResponse().getContentAsString(),
                AuthenticationResponse.class
        );

        User user = userRepository.findByEmail("e2e.user@devflow.com").orElseThrow();
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.PENDING_VERIFICATION);

        // ── 2. Login fails before email verification ───────────────────────────
        LoginRequest loginUnverified = LoginRequest.builder()
                .login("e2e.user@devflow.com")
                .password("Password123!")
                .build();

        mockMvc.perform(post(ApiPaths.AUTH_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginUnverified)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));

        // ── 3. Verify Email & Activate Account ─────────────────────────────────
        List<EmailVerificationToken> pendingTokens = emailVerificationTokenRepository.findPendingByUserId(user.getId());
        assertThat(pendingTokens).hasSize(1);

        user.setEmailVerified(true);
        user.setAccountStatus(AccountStatus.ACTIVE);
        userRepository.save(user);

        // ── 4. Login after verification ────────────────────────────────────────
        LoginRequest loginRequest = LoginRequest.builder()
                .login("e2e.user@devflow.com")
                .password("Password123!")
                .deviceId("device-mac-e2e")
                .build();

        MvcResult loginResult = mockMvc.perform(post(ApiPaths.AUTH_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        AuthenticationResponse loginResponse = objectMapper.readValue(
                loginResult.getResponse().getContentAsString(),
                AuthenticationResponse.class
        );

        // ── 5. Refresh Tokens (Rotation) ──────────────────────────────────────
        RefreshTokenRequest refreshRequest = RefreshTokenRequest.builder()
                .refreshToken(loginResponse.getRefreshToken())
                .build();

        MvcResult refreshResult = mockMvc.perform(post(ApiPaths.AUTH_REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        AuthenticationResponse refreshResponse = objectMapper.readValue(
                refreshResult.getResponse().getContentAsString(),
                AuthenticationResponse.class
        );

        // ── 6. Logout Current Device ───────────────────────────────────────────
        RefreshTokenRequest logoutRequest = RefreshTokenRequest.builder()
                .refreshToken(refreshResponse.getRefreshToken())
                .build();

        mockMvc.perform(post(ApiPaths.AUTH_LOGOUT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutRequest)))
                .andExpect(status().isNoContent());

        // ── 7. Password Reset Request & Reset Execution ────────────────────────
        Optional<String> resetTokenOpt = passwordResetService.requestPasswordReset("e2e.user@devflow.com");
        assertThat(resetTokenOpt).isPresent();

        boolean resetSuccess = passwordResetService.resetPassword(resetTokenOpt.get(), "NewPassword456!");
        assertThat(resetSuccess).isTrue();

        // ── 8. Verify login with new password ──────────────────────────────────
        LoginRequest newLoginRequest = LoginRequest.builder()
                .login("e2euser")
                .password("NewPassword456!")
                .build();

        mockMvc.perform(post(ApiPaths.AUTH_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newLoginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }
}
