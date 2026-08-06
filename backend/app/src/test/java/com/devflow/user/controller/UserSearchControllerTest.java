package com.devflow.user.controller;

import com.devflow.common.ApiPaths;
import com.devflow.security.jwt.JwtAuthenticationEntryPoint;
import com.devflow.security.jwt.JwtTokenProvider;
import com.devflow.security.user.CustomUserDetailsService;
import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.dto.UserSearchResponse;
import com.devflow.user.dto.UserSearchResult;
import com.devflow.user.exception.InvalidSearchQueryException;
import com.devflow.user.service.UserSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserSearchController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("UserSearchController REST API")
class UserSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserSearchService userSearchService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    @MockBean
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    private DevFlowUserDetails userDetails;
    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        User testUser = User.builder().id(UUID.randomUUID()).email("searcher@devflow.com").username("searcher").accountStatus(AccountStatus.ACTIVE).build();
        userDetails = new DevFlowUserDetails(testUser);
        auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    }

    @Test
    @DisplayName("GET /api/v1/users/search returns 200 OK with UserSearchResponse")
    void searchUsers_success() throws Exception {
        UserSearchResult item = UserSearchResult.builder()
                .userId(UUID.randomUUID())
                .username("amansh")
                .displayName("Aman Sharma")
                .avatarUrl("http://localhost/avatar.png")
                .build();

        UserSearchResponse response = UserSearchResponse.fromPage(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));
        when(userSearchService.searchUsers(eq("aman"), any())).thenReturn(response);

        mockMvc.perform(get(ApiPaths.USERS_SEARCH)
                        .param("q", "aman")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].username").value("amansh"))
                .andExpect(jsonPath("$.content[0].displayName").value("Aman Sharma"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/users/search returns 400 Bad Request when query is blank")
    void searchUsers_blankQuery_returns400() throws Exception {
        when(userSearchService.searchUsers(eq("   "), any()))
                .thenThrow(new InvalidSearchQueryException("Search query must not be blank"));

        mockMvc.perform(get(ApiPaths.USERS_SEARCH)
                        .param("q", "   ")
                        .with(authentication(auth)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Search query must not be blank"));
    }
}
