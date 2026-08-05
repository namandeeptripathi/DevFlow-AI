package com.devflow.security;

import com.devflow.common.ApiPaths;
import com.devflow.config.SecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security filter chain contract tests.
 *
 * <p>Verifies that {@link SecurityConfiguration} correctly enforces the authentication
 * boundary defined in the Security Filter Chain:
 * <ul>
 *   <li>All paths in {@link ApiPaths#PUBLIC_PATHS} are accessible without credentials.</li>
 *   <li>All other paths return {@code 401 Unauthorized} when credentials are absent.</li>
 *   <li>Authenticated principals can reach protected paths (past security layer).</li>
 *   <li>CORS preflight requests return appropriate headers for allowed origins.</li>
 *   <li>No session cookies are issued (stateless session policy).</li>
 * </ul>
 *
 * <p>Uses {@code @WebMvcTest} slice testing to isolate the web and security layers
 * without initializing database connections, Flyway migrations, or Testcontainers.
 */
import com.devflow.security.jwt.JwtAuthenticationEntryPoint;
import com.devflow.security.jwt.JwtAuthenticationFilter;
import com.devflow.security.jwt.JwtClaimsFactory;
import com.devflow.security.jwt.JwtProperties;
import com.devflow.security.jwt.JwtTokenProvider;
import com.devflow.user.repository.UserRepository;
import org.springframework.boot.test.mock.mockito.MockBean;

@WebMvcTest
@Import({
        SecurityConfiguration.class,
        PasswordEncoderConfiguration.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        JwtTokenProvider.class,
        JwtClaimsFactory.class,
        com.devflow.security.user.CustomUserDetailsService.class
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
@DisplayName("SecurityConfiguration")
class SecurityConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private com.devflow.auth.service.AuthenticationService authenticationService;

    // ── Public endpoint tests ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Public endpoints (permitAll)")
    class PublicEndpoints {

        private void assertNotBlockedBySecurity(String path) throws Exception {
            mockMvc.perform(get(path))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("Path [%s] must be publicly accessible (not 401 Unauthorized)", path)
                            .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
        }

        @Test
        @DisplayName("/actuator/health is accessible without authentication")
        void actuatorHealth_isPublic() throws Exception {
            assertNotBlockedBySecurity(ApiPaths.ACTUATOR_HEALTH);
        }

        @Test
        @DisplayName("/v3/api-docs root is accessible without authentication")
        void openApiDocs_isPublic() throws Exception {
            assertNotBlockedBySecurity("/v3/api-docs");
        }

        @Test
        @DisplayName("/v3/api-docs/swagger-config is accessible without authentication")
        void openApiDocsSubPath_isPublic() throws Exception {
            assertNotBlockedBySecurity("/v3/api-docs/swagger-config");
        }

        @Test
        @DisplayName("/swagger-ui.html is accessible without authentication")
        void swaggerUiHtml_isPublic() throws Exception {
            assertNotBlockedBySecurity(ApiPaths.SWAGGER_UI_HTML);
        }

        @Test
        @DisplayName("/swagger-ui/** resources are accessible without authentication")
        void swaggerUiResources_arePublic() throws Exception {
            assertNotBlockedBySecurity("/swagger-ui/index.html");
        }
    }

    // ── Protected endpoint tests ──────────────────────────────────────────────

    @Nested
    @DisplayName("Protected endpoints (require authentication)")
    class ProtectedEndpoints {

        @Test
        @DisplayName("GET /api/v1/** returns 401 Unauthorized without credentials")
        void apiV1_get_returns401_whenUnauthenticated() throws Exception {
            mockMvc.perform(get("/api/v1/projects"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /api/v1/** returns 401 Unauthorized without credentials")
        void apiV1_post_returns401_whenUnauthenticated() throws Exception {
            mockMvc.perform(post("/api/v1/projects"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Arbitrary path returns 401 Unauthorized without credentials")
        void arbitraryPath_returns401_whenUnauthenticated() throws Exception {
            mockMvc.perform(get("/some/protected/resource"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        @DisplayName("Authenticated user passes security (reaches controller layer)")
        void authenticatedUser_passesSecurityAndReachesControllerLayer() throws Exception {
            mockMvc.perform(get("/api/v1/projects"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("Authenticated request must not be blocked by security (not 401)")
                            .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("Authenticated admin user passes security")
        void authenticatedAdmin_passesSecurityAndReachesControllerLayer() throws Exception {
            mockMvc.perform(get("/api/v1/organizations"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("Authenticated admin must not be blocked by security (not 401)")
                            .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
        }
    }

    // ── CORS tests ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CORS configuration")
    class CorsConfiguration {

        @Test
        @DisplayName("Preflight OPTIONS request from allowed origin returns CORS headers")
        void preflightRequest_fromAllowedOrigin_returnsCorsHeaders() throws Exception {
            mockMvc.perform(options("/api/v1/projects")
                            .header("Origin", "http://localhost:3000")
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                    .andExpect(header().exists("Access-Control-Allow-Origin"))
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        }

        @Test
        @DisplayName("Request from disallowed origin does not receive CORS headers")
        void request_fromDisallowedOrigin_doesNotReceiveCorsHeaders() throws Exception {
            mockMvc.perform(get("/actuator/health")
                            .header("Origin", "https://malicious-site.example.com"))
                    .andExpect(result -> assertThat(
                            result.getResponse().getHeader("Access-Control-Allow-Origin"))
                            .as("Disallowed origin must not receive CORS allow header")
                            .isNullOrEmpty());
        }
    }

    // ── Session management tests ──────────────────────────────────────────────

    @Nested
    @DisplayName("Stateless session management")
    class StatelessSession {

        @Test
        @WithMockUser
        @DisplayName("Response does not set session cookie")
        void response_doesNotSetSessionCookie() throws Exception {
            mockMvc.perform(get("/api/v1/anything"))
                    .andExpect(result -> assertThat(result.getResponse().getHeader("Set-Cookie"))
                            .as("Stateless API must not set session cookie")
                            .isNullOrEmpty());
        }
    }
}
