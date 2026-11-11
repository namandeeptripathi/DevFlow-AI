package com.devflow.config;

import com.devflow.security.jwt.JwtProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Stage 9: Production Configuration Validator Tests")
class ConfigurationValidatorTest {

    private static final String VALID_PROD_JWT_SECRET =
            "9a7f4c2e1b8d0f6a3c5e7b9d1f3a5c7e9b0d2f4a6c8e0a1b3c5d7e9f1a3c5e7b9d1f3a5c7e9b0d2f4a";
    private static final String VALID_PROD_AUDIT_KEY =
            "prod-audit-hmac-key-0123456789abcdef-secure";

    @Test
    @DisplayName("Rejects default development JWT signing secret when prod profile is active")
    void rejectsDefaultJwtSecretInProd() {
        ConfigurationValidator validator = createValidator(
                "prod",
                ConfigurationValidator.DEFAULT_DEV_JWT_SECRET,
                "devflow-backend",
                VALID_PROD_AUDIT_KEY,
                List.of("https://app.devflow.com"),
                true,
                false,
                false
        );

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default development JWT signing secret");
    }

    @Test
    @DisplayName("Rejects short or blank JWT signing secret in prod")
    void rejectsShortJwtSecretInProd() {
        ConfigurationValidator validator = createValidator(
                "prod",
                "too-short-secret",
                "devflow-backend",
                VALID_PROD_AUDIT_KEY,
                List.of("https://app.devflow.com"),
                true,
                false,
                false
        );

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 64 bytes");
    }

    @Test
    @DisplayName("Rejects default development audit integrity key in prod")
    void rejectsDefaultAuditIntegrityKeyInProd() {
        ConfigurationValidator validator = createValidator(
                "prod",
                VALID_PROD_JWT_SECRET,
                "devflow-backend",
                ConfigurationValidator.DEFAULT_DEV_AUDIT_INTEGRITY_KEY,
                List.of("https://app.devflow.com"),
                true,
                false,
                false
        );

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default development audit integrity key");
    }

