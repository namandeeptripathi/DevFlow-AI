package com.devflow.user.service;

import com.devflow.config.AvatarProperties;
import com.devflow.user.avatar.AvatarStorageService;
import com.devflow.user.domain.UserProfile;
import com.devflow.user.exception.InvalidAvatarException;
import com.devflow.user.exception.UserProfileNotFoundException;
import com.devflow.user.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Objects;
import java.util.UUID;

/**
 * Domain service managing avatar upload and removal for DevFlow user profiles.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Validate the uploaded file (size, MIME type, emptiness) before delegating to storage.</li>
 *   <li>Orchestrate the storage write and profile record update atomically within a transaction.</li>
 *   <li>Remove the previous avatar file from storage when an existing avatar is replaced.</li>
 *   <li>Clear {@code UserProfile.avatarUrl} and remove the file on delete.</li>
 * </ul>
 *
 * <h2>Architectural Boundaries</h2>
 * <ul>
 *   <li>Depends on {@link AvatarStorageService} (interface) — never on a concrete storage class.</li>
 *   <li>All file I/O is delegated to the storage service; this class contains only business rules.</li>
 *   <li>Constructor injection only — no field injection.</li>
 *   <li>Audit fields ({@code updatedAt}) are managed automatically by
 *       {@link org.springframework.data.jpa.domain.support.AuditingEntityListener}.</li>
 * </ul>
 *
 * @see AvatarStorageService
 * @see UserProfileRepository
 * @see com.devflow.user.exception.InvalidAvatarException
 * @see com.devflow.user.exception.AvatarStorageException
 */
@Service
public class AvatarService {

    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);

    private final AvatarStorageService storageService;
    private final UserProfileRepository profileRepository;
    private final AvatarProperties avatarProperties;

    public AvatarService(
            AvatarStorageService storageService,
            UserProfileRepository profileRepository,
            AvatarProperties avatarProperties
    ) {
        this.storageService     = Objects.requireNonNull(storageService,     "storageService must not be null");
        this.profileRepository  = Objects.requireNonNull(profileRepository,  "profileRepository must not be null");
        this.avatarProperties   = Objects.requireNonNull(avatarProperties,   "avatarProperties must not be null");
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    /**
     * Uploads and stores an avatar for the specified user.
     *
     * <p>Business rules applied in order:
     * <ol>
     *   <li>The profile record must exist.</li>
     *   <li>The file must not be empty.</li>
     *   <li>The file size must not exceed the configured maximum ({@link AvatarProperties#getMaxSizeBytes()}).</li>
     *   <li>If the user already has an avatar, the previous file is removed from storage
     *       before the new file is written.</li>
     *   <li>{@code UserProfile.avatarUrl} is updated to the URL returned by the storage service.</li>
     * </ol>
     *
     * @param userId the UUID of the authenticated user
     * @param file   the multipart avatar file to upload
     * @return the updated {@link UserProfile} reflecting the new avatar URL
     * @throws UserProfileNotFoundException if no profile exists for the given user ID
     * @throws InvalidAvatarException       if the file fails size or emptiness validation
     */
    @Transactional
    public UserProfile uploadAvatar(UUID userId, MultipartFile file) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(file,   "file must not be null");

        validateFileSize(file);

        UserProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> {
                    log.warn("Avatar upload rejected: profile not found for user [{}]", userId);
                    return new UserProfileNotFoundException("Profile not found for user: " + userId);
                });

        // Delete previous avatar from storage before writing the new one
        deletePreviousAvatarFile(profile, userId);

        String newAvatarUrl = storageService.store(file, userId);
        profile.setAvatarUrl(newAvatarUrl);

        UserProfile saved = profileRepository.save(profile);
        log.info("Avatar uploaded for user [{}]", userId);
        return saved;
    }

    /**
     * Removes the avatar for the specified user.
     *
     * <p>Business rules:
     * <ol>
     *   <li>The profile record must exist.</li>
     *   <li>If no avatar is currently set, the call is a no-op (no error is thrown).</li>
     *   <li>The file is removed from storage and {@code UserProfile.avatarUrl} is cleared.</li>
     * </ol>
     *
     * @param userId the UUID of the authenticated user
     * @return the updated {@link UserProfile} with {@code avatarUrl} cleared to {@code null}
     * @throws UserProfileNotFoundException if no profile exists for the given user ID
     */
    @Transactional
    public UserProfile deleteAvatar(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");

        UserProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> {
                    log.warn("Avatar delete rejected: profile not found for user [{}]", userId);
                    return new UserProfileNotFoundException("Profile not found for user: " + userId);
                });

        if (profile.getAvatarUrl() == null) {
            log.debug("No avatar set for user [{}]; delete is a no-op", userId);
            return profile;
        }

        storageService.delete(profile.getAvatarUrl());
        profile.setAvatarUrl(null);

        UserProfile saved = profileRepository.save(profile);
        log.info("Avatar removed for user [{}]", userId);
        return saved;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Validates that the uploaded file does not exceed the configured maximum size.
     *
     * @param file the multipart file to check
     * @throws InvalidAvatarException if the file exceeds {@link AvatarProperties#getMaxSizeBytes()}
     */
    private void validateFileSize(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidAvatarException("Uploaded file must not be empty");
        }
        long maxBytes = avatarProperties.getMaxSizeBytes();
        if (file.getSize() > maxBytes) {
            throw new InvalidAvatarException(
                    "File size " + file.getSize() + " bytes exceeds the maximum allowed size of "
                    + maxBytes + " bytes (" + (maxBytes / 1_048_576) + " MB)");
        }
    }

    /**
     * Removes the previously stored avatar file from storage if one exists.
     * Failures are logged as warnings rather than propagated, because the primary
     * goal (storing the new avatar) should not be blocked by a stale file deletion failure.
     *
     * @param profile the current user profile
     * @param userId  the user ID (for logging context)
     */
    private void deletePreviousAvatarFile(UserProfile profile, UUID userId) {
        if (profile.getAvatarUrl() != null) {
            try {
                storageService.delete(profile.getAvatarUrl());
                log.debug("Previous avatar removed for user [{}] before upload", userId);
            } catch (Exception e) {
                log.warn("Failed to remove previous avatar for user [{}]; proceeding with upload. Cause: {}",
                        userId, e.getMessage());
            }
        }
    }
}
