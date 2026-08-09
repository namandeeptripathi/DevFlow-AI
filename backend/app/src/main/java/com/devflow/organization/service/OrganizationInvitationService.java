package com.devflow.organization.service;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationInvitation;
import com.devflow.organization.domain.OrganizationInvitationStatus;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.exception.OrganizationAccessDeniedException;
import com.devflow.organization.exception.OrganizationInvitationAlreadyExistsException;
import com.devflow.organization.exception.OrganizationInvitationAlreadyProcessedException;
import com.devflow.organization.exception.OrganizationInvitationExpiredException;
import com.devflow.organization.exception.OrganizationInvitationNotFoundException;
import com.devflow.organization.exception.OrganizationMemberAlreadyExistsException;
import com.devflow.organization.exception.OrganizationNotFoundException;
import com.devflow.organization.repository.OrganizationInvitationRepository;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain service managing organization membership onboarding invitations.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Invite new members to an organization with a specific {@link OrganizationRole} (admin/owner-gated).</li>
 *   <li>Accept pending invitations by token, creating an active membership atomically.</li>
 *   <li>Revoke pending invitations (admin/owner-gated).</li>
 *   <li>List pending invitations for an organization (admin/owner-gated).</li>
 *   <li>Expire past-due pending invitations in batch.</li>
 * </ul>
 *
 * <h2>Multi-Tenant Security Invariants</h2>
 * <ul>
 *   <li>Authorization checks are strictly enforced via {@link OrganizationAuthorizationService}.</li>
 *   <li>Recipient email verification: Authenticated user email must match invitation email upon acceptance.</li>
 *   <li>Status transitions are strictly validated (PENDING → ACCEPTED / EXPIRED / REVOKED).</li>
 *   <li>Constructor injection only — no field injection.</li>
 * </ul>
 *
 * @see OrganizationInvitation
 * @see OrganizationInvitationStatus
 * @see OrganizationInvitationRepository
 * @see OrganizationAuthorizationService
 */
