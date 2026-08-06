package com.devflow.user.domain;

/**
 * Enumerates the available date display formats for a DevFlow user.
 *
 * <p>Governs how dates are rendered in the UI layer. The format label
 * is a human-readable pattern example — actual rendering is the
 * responsibility of the presentation layer.
 *
 * <p>Persisted as a {@code VARCHAR} column via {@link jakarta.persistence.EnumType#STRING}.
 *
 * @see UserPreferences
 */
public enum DateFormat {

    /**
     * ISO 8601 format: {@code YYYY-MM-DD} (e.g., {@code 2026-08-06}).
     * Recommended for technical and international contexts.
     */
    ISO,

    /**
     * United States format: {@code MM/DD/YYYY} (e.g., {@code 08/06/2026}).
     */
    US,

    /**
     * European format: {@code DD.MM.YYYY} (e.g., {@code 06.08.2026}).
     */
    EUROPEAN,

    /**
     * Day-month-year with slash separator: {@code DD/MM/YYYY} (e.g., {@code 06/08/2026}).
     */
    DMY_SLASH,

    /**
     * Month-day-year with slash separator: {@code MM/DD/YYYY} (e.g., {@code 08/06/2026}).
     * Alias for US style with explicit slash delimiters.
     */
    MDY_SLASH
}