    @Test
    @DisplayName("Rejects short audit integrity key in prod")
    void rejectsShortAuditKeyInProd() {
        ConfigurationValidator validator = createValidator(
                "prod",
                VALID_PROD_JWT_SECRET,
                "devflow-backend",
                "short-audit-key",
                List.of("https://app.devflow.com"),
                true,
                false,
                false
        );

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    @DisplayName("Rejects wildcard CORS origin when credentials are allowed or in prod")
    void rejectsWildcardCorsInProdAndWithCredentials() {
        ConfigurationValidator prodWildcard = createValidator(
                "prod",
                VALID_PROD_JWT_SECRET,
                "devflow-backend",
                VALID_PROD_AUDIT_KEY,
                List.of("*"),
                false,
                false,
                false
        );

        assertThatThrownBy(prodWildcard::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard CORS origin '*'");

        ConfigurationValidator devCredentialsWildcard = createValidator(
                "dev",
                ConfigurationValidator.DEFAULT_DEV_JWT_SECRET,
                "devflow-backend",
                ConfigurationValidator.DEFAULT_DEV_AUDIT_INTEGRITY_KEY,
                List.of("*"),
                true,
                true,
                true
        );

        assertThatThrownBy(devCredentialsWildcard::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard CORS origin '*'");
    }

    @Test
    @DisplayName("Rejects blank CORS origin entries and enabled SpringDoc in prod")
    void rejectsBlankCorsAndEnabledSpringDocInProd() {
        ConfigurationValidator blankOriginValidator = createValidator(
                "prod",
                VALID_PROD_JWT_SECRET,
                "devflow-backend",
                VALID_PROD_AUDIT_KEY,
                Arrays.asList("https://app.devflow.com", " "),
                true,
                false,
                false
        );

        assertThatThrownBy(blankOriginValidator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank entries");

        ConfigurationValidator swaggerEnabledValidator = createValidator(
                "prod",
                VALID_PROD_JWT_SECRET,
                "devflow-backend",
                VALID_PROD_AUDIT_KEY,
                List.of("https://app.devflow.com"),
                true,
                false,
                true
        );

        assertThatThrownBy(swaggerEnabledValidator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Swagger UI must be disabled");
    }

    @Test
    @DisplayName("Passes when all production security settings are properly configured")
    void passesWithValidProductionSettings() {
        ConfigurationValidator validator = createValidator(
                "prod",
                VALID_PROD_JWT_SECRET,
                "devflow-backend-prod",
                VALID_PROD_AUDIT_KEY,
                List.of("https://app.devflow.com"),
                true,
                false,
                false
        );

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("application-prod.yml binds safely with no CORS env var set and hardens prod flags")
    void prodProfileBindsSafelyWithoutCorsEnvVar() {
        prodContextRunner(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            SecurityProperties sec = context.getBean(SecurityProperties.class);
            assertThat(sec.getCors().getAllowedOrigins()).isEmpty();
            ConfigurationValidator validator = context.getBean(ConfigurationValidator.class);
            assertThatCode(validator::validate).doesNotThrowAnyException();

            org.springframework.core.env.Environment env = context.getEnvironment();
            assertThat(env.getProperty("spring.data.redis.ssl.enabled", Boolean.class)).isTrue();
            assertThat(env.getProperty("spring.jpa.show-sql", Boolean.class)).isFalse();
            assertThat(env.getProperty("spring.flyway.validate-on-migrate", Boolean.class)).isTrue();
            assertThat(env.getProperty("spring.flyway.out-of-order", Boolean.class)).isFalse();
            assertThat(env.getProperty("spring.flyway.baseline-on-migrate", Boolean.class)).isFalse();
            assertThat(env.getProperty("springdoc.api-docs.enabled", Boolean.class)).isFalse();
            assertThat(env.getProperty("springdoc.swagger-ui.enabled", Boolean.class)).isFalse();
            assertThat(env.getProperty("devflow.security.audit.in-memory-buffer-size", Integer.class)).isZero();
            assertThat(env.getProperty("management.endpoints.web.exposure.include"))
                    .isEqualTo("health,info,metrics,prometheus");
            assertThat(env.getProperty("management.endpoint.health.show-details"))
                    .isEqualTo("when-authorized");
        });
    }

    @Test
    @DisplayName("application-prod.yml honors documented DEVFLOW_SECURITY_CORS_ALLOWED_ORIGINS and DEVFLOW_CORS_ALLOWED_ORIGINS")
    void prodProfileHonorsDocumentedCorsEnvVars() {
        prodContextRunner(Map.of(
                "DEVFLOW_SECURITY_CORS_ALLOWED_ORIGINS",
                "https://app.devflow.com,https://dashboard.devflow.com"
        )).run(context -> {
            assertThat(context).hasNotFailed();
            SecurityProperties sec = context.getBean(SecurityProperties.class);
            assertThat(sec.getCors().getAllowedOrigins())
                    .containsExactly("https://app.devflow.com", "https://dashboard.devflow.com");
        });

        prodContextRunner(Map.of(
                "DEVFLOW_CORS_ALLOWED_ORIGINS",
                "https://portal.devflow.com"
        )).run(context -> {
            assertThat(context).hasNotFailed();
            SecurityProperties sec = context.getBean(SecurityProperties.class);
            assertThat(sec.getCors().getAllowedOrigins())
                    .containsExactly("https://portal.devflow.com");
        });
    }

    private ApplicationContextRunner prodContextRunner(Map<String, Object> envOverrides) {
        Map<String, Object> envVars = new java.util.HashMap<>(envOverrides);
        envVars.putIfAbsent("DEVFLOW_DB_URL", "jdbc:postgresql://localhost:5432/devflow_test");
        envVars.putIfAbsent("DEVFLOW_DB_USERNAME", "devflow");
        envVars.putIfAbsent("DEVFLOW_DB_PASSWORD", "devflow_secret");
        envVars.putIfAbsent("DEVFLOW_REDIS_HOST", "localhost");
        envVars.putIfAbsent("DEVFLOW_REDIS_PASSWORD", "redis-secret");
        envVars.putIfAbsent("DEVFLOW_JWT_SECRET", VALID_PROD_JWT_SECRET);
        envVars.putIfAbsent("DEVFLOW_AUDIT_INTEGRITY_KEY", VALID_PROD_AUDIT_KEY);

        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new SystemEnvironmentPropertySource("systemEnvironment", envVars));
                    new ConfigDataApplicationContextInitializer().initialize(context);
                })
                .withPropertyValues("spring.profiles.active=prod")
                .withUserConfiguration(ProdConfigProbe.class, ConfigurationValidator.class);
    }

    @Configuration
    @EnableConfigurationProperties({JwtProperties.class, SecurityProperties.class})
    static class ProdConfigProbe {
    }

    private ConfigurationValidator createValidator(
            String profile,
            String jwtSecret,
            String issuer,
            String auditKey,
            List<String> corsOrigins,
            boolean allowCredentials,
            boolean springDocApiDocs,
            boolean springDocSwagger
    ) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);

        JwtProperties jwt = new JwtProperties();
        jwt.setSecret(jwtSecret);
        jwt.setIssuer(issuer);

        SecurityProperties sec = new SecurityProperties();
        sec.getCors().setAllowedOrigins(corsOrigins);
        sec.getCors().setAllowCredentials(allowCredentials);

        return new ConfigurationValidator(
                env,
                jwt,
                sec,
                auditKey,
                springDocApiDocs,
                springDocSwagger
        );
    }
}

