package com.devflow.role.adapter;

import com.devflow.modules.auth.evaluator.MembershipValidator;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.security.authorization.cache.AuthorizationCacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Database-backed persistence adapter implementing {@link MembershipValidator} from {@code devflow-auth}
 * with Stage 6 authorization caching.
 *
 * <p>Authoritatively validates active organization membership against the database and authorization cache,
 * rejecting suspended, invited, or missing memberships.
 */
@Component
public class DatabaseMembershipValidator implements MembershipValidator {

    private final OrganizationMemberRepository organizationMemberRepository;
    private final AuthorizationCacheService cacheService;

    @Autowired
    public DatabaseMembershipValidator(
            OrganizationMemberRepository organizationMemberRepository,
            @Autowired(required = false) AuthorizationCacheService cacheService
    ) {
        this.organizationMemberRepository = Objects.requireNonNull(
                organizationMemberRepository, "organizationMemberRepository must not be null");
        this.cacheService = cacheService;
    }

    public DatabaseMembershipValidator(OrganizationMemberRepository organizationMemberRepository) {
        this(organizationMemberRepository, null);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveMember(UUID userId, UUID organizationId) {
        if (userId == null || organizationId == null) {
            return false;
        }

        // 1. Check authorization cache if available
        if (cacheService != null) {
            Optional<Boolean> cached = cacheService.isMemberActive(organizationId, userId);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        // 2. Authoritative database evaluation on cache miss
        boolean isActive = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, userId)
                .map(member -> member.getStatus() == OrganizationMembershipStatus.ACTIVE)
                .orElse(false);

        // 3. Populate cache
        if (cacheService != null) {
            cacheService.putMemberActive(organizationId, userId, isActive);
        }

        return isActive;
    }
}
