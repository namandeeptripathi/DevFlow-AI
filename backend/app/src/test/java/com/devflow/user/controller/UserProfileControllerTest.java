package com.devflow.user.controller;

import com.devflow.common.ApiPaths;
import com.devflow.security.jwt.JwtAuthenticationEntryPoint;
import com.devflow.security.jwt.JwtTokenProvider;
import com.devflow.security.user.CustomUserDetailsService;
import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.domain.UserProfile;
import com.devflow.user.dto.UpdateUserProfileRequest;
import com.devflow.user.exception.UserProfileNotFoundException;
import com.devflow.user.service.AvatarService;
import com.devflow.user.service.UpdateProfileRequest;
import com.devflow.user.service.UserProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserProfileController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("UserProfileController REST API")
class UserProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserProfileService userProfileService;

    @MockBean
    private AvatarService avatarService;

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
    private UserProfile testProfile;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testUser = User.builder()
                .id(userId)
                .email("test@devflow.com")
                .username("testuser")
                .accountStatus(AccountStatus.ACTIVE)
                .build();

        userDetails = new DevFlowUserDetails(testUser);
        auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);

        testProfile = UserProfile.builder()
                .id(UUID.randomUUID())
                .user(testUser)
                .displayName("Test User")
                .firstName("Test")
                .lastName("User")
                .bio("Developer")
                .avatarUrl("http://localhost:8080/uploads/avatars/avatar.png")
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/users/me returns 200 OK with UserProfileResponse")
    void getMyProfile_success() throws Exception {
        when(userProfileService.getProfile(userId)).thenReturn(testProfile);

        mockMvc.perform(get(ApiPaths.USERS_ME)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.displayName").value("Test User"))
                .andExpect(jsonPath("$.bio").value("Developer"));
    }

    @Test
    @DisplayName("GET /api/v1/users/me returns 404 Not Found when profile missing")
    void getMyProfile_notFound() throws Exception {
        when(userProfileService.getProfile(userId))
                .thenThrow(new UserProfileNotFoundException("Profile not found for user: " + userId));

        mockMvc.perform(get(ApiPaths.USERS_ME)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(userDetails)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/me applies partial update and returns 200 OK")
    void updateMyProfile_success() throws Exception {
        UpdateUserProfileRequest httpRequest = UpdateUserProfileRequest.builder()
                .displayName("Updated Name")
                .build();

        testProfile.setDisplayName("Updated Name");
        when(userProfileService.updateProfile(eq(userId), any(UpdateProfileRequest.class)))
                .thenReturn(testProfile);

        mockMvc.perform(patch(ApiPaths.USERS_ME)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(userDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(httpRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Updated Name"));
    }

    @Test
    @DisplayName("POST /api/v1/users/me/avatar uploads avatar file and returns 200 OK")
    void uploadAvatar_success() throws Exception {
        MockMultipartFile avatarFile = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(avatarService.uploadAvatar(eq(userId), any())).thenReturn(testProfile);

        mockMvc.perform(multipart(ApiPaths.USERS_ME_AVATAR)
                        .file(avatarFile)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").isNotEmpty());

        verify(avatarService).uploadAvatar(eq(userId), any());
    }

    @Test
    @DisplayName("DELETE /api/v1/users/me/avatar deletes avatar and returns 204 No Content")
    void deleteAvatar_success() throws Exception {
        testProfile.setAvatarUrl(null);
        when(avatarService.deleteAvatar(userId)).thenReturn(testProfile);

        mockMvc.perform(delete(ApiPaths.USERS_ME_AVATAR)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(userDetails)))
                .andExpect(status().isNoContent());

        verify(avatarService).deleteAvatar(userId);
    }
}
