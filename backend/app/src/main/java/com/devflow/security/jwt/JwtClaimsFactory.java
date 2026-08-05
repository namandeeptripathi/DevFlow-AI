package com.devflow.security.jwt;

import com.devflow.security.user.DevFlowUserDetails;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Factory responsible exclusively for constructing JWT custom claim maps.
 *
 * <p>Encapsulates claim structure definitions per Authentication Strategy §5.3:
 * <ul>
 *   <li>{@code userId}: Domain user UUID string</li>
 *   <li>{@code username}: User handle / login name</li>
 *   <li>{@code email}: Verified primary email address</li>
 * </ul>
 *
 * <p><strong>Note:</strong> Roles and permissions claims are omitted at this phase
 * and will be integrated during subsequent RBAC authorization implementation.
 *
 * @see JwtTokenProvider
 * @see <a href="../../../../../docs/security/AUTHENTICATION_STRATEGY.md">Authentication Strategy §5.3</a>
 */
@Component
public class JwtClaimsFactory {

    /**
     * Builds custom claims payload map for the given {@link UserDetails}.
     *
     * @param userDetails the authenticated principal details
     * @return a map of custom claim key-value pairs
     */
    public Map<String, Object> createClaims(UserDetails userDetails) {
        Objects.requireNonNull(userDetails, "userDetails must not be null");

        Map<String, Object> claims = new HashMap<>();

        if (userDetails instanceof DevFlowUserDetails devFlowUser) {
            if (devFlowUser.getId() != null) {
                claims.put("userId", devFlowUser.getId().toString());
            }
            if (devFlowUser.getEmail() != null) {
                claims.put("email", devFlowUser.getEmail());
            }
            claims.put("username", devFlowUser.getUsername());
        } else {
            claims.put("username", userDetails.getUsername());
        }

        return claims;
    }
}
