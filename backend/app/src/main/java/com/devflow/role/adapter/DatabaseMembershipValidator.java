package com.devflow.role.adapter;

import com.devflow.modules.auth.evaluator.MembershipValidator;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.repository.OrganizationMemberRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Database-backed persistence adapter implementing {@link MembershipValidator} from {@code devflow-auth}.
 *
 * <p>Authoritatively validates active organization membership against the database,
 * rejecting suspended, invited, or missing memberships.
 */
@Component
public class DatabaseMembershipValidator implements MembershipValidator {

    private final OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    public DatabaseMembershipValidator(OrganizationMemberRepository organizationMemberRepository) {
        this.organizationMemberRepository = Objects.requireNonNull(
                organizationMemberRepository, "organizationMemberRepository must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveMember(UUID userId, UUID organizationId) {
        if (userId == null || organizationId == null) {
            return false;
        }

        return organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, userId)
                .map(member -> member.getStatus() == OrganizationMembershipStatus.ACTIVE)
                .orElse(false);
    }
}
