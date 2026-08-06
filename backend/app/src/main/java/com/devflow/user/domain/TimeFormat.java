package com.devflow.user.domain;

/**
 * Enumerates the available time display formats for a DevFlow user.
 *
 * <p>Governs how times are rendered in the UI layer. Actual clock rendering
 * is the responsibility of the presentation layer.
 *
 * <p>Persisted as a {@code VARCHAR} column via {@link jakarta.persistence.EnumType#STRING}.
 *
 * @see UserPreferences
 */
public enum TimeFormat {

    /**
     * 12-hour clock with AM/PM suffix (e.g., {@code 02:45 PM}).
     */
    TWELVE_HOUR,

    /**
     * 24-hour clock (e.g., {@code 14:45}).
     */
    TWENTY_FOUR_HOUR
}