@Service
public class OrganizationInvitationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationInvitationService.class);
    private static final Duration DEFAULT_INVITATION_VALIDITY = Duration.ofDays(7);

    private final OrganizationInvitationRepository organizationInvitationRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final UserRepository userRepository;
    private final OrganizationAuthorizationService authorizationService;

    public OrganizationInvitationService(
            OrganizationInvitationRepository organizationInvitationRepository,
            OrganizationRepository organizationRepository,
            OrganizationMemberRepository organizationMemberRepository,
            UserRepository userRepository,
            OrganizationAuthorizationService authorizationService
    ) {
        this.organizationInvitationRepository = Objects.requireNonNull(
                organizationInvitationRepository, "organizationInvitationRepository must not be null");
        this.organizationRepository = Objects.requireNonNull(
                organizationRepository, "organizationRepository must not be null");
        this.organizationMemberRepository = Objects.requireNonNull(
                organizationMemberRepository, "organizationMemberRepository must not be null");
        this.userRepository = Objects.requireNonNull(
                userRepository, "userRepository must not be null");
        this.authorizationService = Objects.requireNonNull(
                authorizationService, "authorizationService must not be null");
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    /**
     * Creates a new onboarding invitation for a target email address.
     *
     * <p>Requires the caller to hold {@link OrganizationRole#ADMIN} or {@link OrganizationRole#OWNER} role.
     *
     * @param organizationId the UUID of the organization
     * @param email the target recipient's email address
     * @param role the initial role to grant upon acceptance
     * @param callerUserId the UUID of the authenticated inviter
     * @return the persisted {@link OrganizationInvitation} entity
     * @throws OrganizationNotFoundException if the organization or caller user does not exist
     * @throws OrganizationMemberAlreadyExistsException if a user with that email is already an active member
     * @throws OrganizationInvitationAlreadyExistsException if a pending invitation already exists for the email in this organization
     * @throws OrganizationAccessDeniedException if the caller lacks admin/owner permissions
     */
    @Transactional
    public OrganizationInvitation inviteMember(
            UUID organizationId, String email, OrganizationRole role, UUID callerUserId
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        authorizationService.requireAdminOrOwner(organizationId, callerUserId);

        String normalizedEmail = email.trim().toLowerCase();

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> {
                    log.warn("Invite member failed: organization [{}] not found", organizationId);
                    return new OrganizationNotFoundException("Organization not found: " + organizationId);
                });

        User inviter = userRepository.findById(callerUserId)
                .orElseThrow(() -> {
                    log.warn("Invite member failed: inviter user [{}] not found", callerUserId);
                    return new OrganizationNotFoundException("User not found: " + callerUserId);
                });

        userRepository.findByEmail(normalizedEmail).ifPresent(existingUser -> {
            if (organizationMemberRepository.existsByOrganizationIdAndUserIdAndStatus(
                    organizationId, existingUser.getId(), OrganizationMembershipStatus.ACTIVE)) {
                log.warn("Invite member rejected: user [{}] ({}) is already an active member of organization [{}]",
                        existingUser.getId(), normalizedEmail, organizationId);
                throw new OrganizationMemberAlreadyExistsException(
                        "User with email [" + normalizedEmail + "] is already a member of organization [" + organizationId + "]");
            }
        });

        if (organizationInvitationRepository.existsByOrganizationIdAndEmailAndStatus(
                organizationId, normalizedEmail, OrganizationInvitationStatus.PENDING)) {
            log.warn("Invite member rejected: pending invitation already exists for email [{}] in organization [{}]",
                    normalizedEmail, organizationId);
            throw new OrganizationInvitationAlreadyExistsException(
                    "Pending invitation already exists for email [" + normalizedEmail + "] in organization [" + organizationId + "]");
        }

        OrganizationInvitation invitation = OrganizationInvitation.builder()
                .organization(organization)
                .email(normalizedEmail)
                .role(role)
                .token(UUID.randomUUID().toString())
                .invitedBy(inviter)
                .status(OrganizationInvitationStatus.PENDING)
                .expiresAt(Instant.now().plus(DEFAULT_INVITATION_VALIDITY))
                .build();

        OrganizationInvitation saved = organizationInvitationRepository.save(invitation);
        log.info("Created invitation [{}] for email [{}] with role [{}] in organization [{}] by inviter [{}]",
                saved.getId(), normalizedEmail, role, organizationId, callerUserId);
        return saved;
    }

    /**
     * Accepts a pending invitation by token, creating an active organization membership.
     *
     * <p>Enforces recipient email match: the authenticated user's email must match the invitation's target email.
     *
     * @param token the secure validation token
     * @param authenticatedUserId the UUID of the authenticated user accepting the invitation
     * @return the newly created {@link OrganizationMember} entity
     * @throws OrganizationInvitationNotFoundException if no invitation exists for the token
     * @throws OrganizationInvitationExpiredException if the invitation has expired
     * @throws OrganizationInvitationAlreadyProcessedException if the invitation has already been accepted or revoked
     * @throws OrganizationAccessDeniedException if the authenticated user's email does not match the invitation email
     */
    @Transactional
    public OrganizationMember acceptInvitation(String token, UUID authenticatedUserId) {
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(authenticatedUserId, "authenticatedUserId must not be null");

        OrganizationInvitation invitation = organizationInvitationRepository.findByToken(token.trim())
                .orElseThrow(() -> {
                    log.warn("Accept invitation failed: invalid token provided");
                    return new OrganizationInvitationNotFoundException("Invalid or non-existent invitation token");
                });

        if (invitation.getStatus() == OrganizationInvitationStatus.EXPIRED) {
            log.warn("Accept invitation failed: invitation [{}] has expired", invitation.getId());
            throw new OrganizationInvitationExpiredException("Invitation has expired");
        }

        if (invitation.getStatus() == OrganizationInvitationStatus.REVOKED
                || invitation.getStatus() == OrganizationInvitationStatus.ACCEPTED) {
            log.warn("Accept invitation failed: invitation [{}] already in status [{}]",
                    invitation.getId(), invitation.getStatus());
            throw new OrganizationInvitationAlreadyProcessedException(
                    "Invitation has already been " + invitation.getStatus().name().toLowerCase());
        }

        if (invitation.getExpiresAt().isBefore(Instant.now())) {
            invitation.setStatus(OrganizationInvitationStatus.EXPIRED);
            organizationInvitationRepository.save(invitation);
            log.warn("Accept invitation failed: invitation [{}] passed expiration timestamp", invitation.getId());
            throw new OrganizationInvitationExpiredException("Invitation has expired");
        }

        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(() -> new OrganizationNotFoundException("Authenticated user not found: " + authenticatedUserId));

        if (!invitation.getEmail().equalsIgnoreCase(user.getEmail())) {
            log.warn("Accept invitation rejected: authenticated user email [{}] does not match invitation recipient email [{}]",
                    user.getEmail(), invitation.getEmail());
            throw new OrganizationAccessDeniedException(
                    "Authenticated user email does not match invitation recipient email");
        }

        UUID orgId = invitation.getOrganization().getId();

        if (organizationMemberRepository.existsByOrganizationIdAndUserIdAndStatus(
                orgId, authenticatedUserId, OrganizationMembershipStatus.ACTIVE)) {
            invitation.setStatus(OrganizationInvitationStatus.ACCEPTED);
            invitation.setAcceptedAt(Instant.now());
            organizationInvitationRepository.save(invitation);
            log.warn("Accept invitation: user [{}] is already an active member of organization [{}]",
                    authenticatedUserId, orgId);
            throw new OrganizationMemberAlreadyExistsException(
                    "User is already an active member of organization [" + orgId + "]");
        }

        OrganizationMember member = OrganizationMember.builder()
                .organization(invitation.getOrganization())
                .user(user)
                .role(invitation.getRole())
                .status(OrganizationMembershipStatus.ACTIVE)
                .joinedAt(Instant.now())
                .build();

        OrganizationMember savedMember = organizationMemberRepository.save(member);

        invitation.setStatus(OrganizationInvitationStatus.ACCEPTED);
        invitation.setAcceptedAt(Instant.now());
        organizationInvitationRepository.save(invitation);

        log.info("User [{}] successfully accepted invitation [{}] for organization [{}] with role [{}]",
                authenticatedUserId, invitation.getId(), orgId, invitation.getRole());
        return savedMember;
    }

    /**
     * Revokes a pending invitation.
     *
     * <p>Requires the caller to hold {@link OrganizationRole#ADMIN} or {@link OrganizationRole#OWNER} role in the target organization.
     *
     * @param invitationId the UUID of the invitation to revoke
     * @param callerUserId the UUID of the authenticated caller
     * @return the updated {@link OrganizationInvitation} entity
     * @throws OrganizationInvitationNotFoundException if the invitation does not exist
     * @throws OrganizationInvitationAlreadyProcessedException if the invitation is not in PENDING status
     * @throws OrganizationAccessDeniedException if the caller lacks admin/owner permissions
     */
    @Transactional
    public OrganizationInvitation revokeInvitation(UUID invitationId, UUID callerUserId) {
        Objects.requireNonNull(invitationId, "invitationId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        OrganizationInvitation invitation = organizationInvitationRepository.findById(invitationId)
                .orElseThrow(() -> {
                    log.warn("Revoke invitation failed: invitation [{}] not found", invitationId);
                    return new OrganizationInvitationNotFoundException("Invitation not found: " + invitationId);
                });

        authorizationService.requireAdminOrOwner(invitation.getOrganization().getId(), callerUserId);

        if (invitation.getStatus() != OrganizationInvitationStatus.PENDING) {
            log.warn("Revoke invitation failed: invitation [{}] is in status [{}]", invitationId, invitation.getStatus());
            throw new OrganizationInvitationAlreadyProcessedException(
                    "Cannot revoke invitation in status: " + invitation.getStatus());
        }

        invitation.setStatus(OrganizationInvitationStatus.REVOKED);
        OrganizationInvitation updated = organizationInvitationRepository.save(invitation);
        log.info("Revoked invitation [{}] in organization [{}] by user [{}]",
                invitationId, invitation.getOrganization().getId(), callerUserId);
        return updated;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Retrieves all pending invitations for an organization boundary.
     *
     * <p>Requires the caller to hold {@link OrganizationRole#ADMIN} or {@link OrganizationRole#OWNER} role.
     *
     * @param organizationId the UUID of the organization
     * @param callerUserId the UUID of the authenticated caller
     * @return a list of pending {@link OrganizationInvitation} entities
     * @throws OrganizationNotFoundException if the organization does not exist
     * @throws OrganizationAccessDeniedException if the caller lacks admin/owner permissions
     */
    @Transactional(readOnly = true)
    public List<OrganizationInvitation> listPendingInvitations(UUID organizationId, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        authorizationService.requireAdminOrOwner(organizationId, callerUserId);

        if (!organizationRepository.existsById(organizationId)) {
            log.warn("List pending invitations failed: organization [{}] not found", organizationId);
            throw new OrganizationNotFoundException("Organization not found: " + organizationId);
        }

        log.debug("Listing pending invitations for organization [{}] by caller [{}]", organizationId, callerUserId);
        return organizationInvitationRepository.findByOrganizationIdAndStatus(
                organizationId, OrganizationInvitationStatus.PENDING);
    }

    /**
     * Scheduled or system operation to transition past-due pending invitations to EXPIRED status.
     *
     * @return the number of invitation records marked as EXPIRED
     */
    @Transactional
    public int expirePendingInvitations() {
        Instant now = Instant.now();
        List<OrganizationInvitation> expiredList = organizationInvitationRepository
                .findByExpiresAtBeforeAndStatus(now, OrganizationInvitationStatus.PENDING);

        if (expiredList.isEmpty()) {
            return 0;
        }

        for (OrganizationInvitation invitation : expiredList) {
            invitation.setStatus(OrganizationInvitationStatus.EXPIRED);
        }

        organizationInvitationRepository.saveAll(expiredList);
        log.info("Expired [{}] past-due organization invitations", expiredList.size());
        return expiredList.size();
    }
}
