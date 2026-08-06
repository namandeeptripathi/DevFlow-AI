-- ──────────────────────────────────────────────────────────────────────────────
-- DevFlow — User Preferences Table Migration
--
-- Migration: V7__create_user_preferences_table.sql
-- Author:    DevFlow Backend Engineering
-- Date:      2026-08-06
--
-- Establishes the `user_preferences` table within the User domain boundary.
-- Stores personalisation and display preferences for each user: timezone,
-- language, UI theme, date/time formatting, and extensible notification
-- channel preferences.
--
-- Relationship: One-to-One with `users` (user_id UNIQUE + FK).
-- The UNIQUE constraint on user_id enforces the One-to-One cardinality
-- at the database level, independent of JPA-layer validation.
--
-- notification_preferences is stored as JSONB to allow flexible, schema-free
-- extension of individual notification channel toggles (e.g., emailEnabled,
-- pushEnabled) without requiring additional migrations per preference key.
-- ──────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS user_preferences (
    id                        VARCHAR(36)              NOT NULL,
    user_id                   VARCHAR(36)              NOT NULL,
    timezone                  VARCHAR(100)             DEFAULT 'UTC',
    language                  VARCHAR(10)              DEFAULT 'en',
    theme                     VARCHAR(20)              NOT NULL DEFAULT 'SYSTEM',
    date_format               VARCHAR(20)              NOT NULL DEFAULT 'ISO',
    time_format               VARCHAR(20)              NOT NULL DEFAULT 'TWENTY_FOUR_HOUR',
    notification_preferences  JSONB,
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    version                   BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT pk_user_preferences             PRIMARY KEY (id),
    CONSTRAINT uk_user_preferences_user_id     UNIQUE (user_id),
    CONSTRAINT fk_user_preferences_user        FOREIGN KEY (user_id)
                                                   REFERENCES users (id)
                                                   ON DELETE CASCADE,
    CONSTRAINT chk_user_preferences_theme      CHECK (theme IN ('LIGHT', 'DARK', 'SYSTEM')),
    CONSTRAINT chk_user_preferences_date_format CHECK (date_format IN ('ISO', 'US', 'EUROPEAN', 'DMY_SLASH', 'MDY_SLASH')),
    CONSTRAINT chk_user_preferences_time_format CHECK (time_format IN ('TWELVE_HOUR', 'TWENTY_FOUR_HOUR'))
);

CREATE INDEX IF NOT EXISTS idx_user_preferences_user_id ON user_preferences (user_id);
