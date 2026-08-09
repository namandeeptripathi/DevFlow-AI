package com.devflow.organization.service;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.exception.OrganizationAlreadyExistsException;
import com.devflow.organization.exception.OrganizationNotFoundException;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain service managing the lifecycle of DevFlow organizations (tenants).
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Create a new organization with slug-uniqueness enforcement and atomic owner membership.</li>
 *   <li>Retrieve organizations by ID or slug (membership-gated).</li>
 *   <li>Apply validated updates to mutable organization fields (admin/owner-gated).</li>
 *   <li>Delete an organization by ID (owner-gated).</li>
 * </ul>
 *
 * <h2>Architectural Boundaries</h2>
 * <ul>
 *   <li>Authorization is delegated to {@link OrganizationAuthorizationService}. This service
 *       does not inspect roles or memberships directly.</li>
 *   <li>Slug is treated as immutable after creation; update operations explicitly
 *       prevent slug modification to preserve URL stability.</li>
 *   <li>Owner validation delegates to {@link UserRepository} to confirm the user exists.</li>
 *   <li>Audit fields ({@code createdAt}, {@code updatedAt}) are managed automatically by
 *       {@link org.springframework.data.jpa.domain.support.AuditingEntityListener}.</li>
 *   <li>Constructor injection only — no field injection.</li>
 * </ul>
 *
 * @see Organization
 * @see OrganizationRepository
 * @see OrganizationMemberRepository
 * @see OrganizationAuthorizationService
 * @see OrganizationNotFoundException
 * @see OrganizationAlreadyExistsException
 */
