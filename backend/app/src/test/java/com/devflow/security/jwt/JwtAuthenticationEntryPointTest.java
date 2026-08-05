package com.devflow.security.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtAuthenticationEntryPoint")
class JwtAuthenticationEntryPointTest {

    private JwtAuthenticationEntryPoint entryPoint;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        entryPoint = new JwtAuthenticationEntryPoint(objectMapper);
    }

    @Test
    @DisplayName("commence sets 401 status and JSON error response envelope")
    void commence_sets401AndJsonErrorBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("Invalid token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/json");

        String content = response.getContentAsString();
        assertThat(content).contains("\"status\":401");
        assertThat(content).contains("\"error\":\"Unauthorized\"");
        assertThat(content).contains("\"message\":\"Full authentication is required to access this resource.\"");
        assertThat(content).contains("\"path\":\"/api/v1/projects\"");
        assertThat(content).doesNotContain("Invalid token"); // Does not leak internal exception details
    }
}
