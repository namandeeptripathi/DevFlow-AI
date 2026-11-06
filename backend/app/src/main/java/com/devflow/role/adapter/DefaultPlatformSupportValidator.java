package com.devflow.role.adapter;

import com.devflow.modules.auth.evaluator.PlatformSupportValidator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production implementation of {@link PlatformSupportValidator} from {@code devflow-auth}.
 *
 * <p>Enforces platform support case validation:
 * <ul>
 *   <li>Only verified, unexpired support cases grant cross-tenant access.</li>
 *   <li>Access is strictly pinned to the verified target tenant and assigned support engineer.</li>
 *   <li>Missing, expired, or mismatched support cases fail closed immediately.</li>
 *   <li>Caller-supplied request attributes are never trusted without independent verification.</li>
 * </ul>
 *
 * @see PlatformSupportValidator
 */
@Component
public class DefaultPlatformSupportValidator implements PlatformSupportValidator {

    private final Map<String, VerifiedSupportGrant> verifiedGrants = new ConcurrentHashMap<>();

    /**
     * Registers a verified, independently approved support case grant.
     *
     * @param userId the assigned platform support engineer UUID
     * @param targetOrganizationId the authorized tenant organization UUID
     * @param supportCaseId the ticket identifier
     * @param expiresAt authorization expiration timestamp
     */
    public void registerVerifiedSupportCase(
            UUID userId,
            UUID targetOrganizationId,
            String supportCaseId,
            Instant expiresAt
    ) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(targetOrganizationId, "targetOrganizationId must not be null");
        Objects.requireNonNull(supportCaseId, "supportCaseId must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");

        verifiedGrants.put(supportCaseId, new VerifiedSupportGrant(userId, targetOrganizationId, supportCaseId, expiresAt));
    }

    /**
     * Revokes a support case grant.
     *
     * @param supportCaseId ticket identifier
     */
    public void revokeSupportCase(String supportCaseId) {
        if (supportCaseId != null) {
            verifiedGrants.remove(supportCaseId);
        }
    }

    /**
     * Clears all verified support grants (useful for test isolation).
     */
    public void clearAll() {
        verifiedGrants.clear();
    }

    @Override
    public boolean isSupportAccessAuthorized(UUID userId, UUID targetOrganizationId, String supportCaseId) {
        if (userId == null || targetOrganizationId == null || supportCaseId == null || supportCaseId.isBlank()) {
            return false;
        }

        VerifiedSupportGrant grant = verifiedGrants.get(supportCaseId.trim());
        if (grant == null) {
            return false; // Fail closed for unverified support cases
        }

        return grant.isValid(userId, targetOrganizationId, Instant.now());
    }

    private record VerifiedSupportGrant(
            UUID userId,
            UUID targetOrganizationId,
            String supportCaseId,
            Instant expiresAt
    ) {
        boolean isValid(UUID requestedUserId, UUID requestedOrgId, Instant now) {
            return userId.equals(requestedUserId)
                    && targetOrganizationId.equals(requestedOrgId)
                    && (expiresAt == null || expiresAt.isAfter(now));
        }
    }
}
