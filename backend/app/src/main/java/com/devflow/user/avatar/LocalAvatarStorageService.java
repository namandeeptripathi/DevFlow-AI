package com.devflow.user.avatar;

import com.devflow.config.AvatarProperties;
import com.devflow.user.exception.AvatarStorageException;
import com.devflow.user.exception.InvalidAvatarException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Local filesystem implementation of {@link AvatarStorageService}.
 *
 * <p>Stores avatar files under {@code {uploadDir}/{userId}/{uuid}.{ext}} on the
 * local filesystem. The public-facing URL is constructed as
 * {@code {baseUrl}/{userId}/{filename}}.
 *
 * <h2>Security Measures</h2>
 * <ul>
 *   <li><strong>No original filename used</strong>: the stored filename is always a
 *       freshly generated UUID, eliminating path injection via client-supplied names.</li>
 *   <li><strong>Path traversal prevention</strong>: the resolved target path is checked
 *       to ensure it starts with the canonical upload root before any write occurs.</li>
 *   <li><strong>Content type allowlist</strong>: only MIME types declared in
 *       {@link AvatarProperties#getAllowedContentTypes()} are accepted.</li>
 * </ul>
 *
 * <h2>Replacement Contract</h2>
 * <p>To replace this implementation with a cloud backend (AWS S3, GCS, etc.), create a
 * new {@code @Service} implementing {@link AvatarStorageService} and remove or
 * qualify this one with {@code @Profile}. No other class needs to change.
 *
 * @see AvatarStorageService
 * @see AvatarProperties
 */
@Service
public class LocalAvatarStorageService implements AvatarStorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalAvatarStorageService.class);

    /**
     * Maps accepted MIME types to their canonical file extensions.
     * Only MIME types present here are permitted; the extension is never derived
     * from the client-supplied filename.
     */
    private static final Map<String, String> CONTENT_TYPE_TO_EXTENSION = Map.of(
            "image/jpeg", "jpg",
            "image/png",  "png",
            "image/gif",  "gif",
            "image/webp", "webp"
    );

    private final AvatarProperties avatarProperties;
    private final Path uploadRoot;

    public LocalAvatarStorageService(AvatarProperties avatarProperties) {
        this.avatarProperties = Objects.requireNonNull(
                avatarProperties, "avatarProperties must not be null");
        this.uploadRoot = Paths.get(avatarProperties.getUploadDir()).toAbsolutePath().normalize();
        initialiseUploadRoot();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The file is written to {@code {uploadDir}/{userId}/{uuid}.{ext}}.
     * The returned URL is {@code {baseUrl}/{userId}/{filename}}.
     */
    @Override
    public String store(MultipartFile file, UUID userId) {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        String contentType = resolveContentType(file);
        String extension   = resolveExtension(contentType);
        String filename    = UUID.randomUUID() + "." + extension;

        Path userDir    = resolveUserDirectory(userId);
        Path targetPath = userDir.resolve(filename);

        guardPathTraversal(targetPath);
        ensureDirectory(userDir);
        writeFile(file, targetPath);

        String avatarUrl = buildUrl(userId, filename);
        log.info("Stored avatar for user [{}] → [{}]", userId, filename);
        return avatarUrl;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Resolves the local file path from the given URL and deletes it. If no file
     * exists at the resolved path, the call returns silently.
     */
    @Override
    public void delete(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return;
        }

        Path filePath = resolvePathFromUrl(avatarUrl);
        if (filePath == null) {
            log.warn("Could not resolve local path from avatar URL [{}]; skipping delete", avatarUrl);
            return;
        }

        guardPathTraversal(filePath);

        try {
            boolean deleted = Files.deleteIfExists(filePath);
            if (deleted) {
                log.info("Deleted avatar file [{}]", filePath.getFileName());
            } else {
                log.debug("Avatar file [{}] did not exist; delete is a no-op", filePath);
            }
        } catch (IOException e) {
            throw new AvatarStorageException(
                    "Failed to delete avatar file: " + filePath.getFileName(), e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Creates the upload root directory at startup if it does not already exist.
     */
    private void initialiseUploadRoot() {
        try {
            Files.createDirectories(uploadRoot);
            log.info("Avatar upload root initialised: [{}]", uploadRoot);
        } catch (IOException e) {
            throw new AvatarStorageException(
                    "Failed to initialise avatar upload directory: " + uploadRoot, e);
        }
    }

    /**
     * Extracts and validates the MIME type from the multipart file.
     *
     * @param file the uploaded multipart file
     * @return the validated MIME type string
     * @throws InvalidAvatarException if the file is empty or has an unsupported content type
     */
    private String resolveContentType(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidAvatarException("Uploaded file must not be empty");
        }

        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            throw new InvalidAvatarException("File content type could not be determined");
        }

        String normalised = contentType.trim().toLowerCase();
        if (!avatarProperties.getAllowedContentTypes().contains(normalised)) {
            throw new InvalidAvatarException(
                    "Unsupported file type: [" + contentType + "]. Accepted types: "
                    + String.join(", ", avatarProperties.getAllowedContentTypes()));
        }

        return normalised;
    }

    /**
     * Derives the file extension from the validated MIME type.
     */
    private String resolveExtension(String contentType) {
        String extension = CONTENT_TYPE_TO_EXTENSION.get(contentType);
        if (extension == null) {
            // Safeguard — should not occur because resolveContentType already validates
            throw new InvalidAvatarException("No extension mapping found for type: " + contentType);
        }
        return extension;
    }

    /**
     * Resolves the per-user directory path within the upload root.
     */
    private Path resolveUserDirectory(UUID userId) {
        return uploadRoot.resolve(userId.toString()).normalize();
    }

    /**
     * Ensures the directory at the given path exists, creating it if necessary.
     *
     * @throws AvatarStorageException if the directory cannot be created
     */
    private void ensureDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new AvatarStorageException(
                    "Failed to create avatar storage directory: " + directory, e);
        }
    }

    /**
     * Asserts that the target path is a strict descendant of the upload root.
     * Prevents path traversal attacks where a malicious URL could escape the root.
     *
     * @throws AvatarStorageException if the path escapes the upload root
     */
    private void guardPathTraversal(Path path) {
        if (!path.normalize().startsWith(uploadRoot)) {
            throw new AvatarStorageException(
                    "Resolved path escapes the configured upload directory — possible path traversal attempt");
        }
    }

    /**
     * Writes the multipart file content to the target path atomically.
     *
     * @throws AvatarStorageException if the file cannot be written
     */
    private void writeFile(MultipartFile file, Path targetPath) {
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new AvatarStorageException(
                    "Failed to write avatar file to storage: " + targetPath.getFileName(), e);
        }
    }

    /**
     * Constructs the public-facing avatar URL from the base URL, user ID, and filename.
     */
    private String buildUrl(UUID userId, String filename) {
        String base = avatarProperties.getBaseUrl();
        // Ensure no double slashes between base URL and path segments
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + userId + "/" + filename;
    }

    /**
     * Derives the local filesystem path from a previously generated avatar URL.
     *
     * <p>Strips the configured base URL prefix and resolves the remainder against
     * the upload root. Returns {@code null} if the URL does not match the expected prefix.
     *
     * @param avatarUrl the public URL of the avatar
     * @return the resolved local path, or {@code null} if the URL cannot be parsed
     */
    private Path resolvePathFromUrl(String avatarUrl) {
        String base = avatarProperties.getBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        if (!avatarUrl.startsWith(base)) {
            return null;
        }

        String relativePart = avatarUrl.substring(base.length());
        if (relativePart.startsWith("/")) {
            relativePart = relativePart.substring(1);
        }

        try {
            // Use Path.of to safely parse the relative portion — never trust string splits
            Path relative = Paths.get(relativePart).normalize();
            return uploadRoot.resolve(relative).normalize();
        } catch (InvalidPathException e) {
            log.warn("Cannot parse relative path from avatar URL [{}]", avatarUrl);
            return null;
        }
    }
}
