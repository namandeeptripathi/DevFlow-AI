package com.devflow.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Strongly-typed configuration properties for avatar file management.
 *
 * <p>Bound from the {@code devflow.avatar} YAML namespace. All values are validated
 * at application startup (fail-fast principle): if any required property is absent
 * or malformed, the application refuses to start.
 *
 * <p>Environment-specific overrides:
 * <ul>
 *   <li>{@code application-dev.yml}  — local filesystem paths, relaxed size limits</li>
 *   <li>{@code application-prod.yml} — production upload directory, max size via env vars</li>
 * </ul>
 *
 * @see com.devflow.user.avatar.AvatarStorageService
 * @see com.devflow.user.avatar.LocalAvatarStorageService
 */
@ConfigurationProperties(prefix = "devflow.avatar")
@Validated
public class AvatarProperties {

    /**
     * Absolute or relative path to the directory where avatar files are stored.
     * <p>For local storage: a filesystem directory (e.g., {@code ./uploads/avatars}).
     * <p>Set via environment variable {@code DEVFLOW_AVATAR_UPLOAD_DIR}.
     */
    @NotBlank
    private String uploadDir = "./uploads/avatars";

    /**
     * Base URL prefix prepended to the stored filename when constructing the
     * public-facing {@code avatarUrl} written to the user profile.
     * <p>Example: {@code http://localhost:8080/uploads/avatars}
     * <p>Set via environment variable {@code DEVFLOW_AVATAR_BASE_URL}.
     */
    @NotBlank
    private String baseUrl = "http://localhost:8080/uploads/avatars";

    /**
     * Maximum permitted avatar file size in bytes.
     * Defaults to 5 MB (5 × 1024 × 1024).
     * <p>Set via environment variable {@code DEVFLOW_AVATAR_MAX_SIZE_BYTES}.
     */
    @Min(1)
    private long maxSizeBytes = 5_242_880L;

    /**
     * MIME types accepted for avatar uploads.
     * Files with any other content type are rejected before storage.
     */
    @NotNull
    private List<String> allowedContentTypes = List.of(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp"
    );

    public String getUploadDir() {
        return uploadDir;
    }

    public void setUploadDir(String uploadDir) {
        this.uploadDir = uploadDir;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public void setMaxSizeBytes(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    public List<String> getAllowedContentTypes() {
        return allowedContentTypes;
    }

    public void setAllowedContentTypes(List<String> allowedContentTypes) {
        this.allowedContentTypes = allowedContentTypes;
    }
}
