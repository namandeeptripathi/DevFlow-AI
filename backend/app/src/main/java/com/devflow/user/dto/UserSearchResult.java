package com.devflow.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

/**
 * Data Transfer Object representing an individual user search result item.
 *
 * <p>Exposes only public-safe identity and profile information. Sensitive
 * attributes (email, password hash, account status, internal audit fields)
 * are strictly omitted.
 *
 * @see UserSearchResponse
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class UserSearchResult {

    /** Unique identifier of the user identity. */
    private UUID userId;

    /** Unique username identifier. */
    private String username;

    /** Public display name from the user's profile (may be null if unpopulated). */
    private String displayName;

    /** Fully-qualified URL to the user's avatar asset (may be null if unset). */
    private String avatarUrl;
}
