-- ──────────────────────────────────────────────────────────────────────────────
-- DevFlow — Refresh Tokens Table Migration
--
-- Migration: V3__create_refresh_tokens_table.sql
-- Author:    DevFlow Backend Engineering
-- Date:      2026-08-05
--
-- Establishes the `refresh_tokens` table for tracking cryptographically hashed
-- long-lived refresh token sessions and supporting Token Rotation & Theft Detection.
-- ──────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id VARCHAR(36) PRIMARY KEY,
    token_hash VARCHAR(255) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    device_id VARCHAR(100),
    device_name VARCHAR(100),
    ip_address VARCHAR(45),
    user_agent VARCHAR(255),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_revoked ON refresh_tokens(revoked);
