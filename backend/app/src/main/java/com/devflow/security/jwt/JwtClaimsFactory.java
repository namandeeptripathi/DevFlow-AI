package com.devflow.security.jwt;

import com.devflow.security.user.DevFlowUserDetails;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Factory responsible exclusively for constructing JWT custom claim maps (Stage 6 Optimized).
 *
 * <h2>Claim Structure & Security Invariants</h2>
 * <ul>
 *   <li>{@code userId}: Domain user UUID string (Subject Identity).</li>
 *   <li>{@code username}: User handle / login name (Subject Identity).</li>
 *   <li>{@code email}: Verified primary email address (Subject Identity).</li>
 *   <li>{@code orgId}: Optional active tenant organization UUID string (Context Hint).</li>
 * </ul>
 *
 * <h2>Authorization Invariants (Stage 6)</h2>
 * <ul>
 *   <li><strong>Permissions Omitted:</strong> All 48 canonical permissions are strictly omitted
 *       from JWTs to prevent payload bloat and header overhead.</li>
 *   <li><strong>Non-Authoritative:</strong> Claims are informational only. The centralized authorization
 *       engine ({@code AuthApi}) and database-backed resolvers remain the authoritative source of truth.</li>
 *   <li><strong>Instant Invalidation:</strong> Revoked or expired role assignments take effect immediately
 *       regardless of whether a client holds an older, unexpired token.</li>
 *   <li><strong>Zero Privilege Escalation:</strong> Organization switching or claim tampering cannot
 *       confer privileges because authorization evaluates against persistent database/cached state.</li>
 * </ul>
 *
 * @see JwtTokenProvider
 * @see <a href="../../../../../docs/security/AUTHENTICATION_STRATEGY.md">Authentication Strategy §5.3</a>
 */
@Component
public class JwtClaimsFactory {

    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_ORG_ID = "orgId";

    /**
     * Builds custom claims payload map for the given {@link UserDetails} without tenant context.
     *
     * @param userDetails the authenticated principal details
     * @return a map of custom claim key-value pairs
     */
    public Map<String, Object> createClaims(UserDetails userDetails) {
        return createClaims(userDetails, null);
    }

    /**
     * Builds custom claims payload map for the given {@link UserDetails} with an optional active organization context hint.
     *
     * @param userDetails the authenticated principal details
     * @param currentOrgId optional active organization UUID context hint
     * @return a map of custom claim key-value pairs
     */
    public Map<String, Object> createClaims(UserDetails userDetails, UUID currentOrgId) {
        Objects.requireNonNull(userDetails, "userDetails must not be null");

        Map<String, Object> claims = new HashMap<>();

        if (userDetails instanceof DevFlowUserDetails devFlowUser) {
            if (devFlowUser.getId() != null) {
                claims.put(CLAIM_USER_ID, devFlowUser.getId().toString());
            }
            if (devFlowUser.getEmail() != null) {
                claims.put(CLAIM_EMAIL, devFlowUser.getEmail());
            }
            claims.put(CLAIM_USERNAME, devFlowUser.getUsername());
        } else {
            claims.put(CLAIM_USERNAME, userDetails.getUsername());
        }

        if (currentOrgId != null) {
            claims.put(CLAIM_ORG_ID, currentOrgId.toString());
        }

        return claims;
    }
}
