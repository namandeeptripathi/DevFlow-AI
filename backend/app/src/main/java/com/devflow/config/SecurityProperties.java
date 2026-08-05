package com.devflow.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Strongly-typed configuration properties for all DevFlow security settings.
 *
 * <p>Bound from the {@code devflow.security} YAML namespace. Values are validated
 * at application startup (fail-fast principle from Configuration Strategy §2.7):
 * if any required property is absent or malformed, the application refuses to start.
 *
 * <p>Environment-specific overrides:
 * <ul>
 *   <li>{@code application-dev.yml}  — development CORS origins, relaxed settings</li>
 *   <li>{@code application-test.yml} — test CORS origins</li>
 *   <li>{@code application-prod.yml} — all secrets via {@code ${ENV_VAR}}</li>
 * </ul>
 *
 * @see com.devflow.security.SecurityConfiguration
 * @see <a href="../../../../docs/configuration/CONFIGURATION_STRATEGY.md">Configuration Strategy</a>
 */
@ConfigurationProperties(prefix = "devflow.security")
@Validated
public class SecurityProperties {

    @Valid
    @NotNull
    @NestedConfigurationProperty
    private Jwt jwt = new Jwt();

    @Valid
    @NotNull
    @NestedConfigurationProperty
    private Cors cors = new Cors();

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    // ── Nested configuration types ────────────────────────────────────────────

    /**
     * JWT lifecycle parameters.
     *
     * <p>These settings govern token lifetimes and the issuer claim.
     * The RS256 signing key is <em>never</em> stored in application configuration —
     * it is loaded from the secrets manager at startup.
     *
     * @see <a href="../../../../docs/security/AUTHENTICATION_STRATEGY.md">Authentication Strategy §5</a>
     */
    public static class Jwt {

        private String secret;

        private String issuer = "devflow-backend";

        private long accessTokenExpiration = 900000L;

        private long refreshTokenExpiration = 604800000L;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public long getAccessTokenExpiration() {
            return accessTokenExpiration;
        }

        public void setAccessTokenExpiration(long accessTokenExpiration) {
            this.accessTokenExpiration = accessTokenExpiration;
        }

        public long getRefreshTokenExpiration() {
            return refreshTokenExpiration;
        }

        public void setRefreshTokenExpiration(long refreshTokenExpiration) {
            this.refreshTokenExpiration = refreshTokenExpiration;
        }
    }

    /**
     * CORS (Cross-Origin Resource Sharing) settings.
     *
     * <p>Permitted origins are never hardcoded — they are supplied per-environment
     * via profile-specific YAML or environment variables, satisfying
     * Configuration Strategy §2.1 (no hardcoded environment values).
     *
     * <p>An empty {@code allowedOrigins} list is secure-by-default: it means no
     * cross-origin requests are permitted from any origin. Each environment's
     * configuration must explicitly enumerate the permitted origins.
     *
     * @see com.devflow.security.SecurityConfiguration
     * @see <a href="../../../../docs/configuration/CONFIGURATION_STRATEGY.md">Configuration Strategy §2.1</a>
     */
    public static class Cors {

        /**
         * Origins permitted to make cross-origin requests.
         * Must be explicitly set per environment — never a wildcard {@code *} when
         * {@code allowCredentials} is {@code true}.
         *
         * <p>Example: {@code [https://app.devflow.com, https://dashboard.devflow.com]}
         */
        @NotNull
        private List<String> allowedOrigins = List.of();

        /**
         * HTTP methods permitted in cross-origin requests.
         * OPTIONS is required for preflight; DELETE is required for resource removal.
         */
        @NotNull
        private List<String> allowedMethods = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

        /**
         * Request headers permitted in cross-origin requests.
         * {@code ["*"]} permits all headers, which is acceptable because
         * {@code allowCredentials} already scopes requests to trusted origins.
         */
        @NotNull
        private List<String> allowedHeaders = List.of("*");

        /**
         * Response headers the browser is allowed to expose to JavaScript.
         * {@code X-Request-ID} is required for client-side correlation of errors.
         *
         * @see <a href="../../../../docs/api/ERROR_RESPONSE_FORMAT.md">Error Response Format §9</a>
         */
        @NotNull
        private List<String> exposedHeaders = List.of("X-Request-ID");

        /**
         * Whether credentials (Authorization header, cookies) may be included in
         * cross-origin requests. Must remain {@code false} if {@code allowedOrigins}
         * contains the wildcard {@code *}.
         */
        private boolean allowCredentials = true;

        /**
         * Duration in seconds that the browser may cache preflight responses.
         * Default: 3600 seconds (1 hour).
         */
        @Min(0)
        private long maxAgeSecs = 3600L;

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public List<String> getAllowedMethods() {
            return allowedMethods;
        }

        public void setAllowedMethods(List<String> allowedMethods) {
            this.allowedMethods = allowedMethods;
        }

        public List<String> getAllowedHeaders() {
            return allowedHeaders;
        }

        public void setAllowedHeaders(List<String> allowedHeaders) {
            this.allowedHeaders = allowedHeaders;
        }

        public List<String> getExposedHeaders() {
            return exposedHeaders;
        }

        public void setExposedHeaders(List<String> exposedHeaders) {
            this.exposedHeaders = exposedHeaders;
        }

        public boolean isAllowCredentials() {
            return allowCredentials;
        }

        public void setAllowCredentials(boolean allowCredentials) {
            this.allowCredentials = allowCredentials;
        }

        public long getMaxAgeSecs() {
            return maxAgeSecs;
        }

        public void setMaxAgeSecs(long maxAgeSecs) {
            this.maxAgeSecs = maxAgeSecs;
        }
    }
}
