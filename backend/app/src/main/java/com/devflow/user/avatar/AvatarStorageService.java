package com.devflow.user.avatar;

import com.devflow.user.exception.AvatarStorageException;
import com.devflow.user.exception.InvalidAvatarException;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Storage abstraction for avatar file operations.
 *
 * <p>Decouples business logic from the underlying storage backend so that the
 * implementation can be replaced (e.g., local filesystem → AWS S3) without
 * modifying any service or controller code.
 *
 * <p>Implementations must be stateless and safe for concurrent use.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #store} validates and persists the file; returns a fully-qualified
 *       public URL suitable for storage in {@code UserProfile.avatarUrl}.</li>
 *   <li>{@link #delete} removes a previously stored file identified by its URL.
 *       If the file no longer exists, the call is a no-op.</li>
 * </ul>
 *
 * @see LocalAvatarStorageService
 * @see com.devflow.user.service.AvatarService
 */
public interface AvatarStorageService {

    /**
     * Validates and stores the provided file for the specified user.
     *
     * <p>Implementations are responsible for:
     * <ul>
     *   <li>Generating a unique, safe filename (never reusing the client-supplied name).</li>
     *   <li>Organising stored files by user ID to avoid namespace collisions.</li>
     *   <li>Returning a fully-qualified URL that can be stored as {@code avatarUrl}.</li>
     * </ul>
     *
     * @param file   the uploaded multipart file
     * @param userId the UUID of the owning user, used for path organisation
     * @return the public URL pointing to the stored avatar file
     * @throws InvalidAvatarException  if the file is empty or has an unsupported MIME type
     * @throws AvatarStorageException  if the file cannot be written to the backing store
     */
    String store(MultipartFile file, UUID userId);

    /**
     * Removes the avatar file identified by the given URL from the backing store.
     *
     * <p>This call is a no-op if no file exists at the specified URL; implementations
     * must not throw an exception in that case.
     *
     * @param avatarUrl the public URL previously returned by {@link #store}
     * @throws AvatarStorageException if the file exists but cannot be deleted
     */
    void delete(String avatarUrl);
}
