package com.devflow.user.dto;

import com.devflow.user.domain.DateFormat;
import com.devflow.user.domain.Theme;
import com.devflow.user.domain.TimeFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/**
 * Data Transfer Object representing the user preferences returned to API clients.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class UserPreferencesResponse {

    /** Unique identifier of the preferences record. */
    private UUID id;

    /** UUID of the associated {@link com.devflow.user.domain.User}. */
    private UUID userId;

    /** UI Theme setting. */
    private Theme theme;

    /** Timezone ID string. */
    private String timezone;

    /** Language code string. */
    private String language;

    /** Preferred date display format. */
    private DateFormat dateFormat;

    /** Preferred time display format. */
    private TimeFormat timeFormat;

    /** Structured notification channel preferences. */
    private NotificationPreferencesDto notificationPreferences;

    /** Record creation timestamp. */
    private Instant createdAt;

    /** Record last modified timestamp. */
    private Instant updatedAt;
}
