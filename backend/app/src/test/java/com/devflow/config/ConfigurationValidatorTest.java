package com.devflow.config;

import com.devflow.security.jwt.JwtProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.List;

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

