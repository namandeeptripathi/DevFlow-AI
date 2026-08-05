package com.devflow.security.jwt;

import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JwtTokenProvider")
class JwtTokenProviderTest {

    private static final String TEST_SECRET =
            "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970337336763979244226452948404D635166546A576E5A7234753778214125442A";

    private JwtTokenProvider jwtTokenProvider;
    private JwtProperties jwtProperties;
    private UserDetails userDetails;
    private UUID userId;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret(TEST_SECRET);
        jwtProperties.setIssuer("devflow-test");
        jwtProperties.setAccessTokenExpiration(60000L); // 60 seconds
        jwtProperties.setRefreshTokenExpiration(604800000L); // 7 days

        JwtClaimsFactory claimsFactory = new JwtClaimsFactory();
        jwtTokenProvider = new JwtTokenProvider(jwtProperties, claimsFactory);

        userId = UUID.randomUUID();
        User user = User.builder()
                .id(userId)
                .email("test.engineer@devflow.com")
                .username("testengineer")
                .passwordHash("hashedpassword")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();

        userDetails = new DevFlowUserDetails(user);
    }

    @Test
    @DisplayName("generateAccessToken produces valid, non-null JWT string")
    void generateAccessToken_producesValidToken() {
        String token = jwtTokenProvider.generateAccessToken(userDetails);

        assertThat(token).isNotBlank();
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        assertThat(jwtTokenProvider.extractUsername(token)).isEqualTo("testengineer");
    }

    @Test
    @DisplayName("generateRefreshToken produces valid token with longer lifespan")
    void generateRefreshToken_producesValidToken() {
        String token = jwtTokenProvider.generateRefreshToken(userDetails);

        assertThat(token).isNotBlank();
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        assertThat(jwtTokenProvider.extractUsername(token)).isEqualTo("testengineer");
    }

    @Test
    @DisplayName("extractClaims includes userId, username, email, and issuer")
    void extractClaims_includesExpectedCustomClaims() {
        String token = jwtTokenProvider.generateAccessToken(userDetails);

        Claims claims = jwtTokenProvider.extractClaims(token);

        assertThat(claims.getSubject()).isEqualTo("testengineer");
        assertThat(claims.getIssuer()).isEqualTo("devflow-test");
        assertThat(claims.get("userId")).isEqualTo(userId.toString());
        assertThat(claims.get("email")).isEqualTo("test.engineer@devflow.com");
        assertThat(claims.get("username")).isEqualTo("testengineer");
    }

    @Test
    @DisplayName("extractExpiration returns valid future date")
    void extractExpiration_returnsFutureDate() {
        String token = jwtTokenProvider.generateAccessToken(userDetails);

        Date expiration = jwtTokenProvider.extractExpiration(token);

        assertThat(expiration).isAfter(new Date());
    }

    @Test
    @DisplayName("validateToken returns false for expired token")
    void validateToken_returnsFalse_forExpiredToken() {
        // Create a provider with 1 ms expiration
        JwtProperties expiredProps = new JwtProperties();
        expiredProps.setSecret(TEST_SECRET);
        expiredProps.setIssuer("devflow-test");
        expiredProps.setAccessTokenExpiration(-1000L); // Already expired 1 sec ago

        JwtTokenProvider expiredProvider = new JwtTokenProvider(expiredProps, new JwtClaimsFactory());
        String expiredToken = expiredProvider.generateAccessToken(userDetails);

        assertThat(jwtTokenProvider.validateToken(expiredToken)).isFalse();
    }

    @Test
    @DisplayName("validateToken returns false for malformed token string")
    void validateToken_returnsFalse_forMalformedToken() {
        assertThat(jwtTokenProvider.validateToken("not.a.valid.jwt.token")).isFalse();
        assertThat(jwtTokenProvider.validateToken("invalidTokenHeader")).isFalse();
    }

    @Test
    @DisplayName("validateToken returns false for null or empty input")
    void validateToken_returnsFalse_forNullOrEmptyInput() {
        assertThat(jwtTokenProvider.validateToken(null)).isFalse();
        assertThat(jwtTokenProvider.validateToken("")).isFalse();
        assertThat(jwtTokenProvider.validateToken("   ")).isFalse();
    }

    @Test
    @DisplayName("validateToken returns false when signed with different secret key")
    void validateToken_returnsFalse_forDifferentSecretKey() {
        String differentSecret =
                "804E635266556A586E3272357538782F413F4428472B4B6250645367566B5970337336763979244226452948404D635166546A576E5A7234753778214125442A";
        JwtProperties diffProps = new JwtProperties();
        diffProps.setSecret(differentSecret);
        diffProps.setIssuer("devflow-test");
        diffProps.setAccessTokenExpiration(60000L);

        JwtTokenProvider diffProvider = new JwtTokenProvider(diffProps, new JwtClaimsFactory());
        String tokenFromDiffSecret = diffProvider.generateAccessToken(userDetails);

        assertThat(jwtTokenProvider.validateToken(tokenFromDiffSecret)).isFalse();
    }
}
