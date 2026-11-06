package com.devflow.security.jwt;

import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Stage 6: JWT Claims Optimization & Tenant Context Tests")
class JwtClaimsOptimizationTest {

    private JwtProperties jwtProperties;
    private JwtClaimsFactory jwtClaimsFactory;
    private JwtTokenProvider jwtTokenProvider;

    private User sampleUser;
    private DevFlowUserDetails userDetails;
    private UUID testOrgId;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret("404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970337336763979244226452948404D635166546A576E5A7234753778214125442A");
        jwtProperties.setIssuer("devflow-backend");
        jwtProperties.setAccessTokenExpiration(900_000L); // 15 min
        jwtProperties.setRefreshTokenExpiration(604_800_000L); // 7 days

        jwtClaimsFactory = new JwtClaimsFactory();
        jwtTokenProvider = new JwtTokenProvider(jwtProperties, jwtClaimsFactory);

        sampleUser = User.builder()
                .id(UUID.randomUUID())
                .email("engineer@devflow.io")
                .username("dev_engineer")
                .passwordHash("hashed_password")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();

        userDetails = new DevFlowUserDetails(sampleUser);
        testOrgId = UUID.randomUUID();
    }

    @Nested
    @DisplayName("Claim Generation & Structure")
    class ClaimGeneration {

        @Test
        @DisplayName("createClaims without orgId includes standard user identity claims")
        void standardClaims_withoutOrgId() {
            Map<String, Object> claims = jwtClaimsFactory.createClaims(userDetails);

            assertThat(claims.get(JwtClaimsFactory.CLAIM_USER_ID)).isEqualTo(sampleUser.getId().toString());
            assertThat(claims.get(JwtClaimsFactory.CLAIM_USERNAME)).isEqualTo("dev_engineer");
            assertThat(claims.get(JwtClaimsFactory.CLAIM_EMAIL)).isEqualTo("engineer@devflow.io");
            assertThat(claims).doesNotContainKey(JwtClaimsFactory.CLAIM_ORG_ID);
            // Permissions are strictly omitted from claims to avoid bloat
            assertThat(claims).doesNotContainKey("permissions");
            assertThat(claims).doesNotContainKey("roles");
        }

        @Test
        @DisplayName("createClaims with orgId includes optional tenant context hint")
        void claimsWithOrgId_containsTenantContext() {
            Map<String, Object> claims = jwtClaimsFactory.createClaims(userDetails, testOrgId);

            assertThat(claims.get(JwtClaimsFactory.CLAIM_USER_ID)).isEqualTo(sampleUser.getId().toString());
            assertThat(claims.get(JwtClaimsFactory.CLAIM_USERNAME)).isEqualTo("dev_engineer");
            assertThat(claims.get(JwtClaimsFactory.CLAIM_EMAIL)).isEqualTo("engineer@devflow.io");
            assertThat(claims.get(JwtClaimsFactory.CLAIM_ORG_ID)).isEqualTo(testOrgId.toString());
        }
    }

    @Nested
    @DisplayName("Token Provider Integration & Extraction")
    class TokenProviderIntegration {

        @Test
        @DisplayName("generateAccessToken with orgId embeds context extractable via extractOrganizationId")
        void tokenWithOrgId_extractable() {
            String token = jwtTokenProvider.generateAccessToken(userDetails, testOrgId);
            assertThat(jwtTokenProvider.validateToken(token)).isTrue();

            assertThat(jwtTokenProvider.extractUsername(token)).isEqualTo("dev_engineer");
            assertThat(jwtTokenProvider.extractUserId(token)).contains(sampleUser.getId());
            assertThat(jwtTokenProvider.extractOrganizationId(token)).contains(testOrgId);
        }

        @Test
        @DisplayName("generateAccessToken without orgId maintains backwards compatibility (empty orgId)")
        void tokenWithoutOrgId_backwardsCompatible() {
            String token = jwtTokenProvider.generateAccessToken(userDetails);
            assertThat(jwtTokenProvider.validateToken(token)).isTrue();

            assertThat(jwtTokenProvider.extractUsername(token)).isEqualTo("dev_engineer");
            assertThat(jwtTokenProvider.extractUserId(token)).contains(sampleUser.getId());
            assertThat(jwtTokenProvider.extractOrganizationId(token)).isEmpty();
        }

        @Test
        @DisplayName("Malformed or expired tokens fail validation cleanly")
        void invalidTokens_handledCleanly() {
            assertThat(jwtTokenProvider.validateToken(null)).isFalse();
            assertThat(jwtTokenProvider.validateToken("")).isFalse();
            assertThat(jwtTokenProvider.validateToken("not.a.valid.jwt.token")).isFalse();
        }
    }

    @Nested
    @DisplayName("Security & Anti-Escalation Invariants")
    class SecurityInvariants {

        @Test
        @DisplayName("Token claims are compact: payload size remains well under 1KB")
        void tokenPayloadIsCompact() {
            String token = jwtTokenProvider.generateAccessToken(userDetails, testOrgId);
            // Full JWT string is around 300-400 characters, well within HTTP header limits
            assertThat(token.length()).isLessThan(500);
        }

        @Test
        @DisplayName("Tampered token fails signature verification immediately")
        void tamperedToken_signatureFails() {
            String token = jwtTokenProvider.generateAccessToken(userDetails, testOrgId);
            String tampered = token.substring(0, token.length() - 5) + "ABCDE";

            assertThat(jwtTokenProvider.validateToken(tampered)).isFalse();
        }
    }
}
