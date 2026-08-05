package com.devflow.security.jwt;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly typed configuration properties for JWT token creation and validation.
 *
 * <p>Bound from the {@code devflow.security.jwt} YAML configuration block:
 * <ul>
 *   <li>{@code secret}: Cryptographic signing secret (minimum 64 characters / 512 bits for HS512).</li>
 *   <li>{@code issuer}: Issuer claim value embedded in JWTs ({@code iss}).</li>
 *   <li>{@code accessTokenExpiration}: Access token validity duration in milliseconds.</li>
 *   <li>{@code refreshTokenExpiration}: Refresh token validity duration in milliseconds.</li>
 * </ul>
 *
 * @see JwtTokenProvider
 * @see <a href="../../../../../docs/security/AUTHENTICATION_STRATEGY.md">Authentication Strategy §5</a>
 */
@ConfigurationProperties(prefix = "devflow.security.jwt")
@Validated
public class JwtProperties {

    /**
     * Secret key for signing JWT tokens.
     * Must be at least 64 characters long to support HS512 HMAC signing.
     */
    @NotBlank(message = "JWT secret key must not be blank")
    @Size(min = 64, message = "JWT secret key must be at least 64 characters (512 bits) for HS512 signing")
    private String secret;

    /**
     * Issuer claim value embedded in tokens (iss).
     */
    @NotBlank(message = "JWT issuer must not be blank")
    private String issuer = "devflow-backend";

    /**
     * Access token lifetime in milliseconds. Default: 15 minutes (900,000 ms).
     */
    @Min(value = 1000, message = "Access token expiration must be at least 1000 ms")
    private long accessTokenExpiration = 900000L;

    /**
     * Refresh token lifetime in milliseconds. Default: 7 days (604,800,000 ms).
     */
    @Min(value = 1000, message = "Refresh token expiration must be at least 1000 ms")
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
