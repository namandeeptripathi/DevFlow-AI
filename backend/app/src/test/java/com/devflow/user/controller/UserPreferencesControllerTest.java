package com.devflow.user.controller;

import com.devflow.common.ApiPaths;
import com.devflow.security.jwt.JwtAuthenticationEntryPoint;
import com.devflow.security.jwt.JwtTokenProvider;
import com.devflow.security.user.CustomUserDetailsService;
import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.DateFormat;
import com.devflow.user.domain.Theme;
import com.devflow.user.domain.TimeFormat;
import com.devflow.user.domain.User;
import com.devflow.user.domain.UserPreferences;
import com.devflow.user.dto.NotificationPreferencesDto;
import com.devflow.user.dto.UpdateUserPreferencesRequest;
import com.devflow.user.exception.InvalidPreferencesException;
import com.devflow.user.service.UserPreferencesService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserPreferencesController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("UserPreferencesController REST API")
class UserPreferencesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserPreferencesService userPreferencesService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    @MockBean
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    private UUID userId;
    private User testUser;
    private DevFlowUserDetails userDetails;
    private UsernamePasswordAuthenticationToken auth;
    private UserPreferences testPreferences;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testUser = User.builder().id(userId).email("pref@devflow.com").username("prefuser").accountStatus(AccountStatus.ACTIVE).build();
        userDetails = new DevFlowUserDetails(testUser);
        auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);

        testPreferences = UserPreferences.builder()
                .id(UUID.randomUUID())
                .user(testUser)
                .theme(Theme.DARK)
                .timezone("UTC")
                .language("en")
                .dateFormat(DateFormat.ISO)
                .timeFormat(TimeFormat.TWENTY_FOUR_HOUR)
                .notificationPreferences(new HashMap<>())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/users/me/preferences returns 200 OK")
    void getMyPreferences_success() throws Exception {
        when(userPreferencesService.getPreferences(userId)).thenReturn(testPreferences);
        when(userPreferencesService.mapNotificationMapToDto(any())).thenReturn(
                NotificationPreferencesDto.builder().emailNotifications(true).build()
        );

        mockMvc.perform(get(ApiPaths.USERS_ME_PREFERENCES)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("DARK"))
                .andExpect(jsonPath("$.timezone").value("UTC"));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/me/preferences updates preferences and returns 200 OK")
    void updateMyPreferences_success() throws Exception {
        UpdateUserPreferencesRequest request = UpdateUserPreferencesRequest.builder()
                .theme(Theme.LIGHT)
                .timezone("Asia/Kolkata")
                .build();

        testPreferences.setTheme(Theme.LIGHT);
        testPreferences.setTimezone("Asia/Kolkata");

        when(userPreferencesService.updatePreferences(eq(userId), any())).thenReturn(testPreferences);
        when(userPreferencesService.mapNotificationMapToDto(any())).thenReturn(
                NotificationPreferencesDto.builder().emailNotifications(true).build()
        );

        mockMvc.perform(patch(ApiPaths.USERS_ME_PREFERENCES)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(userDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("LIGHT"))
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/me/preferences returns 400 Bad Request on invalid timezone")
    void updateMyPreferences_invalidTimezone_returns400() throws Exception {
        UpdateUserPreferencesRequest request = UpdateUserPreferencesRequest.builder()
                .timezone("Invalid/TZ")
                .build();

        when(userPreferencesService.updatePreferences(eq(userId), any()))
                .thenThrow(new InvalidPreferencesException("Invalid IANA timezone identifier: Invalid/TZ"));

        mockMvc.perform(patch(ApiPaths.USERS_ME_PREFERENCES)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(userDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid IANA timezone identifier: Invalid/TZ"));
    }
}
