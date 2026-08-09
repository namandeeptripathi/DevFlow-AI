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
 *   <li>Retrieve organizations by ID or slug.</li>
 *   <li>Apply validated updates to mutable organization fields.</li>
 *   <li>Delete an organization by ID.</li>
 * </ul>
 *
 * <h2>Architectural Boundaries</h2>
 * <ul>
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
 * @see OrganizationNotFoundException
 * @see OrganizationAlreadyExistsException
 */
@Service
public class OrganizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final UserRepository userRepository;

    public OrganizationService(
            OrganizationRepository organizationRepository,
            OrganizationMemberRepository organizationMemberRepository,
            UserRepository userRepository
    ) {
        this.organizationRepository = Objects.requireNonNull(
                organizationRepository, "organizationRepository must not be null");
        this.organizationMemberRepository = Objects.requireNonNull(
                organizationMemberRepository, "organizationMemberRepository must not be null");
        this.userRepository = Objects.requireNonNull(
                userRepository, "userRepository must not be null");
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    /**
     * Creates a new organization with the specified name, slug, description, and owner.
     * Automatically persists the founding user as an {@link OrganizationRole#OWNER} member.
     *
     * <p>Business rules enforced:
     * <ul>
     *   <li>The slug must be globally unique across all organizations.</li>
     *   <li>The owner must be an existing, persisted user.</li>
     *   <li>The owner is atomically granted an active {@link OrganizationRole#OWNER} membership.</li>
     * </ul>
     *
     * @param name        the display name of the organization
     * @param slug        the URL-safe slug (must be unique)
     * @param description an optional human-readable description
     * @param ownerId     the UUID of the founding user
     * @return the persisted {@link Organization} entity
     * @throws OrganizationAlreadyExistsException if the slug is already in use
     * @throws OrganizationNotFoundException      if the owner user ID does not exist
     */
    @Transactional
    public Organization createOrganization(String name, String slug, String description, UUID ownerId) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(slug, "slug must not be null");
        Objects.requireNonNull(ownerId, "ownerId must not be null");

        String normalizedSlug = slug.trim().toLowerCase();

        if (organizationRepository.existsBySlug(normalizedSlug)) {
            log.warn("Organization creation rejected: slug [{}] is already in use", normalizedSlug);
            throw new OrganizationAlreadyExistsException(
                    "Organization already exists with slug: " + normalizedSlug);
        }

        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> {
                    log.warn("Organization creation rejected: owner [{}] not found", ownerId);
                    return new OrganizationNotFoundException(
                            "Owner user not found: " + ownerId);
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
                saved.getId(), saved.getSlug(), ownerId);
        return saved;
    }

    /**
     * Updates the mutable fields of an existing organization.
     *
     * <p>Only {@code name} and {@code description} are eligible for update.
     * The {@code slug} is immutable after creation and is never modified by this method
     * to preserve URL stability and external reference integrity.
     *
     * @param organizationId the UUID of the organization to update
     * @param name           the new display name (if {@code null}, left unchanged)
     * @param description    the new description (if {@code null}, left unchanged)
     * @return the updated {@link Organization} entity
     * @throws OrganizationNotFoundException if no organization exists with the given ID
     */
    @Transactional
    public Organization updateOrganization(UUID organizationId, String name, String description) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");

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
        log.info("Updated organization [{}]", saved.getId());
        return saved;
    }

    /**
     * Deletes an organization by its unique identifier.
     *
     * @param organizationId the UUID of the organization to delete
     * @throws OrganizationNotFoundException if no organization exists with the given ID
     */
    @Transactional
    public void deleteOrganization(UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> {
                    log.warn("Organization deletion rejected: organization [{}] not found", organizationId);
                    return new OrganizationNotFoundException(
                            "Organization not found: " + organizationId);
                });

        organizationRepository.delete(organization);
        log.info("Deleted organization [{}] with slug [{}]", organizationId, organization.getSlug());
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Retrieves an organization by its unique identifier.
     *
     * @param organizationId the UUID of the organization
     * @return the {@link Organization} entity
     * @throws OrganizationNotFoundException if no organization exists with the given ID
     */
    @Transactional(readOnly = true)
    public Organization getOrganizationById(UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");

        log.debug("Fetching organization by ID [{}]", organizationId);

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
     * @param slug the slug to query (e.g., {@code "acme-corp"})
     * @return the {@link Organization} entity
     * @throws OrganizationNotFoundException if no organization exists with the given slug
     */
    @Transactional(readOnly = true)
    public Organization getOrganizationBySlug(String slug) {
        Objects.requireNonNull(slug, "slug must not be null");

        String normalizedSlug = slug.trim().toLowerCase();
        log.debug("Fetching organization by slug [{}]", normalizedSlug);

        return organizationRepository.findBySlug(normalizedSlug)
                .orElseThrow(() -> {
                    log.warn("Organization not found for slug [{}]", normalizedSlug);
                    return new OrganizationNotFoundException(
                            "Organization not found with slug: " + normalizedSlug);
                });
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
