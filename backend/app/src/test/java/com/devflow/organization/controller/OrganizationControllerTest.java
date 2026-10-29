package com.devflow.organization.controller;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.dto.CreateOrganizationRequest;
import com.devflow.organization.dto.UpdateOrganizationRequest;
import com.devflow.organization.exception.OrganizationAccessDeniedException;
import com.devflow.organization.exception.OrganizationAlreadyExistsException;
import com.devflow.organization.exception.OrganizationMembershipRequiredException;
import com.devflow.organization.exception.OrganizationNotFoundException;
import com.devflow.organization.service.OrganizationService;
import com.devflow.security.jwt.JwtAuthenticationEntryPoint;
import com.devflow.security.jwt.JwtTokenProvider;
import com.devflow.security.user.CustomUserDetailsService;
import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrganizationController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("OrganizationController REST API Tests")
class OrganizationControllerTest {

    private static final String BASE_URL = "/api/v1/organizations";
    private static final String ITEM_URL = "/api/v1/organizations/{id}";
    private static final String SLUG_URL = "/api/v1/organizations/slug/{slug}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrganizationService organizationService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    @MockBean
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    private UUID organizationId;
    private UUID userId;
    private User testUser;
    private DevFlowUserDetails userDetails;
    private Organization testOrg;

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        userId = UUID.randomUUID();

        testUser = User.builder()
                .id(userId)
                .email("admin@devflow.com")
                .username("adminuser")
                .accountStatus(AccountStatus.ACTIVE)
                .build();

