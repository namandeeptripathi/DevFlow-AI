package com.devflow.user.service;

import com.devflow.user.domain.UserProfile;
import com.devflow.user.exception.UserProfileNotFoundException;
import com.devflow.user.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Domain service managing the retrieval and mutation of a DevFlow user's profile.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Retrieve the current user's {@link UserProfile}.</li>
 *   <li>Apply validated, field-level updates to the profile.</li>
 * </ul>
 *
 * <h2>Architectural Boundaries</h2>
 * <ul>
 *   <li>This service operates exclusively on profile data. Authentication-owned fields
 *       ({@code email}, {@code username}, {@code passwordHash}) are never mutated here.</li>
 *   <li>Avatar management is out of scope for this stage.</li>
 *   <li>Audit fields ({@code createdAt}, {@code updatedAt}) are managed automatically by
 *       {@link org.springframework.data.jpa.domain.support.AuditingEntityListener}.</li>
 *   <li>Constructor injection only — no field injection.</li>
 *   <li>Zero direct database or repository access outside this service's own repository.</li>
 * </ul>
 *
 * @see UserProfile
 * @see UserProfileRepository
 * @see UpdateProfileRequest
 * @see UserProfileNotFoundException
 */
@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    private final UserProfileRepository userProfileRepository;

    public UserProfileService(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = Objects.requireNonNull(
                userProfileRepository, "userProfileRepository must not be null");
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Retrieves the {@link UserProfile} for the specified user.
     *
     * @param userId the UUID of the authenticated user
     * @return the user's profile entity
     * @throws UserProfileNotFoundException if no profile record exists for the given user ID
     */
    @Transactional(readOnly = true)
    public UserProfile getProfile(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");

        log.debug("Fetching profile for user [{}]", userId);

        return userProfileRepository.findByUserId(userId)
                .orElseThrow(() -> {
                    log.warn("Profile not found for user [{}]", userId);
                    return new UserProfileNotFoundException(
                            "Profile not found for user: " + userId);
                });
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    /**
     * Applies a validated set of field-level updates to the user's profile.
     *
     * <p>Only the following fields may be updated via this method:
     * <ul>
     *   <li>{@code displayName}</li>
     *   <li>{@code firstName}</li>
     *   <li>{@code lastName}</li>
     *   <li>{@code bio}</li>
     * </ul>
     *
     * <p>Each field is trimmed before persistence. Empty strings are normalised to
     * {@code null} so that the database does not store blank strings.
     * Fields absent from the request (i.e., {@code null}) are left unchanged on
     * the entity, enabling partial updates without an explicit PATCH vs PUT distinction.
     *
     * @param userId  the UUID of the authenticated user
     * @param request the update command containing new field values
     * @return the saved {@link UserProfile} reflecting the applied changes
     * @throws UserProfileNotFoundException if no profile record exists for the given user ID
     * @throws IllegalArgumentException     if the request itself is null
     */
    @Transactional
    public UserProfile updateProfile(UUID userId, UpdateProfileRequest request) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(request, "UpdateProfileRequest must not be null");

        UserProfile profile = userProfileRepository.findByUserId(userId)
                .orElseThrow(() -> {
                    log.warn("Profile update rejected: profile not found for user [{}]", userId);
                    return new UserProfileNotFoundException(
                            "Profile not found for user: " + userId);
                });

        applyUpdates(profile, request);

        UserProfile saved = userProfileRepository.save(profile);
        log.info("Profile updated for user [{}]", userId);
        return saved;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Applies non-null field values from the request onto the profile entity.
     *
     * <p>Null request fields are intentionally skipped to support partial updates:
     * only fields explicitly provided by the caller are written through.
     * Non-null values are trimmed; blank values (empty after trimming) are stored as
     * {@code null} to avoid persisting whitespace-only strings.
     *
     * @param profile the entity to mutate in place
     * @param request the update command
     */
    private void applyUpdates(UserProfile profile, UpdateProfileRequest request) {
        if (request.getDisplayName() != null) {
            profile.setDisplayName(normalise(request.getDisplayName()));
        }
        if (request.getFirstName() != null) {
            profile.setFirstName(normalise(request.getFirstName()));
        }
        if (request.getLastName() != null) {
            profile.setLastName(normalise(request.getLastName()));
        }
        if (request.getBio() != null) {
            profile.setBio(normalise(request.getBio()));
        }
    }

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
