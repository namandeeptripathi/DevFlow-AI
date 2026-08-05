package com.devflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * DevFlow Application Bootstrap.
 *
 * <p>Entry point for the DevFlow AI-First Engineering Intelligence and Delivery Platform.
 * This class bootstraps the Spring application context, which in turn initializes all
 * registered domain modules, their configurations, and the embedded servlet container.
 *
 * <p>The application is profile-driven:
 * <ul>
 *   <li>{@code dev}  — local development with DevTools, relaxed security, verbose logging</li>
 *   <li>{@code test} — integration testing with Testcontainers-managed infrastructure</li>
 *   <li>{@code prod} — production-hardened settings, externalized secrets, minimal logging</li>
 * </ul>
 *
 * @see <a href="../../../resources/application.yml">application.yml</a>
 * @see <a href="../docs/configuration/CONFIGURATION_STRATEGY.md">Configuration Strategy</a>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DevFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevFlowApplication.class, args);
    }
}
