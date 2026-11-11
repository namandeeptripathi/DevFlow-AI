package com.devflow.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;
import java.util.function.Function;

/**
 * Service component responsible for JWT token generation, parsing, claim extraction, and validation.
 *
 * <p>Uses HMAC SHA-512 (HS512) asymmetric signing per Authentication Strategy §5.2 with
 * secret key loaded from {@link JwtProperties}.
 *
 * <h2>Core Responsibilities</h2>
 * <ul>
 *   <li>{@link #generateAccessToken(UserDetails)} — generates 15-minute access token</li>
 *   <li>{@link #generateRefreshToken(UserDetails)} — generates 7-day refresh token</li>
 *   <li>{@link #validateToken(String)} — validates signature, expiry, and structure safely</li>
 *   <li>{@link #extractUsername(String)} — extracts subject/username claim</li>
 *   <li>{@link #extractExpiration(String)} — extracts expiration timestamp</li>
 *   <li>{@link #extractClaims(String)} — extracts full JWT claims payload</li>
 * </ul>
 *
 * @see JwtProperties
 * @see JwtClaimsFactory
 * @see <a href="../../../../../docs/security/AUTHENTICATION_STRATEGY.md">Authentication Strategy §5</a>
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    public static final String CLAIM_TOKEN_TYPE = "token_type";
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    private final JwtProperties jwtProperties;
    private final JwtClaimsFactory jwtClaimsFactory;
    private final SecretKey secretKey;

    public JwtTokenProvider(JwtProperties jwtProperties, JwtClaimsFactory jwtClaimsFactory) {
        this.jwtProperties = Objects.requireNonNull(jwtProperties, "jwtProperties must not be null");
        this.jwtClaimsFactory = Objects.requireNonNull(jwtClaimsFactory, "jwtClaimsFactory must not be null");
        this.secretKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generates a short-lived access JWT token for the specified user without tenant context.
     *
     * @param userDetails the authenticated principal
     * @return signed access token string
     */
    public String generateAccessToken(UserDetails userDetails) {
        return generateAccessToken(userDetails, null);
    }

    /**
     * Generates a short-lived access JWT token for the specified user with an optional active organization context hint.
     *
     * @param userDetails the authenticated principal
     * @param organizationId optional active organization UUID context hint
     * @return signed access token string
     */
    public String generateAccessToken(UserDetails userDetails, java.util.UUID organizationId) {
        return buildToken(userDetails, organizationId, jwtProperties.getAccessTokenExpiration(), TOKEN_TYPE_ACCESS);
    }

    /**
     * Generates a long-lived refresh JWT token for the specified user.
     *
     * @param userDetails the authenticated principal
     * @return signed refresh token string
     */
    public String generateRefreshToken(UserDetails userDetails) {
        return buildToken(userDetails, null, jwtProperties.getRefreshTokenExpiration(), TOKEN_TYPE_REFRESH);
    }

    private String buildToken(UserDetails userDetails, java.util.UUID organizationId, long expirationMs, String tokenType) {
        Objects.requireNonNull(userDetails, "userDetails must not be null");

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .claims(jwtClaimsFactory.createClaims(userDetails, organizationId))
                .claim(CLAIM_TOKEN_TYPE, tokenType)
                .subject(userDetails.getUsername())
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(secretKey, Jwts.SIG.HS512)
                .compact();
    }

    /**
     * Validates signature, structural integrity, issuer, and expiration of a JWT token.
     *
     * @param token the JWT string to validate
     * @return {@code true} if token is valid and unexpired; {@code false} otherwise
     */
    public boolean validateToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }

        try {
            extractClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("JWT token has expired: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.debug("Malformed JWT token: {}", e.getMessage());
        } catch (SignatureException e) {
            log.debug("Invalid JWT signature: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.debug("Unsupported JWT token: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.debug("JWT claims string is empty: {}", e.getMessage());
        } catch (JwtException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Validates that the token is structurally valid, properly signed, unexpired, and not a refresh token.
     */
    public boolean validateAccessToken(String token) {
        return validateToken(token) && !isRefreshToken(token);
    }

    /**
     * Returns {@code true} if the token carries {@code token_type = "refresh"}.
     */
    public boolean isRefreshToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        try {
            Claims claims = extractClaims(token);
            return TOKEN_TYPE_REFRESH.equals(claims.get(CLAIM_TOKEN_TYPE));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Extracts the subject (username/email) from the specified token.
     *
     * @param token the JWT token string
     * @return subject username string
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Extracts the expiration date from the specified token.
     *
     * @param token the JWT token string
     * @return expiration timestamp as {@link Date}
     */
    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    /**
     * Extracts the optional user UUID claim from the specified token.
     *
     * @param token the JWT token string
     * @return optional containing the parsed user UUID, or empty if absent or malformed
     */
    public java.util.Optional<java.util.UUID> extractUserId(String token) {
        return extractClaim(token, claims -> {
            Object val = claims.get(JwtClaimsFactory.CLAIM_USER_ID);
            if (val instanceof String s && !s.isBlank()) {
                try {
                    return java.util.Optional.of(java.util.UUID.fromString(s));
                } catch (IllegalArgumentException e) {
                    return java.util.Optional.empty();
                }
            }
            return java.util.Optional.empty();
        });
    }

    /**
     * Extracts the optional active organization UUID context hint from the specified token.
     *
     * @param token the JWT token string
     * @return optional containing the parsed organization UUID, or empty if absent or malformed
     */
    public java.util.Optional<java.util.UUID> extractOrganizationId(String token) {
        return extractClaim(token, claims -> {
            Object val = claims.get(JwtClaimsFactory.CLAIM_ORG_ID);
            if (val instanceof String s && !s.isBlank()) {
                try {
                    return java.util.Optional.of(java.util.UUID.fromString(s));
                } catch (IllegalArgumentException e) {
                    return java.util.Optional.empty();
                }
            }
            return java.util.Optional.empty();
        });
    }

    /**
     * Extracts a specific claim from the token using a claim resolver function.
     *
     * @param token the JWT token string
     * @param claimsResolver function to resolve target claim value
     * @param <T> claim return type
     * @return extracted claim value
     */
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        Claims claims = extractClaims(token);
        return claimsResolver.apply(claims);
    }

    /**
     * Parses and returns the full {@link Claims} payload from a signed JWT.
     *
     * @param token the JWT token string
     * @return parsed {@link Claims}
     * @throws JwtException if the token cannot be parsed or verified
     */
    public Claims extractClaims(String token) {
        var parserBuilder = Jwts.parser().verifyWith(secretKey);
        if (jwtProperties.getIssuer() != null && !jwtProperties.getIssuer().isBlank()) {
            parserBuilder.requireIssuer(jwtProperties.getIssuer());
        }
        return parserBuilder
                .build()
                .parseSignedClaims(token.trim())
                .getPayload();
    }
}
