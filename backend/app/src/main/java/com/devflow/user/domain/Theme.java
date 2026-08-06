package com.devflow.user.domain;

/**
 * Enumerates the available UI theme preferences for a DevFlow user.
 *
 * <p>Persisted as a {@code VARCHAR} column via {@link jakarta.persistence.EnumType#STRING}
 * to ensure readability and forward-compatible schema migrations.
 *
 * @see UserPreferences
 */
public enum Theme {

    /**
     * Light colour scheme — high-contrast white background.
     */
    LIGHT,

    /**
     * Dark colour scheme — reduced-glare dark background.
     */
    DARK,

    /**
     * Follows the operating system / browser preference automatically.
     */
    SYSTEM
}
