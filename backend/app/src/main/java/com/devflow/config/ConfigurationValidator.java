package com.devflow.config;

import com.devflow.security.jwt.JwtProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Startup fail-fast security configuration validator (Stage 9).
 *
 * <p>Prevents unsafe security posture across all profiles (such as wildcard CORS with credentials)
 * and rejects insecure default secrets or exposed developer endpoints when the {@code prod} profile
 * is active.
 */
@Component
public class ConfigurationValidator {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationValidator.class);

    public static final String DEFAULT_DEV_JWT_SECRET =
            "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970"
                    + "337336763979244226452948404D635166546A576E5A7234753778214125442A";

    public static final String DEFAULT_DEV_AUDIT_INTEGRITY_KEY =
            "devflow-audit-default-secret-key-32-bytes-minimum!";

    private static final int MIN_JWT_SECRET_BYTES = 64;
    private static final int MIN_AUDIT_KEY_BYTES = 32;

    private final Environment environment;
    private final JwtProperties jwtProperties;
    private final SecurityProperties securityProperties;
    private final String auditIntegrityKey;
    private final boolean springDocApiDocsEnabled;
    private final boolean springDocSwaggerUiEnabled;

    public ConfigurationValidator(
            Environment environment,
            JwtProperties jwtProperties,
            SecurityProperties securityProperties,
            @Value("${devflow.security.audit.integrity-key:" + DEFAULT_DEV_AUDIT_INTEGRITY_KEY + "}") String auditIntegrityKey,
            @Value("${springdoc.api-docs.enabled:false}") boolean springDocApiDocsEnabled,
            @Value("${springdoc.swagger-ui.enabled:false}") boolean springDocSwaggerUiEnabled
    ) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        this.jwtProperties = Objects.requireNonNull(jwtProperties, "jwtProperties must not be null");
        this.securityProperties = Objects.requireNonNull(securityProperties, "securityProperties must not be null");
        this.auditIntegrityKey = auditIntegrityKey;
        this.springDocApiDocsEnabled = springDocApiDocsEnabled;
        this.springDocSwaggerUiEnabled = springDocSwaggerUiEnabled;
    }

    @PostConstruct
    public void validate() {
        validateCorsSafety(false);

        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            validateProductionSettings();
            log.info("Production security configuration validation passed.");
        }
    }

    private void validateProductionSettings() {
        String jwtSecret = jwtProperties.getSecret();
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("Production startup aborted: DEVFLOW_JWT_SECRET must be set and non-blank.");
        }
        if (DEFAULT_DEV_JWT_SECRET.equals(jwtSecret.trim())) {
            throw new IllegalStateException("Production startup aborted: default development JWT signing secret cannot be used in production.");
        }
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES) {
            throw new IllegalStateException("Production startup aborted: JWT signing secret must be at least 64 bytes (512 bits) for HS512.");
        }

        if (jwtProperties.getIssuer() == null || jwtProperties.getIssuer().isBlank()) {
            throw new IllegalStateException("Production startup aborted: devflow.security.jwt.issuer must not be blank.");
        }

        if (auditIntegrityKey == null || auditIntegrityKey.isBlank()) {
            throw new IllegalStateException("Production startup aborted: DEVFLOW_AUDIT_INTEGRITY_KEY must be set and non-blank.");
        }
        if (DEFAULT_DEV_AUDIT_INTEGRITY_KEY.equals(auditIntegrityKey.trim())) {
            throw new IllegalStateException("Production startup aborted: default development audit integrity key cannot be used in production.");
        }
        if (auditIntegrityKey.getBytes(StandardCharsets.UTF_8).length < MIN_AUDIT_KEY_BYTES) {
            throw new IllegalStateException("Production startup aborted: audit integrity key must be at least 32 bytes.");
        }

        if (springDocApiDocsEnabled || springDocSwaggerUiEnabled) {
            throw new IllegalStateException("Production startup aborted: SpringDoc OpenAPI / Swagger UI must be disabled in production.");
        }

        validateCorsSafety(true);
    }

    private void validateCorsSafety(boolean production) {
        SecurityProperties.Cors cors = securityProperties.getCors();
        if (cors == null) {
            return;
        }
        List<String> origins = cors.getAllowedOrigins();
        if (origins == null) {
            return;
        }
        for (String origin : origins) {
            if (origin == null || origin.isBlank()) {
                if (production) {
                    throw new IllegalStateException("Production startup aborted: CORS allowed-origins must not contain blank entries.");
                }
                continue;
            }
            if ("*".equals(origin.trim())) {
                if (cors.isAllowCredentials() || production) {
                    throw new IllegalStateException(
                            "Startup aborted: wildcard CORS origin '*' is prohibited when credentials are allowed or in production.");
                }
            }
        }
    }
}

