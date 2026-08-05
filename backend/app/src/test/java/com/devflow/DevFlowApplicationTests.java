package com.devflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Application context load test.
 *
 * <p>Verifies that the Spring application context starts successfully with all
 * registered modules, configuration bindings, and infrastructure integrations.
 * This test uses the {@code test} profile, which activates Testcontainers-managed
 * PostgreSQL and Redis instances instead of real infrastructure.
 *
 * <p>If this test fails, the application cannot start in any environment.
 * All configuration validation, bean wiring, and Flyway migrations are
 * exercised here before any domain-level tests run.
 */
@SpringBootTest
@ActiveProfiles("test")
class DevFlowApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the Spring context loads without errors.
        // No assertions required — failure to load throws an exception.
    }
}
