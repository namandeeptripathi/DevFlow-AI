-- ──────────────────────────────────────────────────────────────────────────────
-- DevFlow — User Profiles Table Migration
--
-- Migration: V6__create_user_profiles_table.sql
-- Author:    DevFlow Backend Engineering
-- Date:      2026-08-06
--
-- Establishes the `user_profiles` table within the User domain boundary.
-- Stores public-facing profile information for each user (display name,
-- names, bio, avatar URL). Intentionally separated from the `users` table
-- to uphold the Single Responsibility Principle and allow independent
-- evolution of identity and profile concerns.
--
-- Relationship: One-to-One with `users` (user_id UNIQUE + FK).
-- The UNIQUE constraint on user_id enforces the One-to-One cardinality
-- at the database level, independent of JPA-layer validation.
-- ──────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS user_profiles (
    id          VARCHAR(36)                  NOT NULL,
    user_id     VARCHAR(36)                  NOT NULL,
    display_name VARCHAR(100),
    first_name  VARCHAR(100),
    last_name   VARCHAR(100),
    bio         VARCHAR(1000),
    avatar_url  VARCHAR(2048),
    created_at  TIMESTAMP WITH TIME ZONE     NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE     NOT NULL,
    version     BIGINT                       NOT NULL DEFAULT 0,

    CONSTRAINT pk_user_profiles             PRIMARY KEY (id),
    CONSTRAINT uk_user_profiles_user_id     UNIQUE (user_id),
    CONSTRAINT fk_user_profiles_user        FOREIGN KEY (user_id)
                                                REFERENCES users (id)
                                                ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_user_profiles_user_id ON user_profiles (user_id);