@Service
public class OrganizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final UserRepository userRepository;
    private final OrganizationAuthorizationService authorizationService;

    public OrganizationService(
            OrganizationRepository organizationRepository,
            OrganizationMemberRepository organizationMemberRepository,
            UserRepository userRepository,
            OrganizationAuthorizationService authorizationService
    ) {
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
     * Creates a new organization owned by the authenticated user.
     * Automatically persists the caller as an {@link OrganizationRole#OWNER} member.
     *
     * <p>Business rules enforced:
     * <ul>
     *   <li>The slug must be globally unique across all organizations.</li>
     *   <li>The caller is the founding owner — no separate owner ID is accepted.</li>
     *   <li>The owner is atomically granted an active {@link OrganizationRole#OWNER} membership.</li>
     * </ul>
     *
     * @param name        the display name of the organization
     * @param slug        the URL-safe slug (must be unique)
     * @param description an optional human-readable description
     * @param callerUserId the UUID of the authenticated user (becomes the owner)
     * @return the persisted {@link Organization} entity
     * @throws OrganizationAlreadyExistsException if the slug is already in use
     * @throws OrganizationNotFoundException      if the caller user ID does not exist
     */
    @Transactional
    public Organization createOrganization(String name, String slug, String description, UUID callerUserId) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(slug, "slug must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        String normalizedSlug = slug.trim().toLowerCase();

        if (organizationRepository.existsBySlug(normalizedSlug)) {
            log.warn("Organization creation rejected: slug [{}] is already in use", normalizedSlug);
            throw new OrganizationAlreadyExistsException(
                    "Organization already exists with slug: " + normalizedSlug);
        }

        User owner = userRepository.findById(callerUserId)
                .orElseThrow(() -> {
                    log.warn("Organization creation rejected: owner [{}] not found", callerUserId);
                    return new OrganizationNotFoundException(
                            "Owner user not found: " + callerUserId);
                });

        Organization organization = Organization.builder()
                .name(name.trim())
                .slug(normalizedSlug)
                .description(normalise(description))
                .owner(owner)
                .build();

        Organization saved = organizationRepository.save(organization);

        OrganizationMember ownerMember = OrganizationMember.builder()
                .organization(saved)
                .user(owner)
                .role(OrganizationRole.OWNER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .joinedAt(Instant.now())
                .build();

        organizationMemberRepository.save(ownerMember);

        log.info("Created organization [{}] with slug [{}] and initial OWNER member [{}]",
                saved.getId(), saved.getSlug(), callerUserId);
        return saved;
    }

    /**
     * Updates the mutable fields of an existing organization.
     *
     * <p>Only {@code name} and {@code description} are eligible for update.
     * The {@code slug} is immutable after creation and is never modified by this method
     * to preserve URL stability and external reference integrity.
     *
     * <p>Requires the caller to hold {@link OrganizationRole#ADMIN} or
     * {@link OrganizationRole#OWNER} role.
     *
     * @param organizationId the UUID of the organization to update
     * @param name           the new display name (if {@code null}, left unchanged)
     * @param description    the new description (if {@code null}, left unchanged)
     * @param callerUserId   the UUID of the authenticated caller
     * @return the updated {@link Organization} entity
     * @throws OrganizationNotFoundException if no organization exists with the given ID
     * @throws com.devflow.organization.exception.OrganizationAccessDeniedException if the caller lacks admin/owner permissions
     */
    @Transactional
    public Organization updateOrganization(UUID organizationId, String name, String description, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        authorizationService.requireAdminOrOwner(organizationId, callerUserId);

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> {
                    log.warn("Organization update rejected: organization [{}] not found", organizationId);
                    return new OrganizationNotFoundException(
                            "Organization not found: " + organizationId);
                });

        if (name != null) {
            organization.setName(name.trim());
        }
        if (description != null) {
            organization.setDescription(normalise(description));
        }

        Organization saved = organizationRepository.save(organization);
        log.info("Updated organization [{}] by user [{}]", saved.getId(), callerUserId);
        return saved;
    }

    /**
     * Deletes an organization by its unique identifier.
     *
     * <p>Requires the caller to hold {@link OrganizationRole#OWNER} role.
     *
     * @param organizationId the UUID of the organization to delete
     * @param callerUserId   the UUID of the authenticated caller
     * @throws OrganizationNotFoundException if no organization exists with the given ID
     * @throws com.devflow.organization.exception.OrganizationAccessDeniedException if the caller is not an owner
     */
    @Transactional
    public void deleteOrganization(UUID organizationId, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        authorizationService.requireOwner(organizationId, callerUserId);

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> {
                    log.warn("Organization deletion rejected: organization [{}] not found", organizationId);
                    return new OrganizationNotFoundException(
                            "Organization not found: " + organizationId);
                });

        organizationRepository.delete(organization);
        log.info("Deleted organization [{}] with slug [{}] by user [{}]",
                organizationId, organization.getSlug(), callerUserId);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Retrieves an organization by its unique identifier.
     *
     * <p>Requires the caller to be an active member of the organization.
     *
     * @param organizationId the UUID of the organization
     * @param callerUserId   the UUID of the authenticated caller
     * @return the {@link Organization} entity
     * @throws OrganizationNotFoundException if no organization exists with the given ID
     * @throws com.devflow.organization.exception.OrganizationMembershipRequiredException if the caller is not a member
     */
    @Transactional(readOnly = true)
    public Organization getOrganizationById(UUID organizationId, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        authorizationService.requireMember(organizationId, callerUserId);

        log.debug("Fetching organization by ID [{}] for user [{}]", organizationId, callerUserId);

        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> {
                    log.warn("Organization not found for ID [{}]", organizationId);
                    return new OrganizationNotFoundException(
                            "Organization not found: " + organizationId);
                });
    }

    /**
     * Retrieves an organization by its unique, URL-safe slug.
     *
     * <p>Requires the caller to be an active member of the organization.
     *
     * @param slug         the slug to query (e.g., {@code "acme-corp"})
     * @param callerUserId the UUID of the authenticated caller
     * @return the {@link Organization} entity
     * @throws OrganizationNotFoundException if no organization exists with the given slug
     * @throws com.devflow.organization.exception.OrganizationMembershipRequiredException if the caller is not a member
     */
    @Transactional(readOnly = true)
    public Organization getOrganizationBySlug(String slug, UUID callerUserId) {
        Objects.requireNonNull(slug, "slug must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        String normalizedSlug = slug.trim().toLowerCase();
        log.debug("Fetching organization by slug [{}] for user [{}]", normalizedSlug, callerUserId);

        Organization organization = organizationRepository.findBySlug(normalizedSlug)
                .orElseThrow(() -> {
                    log.warn("Organization not found for slug [{}]", normalizedSlug);
                    return new OrganizationNotFoundException(
                            "Organization not found with slug: " + normalizedSlug);
                });

        authorizationService.requireMember(organization.getId(), callerUserId);
        return organization;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Trims whitespace from a string and returns {@code null} if the result is empty.
     *
     * @param value the raw input string
     * @return the trimmed string, or {@code null} if blank
     */
    private String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
