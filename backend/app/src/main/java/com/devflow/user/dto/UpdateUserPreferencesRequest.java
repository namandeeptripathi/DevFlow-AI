package com.devflow.user.dto;

import com.devflow.user.domain.DateFormat;
import com.devflow.user.domain.Theme;
import com.devflow.user.domain.TimeFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Data Transfer Object representing the request body for {@code PATCH /api/v1/users/me/preferences}.
 *
 * <p>All fields are optional. A {@code null} field signals that the caller does not wish to update that attribute.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class UpdateUserPreferencesRequest {

    /** Selected UI theme (LIGHT, DARK, SYSTEM). */
    private Theme theme;

    /** User's primary timezone ID (e.g. "UTC", "America/New_York", "Asia/Kolkata"). */
    @Size(max = 100, message = "Timezone cannot exceed 100 characters")
    private String timezone;

    /** Preferred UI language code (e.g. "en", "es", "fr"). */
    @Size(max = 10, message = "Language code cannot exceed 10 characters")
    private String language;

    /** Date display format preference. */
    private DateFormat dateFormat;

    /** Time display format preference. */
    private TimeFormat timeFormat;

    /** Channel-specific notification preferences. */
    @Valid
    private NotificationPreferencesDto notificationPreferences;
}