        userDetails = new DevFlowUserDetails(testUser);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);

        testOrg = Organization.builder()
                .id(organizationId)
                .name("Acme Corp")
                .slug("acme-corp")
                .description("Acme software development")
                .owner(testUser)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    // ── POST /api/v1/organizations ────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/organizations")
    class CreateOrganizationEndpoint {

        @Test
        @DisplayName("valid request returns 201 Created with OrganizationResponse")
        void createOrganization_validRequest_returns201() throws Exception {
            CreateOrganizationRequest request = CreateOrganizationRequest.builder()
                    .name("Acme Corp")
                    .slug("acme-corp")
                    .description("Acme software development")
                    .build();

            when(organizationService.createOrganization(
                    eq("Acme Corp"), eq("acme-corp"), eq("Acme software development"), eq(userId)))
                    .thenReturn(testOrg);

            mockMvc.perform(post(BASE_URL)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(organizationId.toString()))
                    .andExpect(jsonPath("$.name").value("Acme Corp"))
                    .andExpect(jsonPath("$.slug").value("acme-corp"))
                    .andExpect(jsonPath("$.description").value("Acme software development"))
                    .andExpect(jsonPath("$.ownerId").value(userId.toString()));

            verify(organizationService).createOrganization(
                    eq("Acme Corp"), eq("acme-corp"), eq("Acme software development"), eq(userId));
        }

        @Test
        @DisplayName("invalid request with blank name returns 400 Bad Request")
        void createOrganization_blankName_returns400() throws Exception {
            CreateOrganizationRequest request = CreateOrganizationRequest.builder()
                    .name("")
                    .slug("acme-corp")
                    .build();

            mockMvc.perform(post(BASE_URL)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"));

            verify(organizationService, never()).createOrganization(any(), any(), any(), any());
        }

        @Test
        @DisplayName("invalid request with invalid slug regex returns 400 Bad Request")
        void createOrganization_invalidSlugPattern_returns400() throws Exception {
            CreateOrganizationRequest request = CreateOrganizationRequest.builder()
                    .name("Acme Corp")
                    .slug("Acme_Corp") // uppercase and underscore disallowed
                    .build();

            mockMvc.perform(post(BASE_URL)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            verify(organizationService, never()).createOrganization(any(), any(), any(), any());
        }

        @Test
        @DisplayName("invalid request with name exceeding 100 characters returns 400 Bad Request")
        void createOrganization_nameTooLong_returns400() throws Exception {
            CreateOrganizationRequest request = CreateOrganizationRequest.builder()
                    .name("A".repeat(101))
                    .slug("acme-corp")
                    .build();

            mockMvc.perform(post(BASE_URL)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("duplicate slug returns 409 Conflict")
        void createOrganization_duplicateSlug_returns409() throws Exception {
            CreateOrganizationRequest request = CreateOrganizationRequest.builder()
                    .name("Acme Corp")
                    .slug("acme-corp")
                    .build();

            when(organizationService.createOrganization(any(), any(), any(), eq(userId)))
                    .thenThrow(new OrganizationAlreadyExistsException("Organization already exists with slug: acme-corp"));

            mockMvc.perform(post(BASE_URL)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.error").value("Conflict"));
        }
    }

    // ── PATCH /api/v1/organizations/{id} ──────────────────────────────────────

    @Nested
    @DisplayName("PATCH /api/v1/organizations/{id}")
    class UpdateOrganizationEndpoint {

        @Test
        @DisplayName("valid request returns 200 OK with updated OrganizationResponse")
        void updateOrganization_validRequest_returns200() throws Exception {
            UpdateOrganizationRequest request = UpdateOrganizationRequest.builder()
                    .name("New Acme Corp")
                    .description("Updated description")
                    .build();

            Organization updatedOrg = Organization.builder()
                    .id(organizationId)
                    .name("New Acme Corp")
                    .slug("acme-corp")
                    .description("Updated description")
                    .owner(testUser)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            when(organizationService.updateOrganization(
                    eq(organizationId), eq("New Acme Corp"), eq("Updated description"), eq(userId)))
                    .thenReturn(updatedOrg);

            mockMvc.perform(patch(ITEM_URL, organizationId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(organizationId.toString()))
                    .andExpect(jsonPath("$.name").value("New Acme Corp"))
                    .andExpect(jsonPath("$.description").value("Updated description"))
                    .andExpect(jsonPath("$.slug").value("acme-corp"));

            verify(organizationService).updateOrganization(
                    eq(organizationId), eq("New Acme Corp"), eq("Updated description"), eq(userId));
        }

        @Test
        @DisplayName("unauthorized caller returns 403 Forbidden")
        void updateOrganization_unauthorized_returns403() throws Exception {
            UpdateOrganizationRequest request = UpdateOrganizationRequest.builder()
                    .name("New Name")
                    .build();

            when(organizationService.updateOrganization(eq(organizationId), any(), any(), eq(userId)))
                    .thenThrow(new OrganizationAccessDeniedException("Access denied: ADMIN or OWNER role required"));

            mockMvc.perform(patch(ITEM_URL, organizationId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.error").value("Forbidden"));
        }

        @Test
        @DisplayName("non-existent organization returns 404 Not Found")
        void updateOrganization_notFound_returns404() throws Exception {
            UpdateOrganizationRequest request = UpdateOrganizationRequest.builder()
                    .name("New Name")
                    .build();

            when(organizationService.updateOrganization(eq(organizationId), any(), any(), eq(userId)))
                    .thenThrow(new OrganizationNotFoundException("Organization not found: " + organizationId));

            mockMvc.perform(patch(ITEM_URL, organizationId)
                            .with(user(userDetails))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }
    }

    // ── DELETE /api/v1/organizations/{id} ─────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/v1/organizations/{id}")
    class DeleteOrganizationEndpoint {

        @Test
        @DisplayName("authorized owner returns 204 No Content")
        void deleteOrganization_authorizedOwner_returns204() throws Exception {
            doNothing().when(organizationService).deleteOrganization(eq(organizationId), eq(userId));

            mockMvc.perform(delete(ITEM_URL, organizationId)
                            .with(user(userDetails)))
                    .andExpect(status().isNoContent());

            verify(organizationService).deleteOrganization(eq(organizationId), eq(userId));
        }

        @Test
        @DisplayName("caller lacking OWNER role returns 403 Forbidden")
        void deleteOrganization_unauthorized_returns403() throws Exception {
            doThrow(new OrganizationAccessDeniedException("Access denied: OWNER role required"))
                    .when(organizationService).deleteOrganization(eq(organizationId), eq(userId));

            mockMvc.perform(delete(ITEM_URL, organizationId)
                            .with(user(userDetails)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("non-existent organization returns 404 Not Found")
        void deleteOrganization_notFound_returns404() throws Exception {
            doThrow(new OrganizationNotFoundException("Organization not found: " + organizationId))
                    .when(organizationService).deleteOrganization(eq(organizationId), eq(userId));

            mockMvc.perform(delete(ITEM_URL, organizationId)
                            .with(user(userDetails)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    // ── GET /api/v1/organizations/{id} ────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/organizations/{id}")
    class GetOrganizationByIdEndpoint {

        @Test
        @DisplayName("active member returns 200 OK with OrganizationResponse")
        void getOrganizationById_activeMember_returns200() throws Exception {
            when(organizationService.getOrganizationById(eq(organizationId), eq(userId)))
                    .thenReturn(testOrg);

            mockMvc.perform(get(ITEM_URL, organizationId)
                            .with(user(userDetails)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(organizationId.toString()))
                    .andExpect(jsonPath("$.name").value("Acme Corp"))
                    .andExpect(jsonPath("$.slug").value("acme-corp"));

            verify(organizationService).getOrganizationById(eq(organizationId), eq(userId));
        }

        @Test
        @DisplayName("non-member caller returns 403 Forbidden")
        void getOrganizationById_nonMember_returns403() throws Exception {
            when(organizationService.getOrganizationById(eq(organizationId), eq(userId)))
                    .thenThrow(new OrganizationMembershipRequiredException("Membership required"));

            mockMvc.perform(get(ITEM_URL, organizationId)
                            .with(user(userDetails)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("non-existent organization returns 404 Not Found")
        void getOrganizationById_notFound_returns404() throws Exception {
            when(organizationService.getOrganizationById(eq(organizationId), eq(userId)))
                    .thenThrow(new OrganizationNotFoundException("Organization not found: " + organizationId));

            mockMvc.perform(get(ITEM_URL, organizationId)
                            .with(user(userDetails)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    // ── GET /api/v1/organizations/slug/{slug} ─────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/organizations/slug/{slug}")
    class GetOrganizationBySlugEndpoint {

        @Test
        @DisplayName("active member returns 200 OK with OrganizationResponse")
        void getOrganizationBySlug_activeMember_returns200() throws Exception {
            when(organizationService.getOrganizationBySlug(eq("acme-corp"), eq(userId)))
                    .thenReturn(testOrg);

            mockMvc.perform(get(SLUG_URL, "acme-corp")
                            .with(user(userDetails)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(organizationId.toString()))
                    .andExpect(jsonPath("$.name").value("Acme Corp"))
                    .andExpect(jsonPath("$.slug").value("acme-corp"));

            verify(organizationService).getOrganizationBySlug(eq("acme-corp"), eq(userId));
        }

        @Test
        @DisplayName("non-member caller returns 403 Forbidden")
        void getOrganizationBySlug_nonMember_returns403() throws Exception {
            when(organizationService.getOrganizationBySlug(eq("acme-corp"), eq(userId)))
                    .thenThrow(new OrganizationMembershipRequiredException("Membership required"));

            mockMvc.perform(get(SLUG_URL, "acme-corp")
                            .with(user(userDetails)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("non-existent slug returns 404 Not Found")
        void getOrganizationBySlug_notFound_returns404() throws Exception {
            when(organizationService.getOrganizationBySlug(eq("unknown-slug"), eq(userId)))
                    .thenThrow(new OrganizationNotFoundException("Organization not found with slug: unknown-slug"));

            mockMvc.perform(get(SLUG_URL, "unknown-slug")
                            .with(user(userDetails)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }
}
