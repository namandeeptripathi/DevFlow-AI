-- ──────────────────────────────────────────────────────────────────────────────
-- DevFlow — Authentication Domain Users Table Migration
--
-- Migration: V2__create_users_table.sql
-- Author:    DevFlow Backend Engineering
-- Date:      2026-08-05
--
-- Establishes the core `users` table inside the authentication boundary.
-- Stores user identity, hashed credentials, email verification status,
-- account lifecycle status, optimistic locking version, and audit timestamps.
-- ──────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(36) PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    username VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    account_status VARCHAR(30) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    last_login_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_username UNIQUE (username)
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
