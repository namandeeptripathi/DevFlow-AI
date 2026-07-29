# DevFlow — Authentication Strategy

> **Version:** 1.0.0
> **Status:** Final / Architecture Review Board (ARB) Approved
> **Author:** Principal Security Architect
> **Date:** 2026-07-29
> **Classification:** Internal — Engineering & Security

---

## Table of Contents

1. [Purpose](#1-purpose)
2. [Authentication Architecture](#2-authentication-architecture)
3. [Supported Authentication Methods](#3-supported-authentication-methods)
4. [Authentication Lifecycle](#4-authentication-lifecycle)
5. [JWT Strategy](#5-jwt-strategy)
6. [Refresh Token Strategy](#6-refresh-token-strategy)
7. [Password Security](#7-password-security)
8. [OAuth Strategy](#8-oauth-strategy)
9. [Session Management](#9-session-management)
10. [Token Revocation](#10-token-revocation)
11. [Multi-Tenant Authentication](#11-multi-tenant-authentication)
12. [CLI Authentication](#12-cli-authentication)
13. [VS Code Extension Authentication](#13-vs-code-extension-authentication)
14. [Security Considerations](#14-security-considerations)
15. [Future Evolution](#15-future-evolution)

---

## 1. Purpose

Authentication in DevFlow answers one foundational question before any operation is permitted: **Who are you, and can I trust that claim?**

DevFlow is an AI-First Engineering Intelligence Platform that stores and processes some of an organization's most sensitive intellectual assets — source code histories, engineering velocity data, architectural documentation, and AI-generated insights. Every API call, every AI prompt, and every background task operates within a verified identity boundary. Authentication is therefore not a feature; it is a foundational architectural contract.

### Why Every Client Surface Requires Authentication

| Client | Why Authentication is Required |
| :--- | :--- |
| **Web Application (Next.js)** | Users manage confidential project data, team memberships, and billing. Authentication prevents unauthorized cross-organization access and establishes the tenant execution context. |
| **CLI Tool** | The CLI executes privileged operations — pushing tasks, querying repositories, triggering AI reviews — from developer machines that may be shared. A long-lived, revocable credential model protects against abandoned sessions on shared workstations. |
| **VS Code Extension** | The extension operates inside the developer's IDE and has ambient access to the files open in the editor. It must operate with a scoped, refreshable credential derived from the user's primary browser session, not an embedded long-lived secret. |
| **Future Mobile App** | Mobile devices have higher theft and compromise probability than workstations. The authentication model must support short-lived access tokens, biometric-gated refresh, and remote session revocation without disrupting other active devices. |

---

## 2. Authentication Architecture

### 2.1 Architectural Layers

The authentication system spans five layered tiers from the client surface down to the protected domain modules.

```
+---------------------------------------------------------------------------+
|                             CLIENT TIER                                   |
|                                                                           |
|  +----------------+  +--------------+  +----------------+  +----------+  |
|  |  Web App        |  |  CLI Tool    |  |  VS Code Ext   |  |  Mobile  |  |
|  |  (Next.js)      |  |  (GraalVM)   |  |  (TypeScript)  |  | (Future) |  |
|  +--------+-------+  +------+-------+  +-------+--------+  +----+-----+  |
+-----------|-----------------|------------------|-----------------|---------+
            |                 |                  |                 |
            v                 v                  v                 v
+---------------------------------------------------------------------------+
|                       AUTHENTICATION LAYER                                |
|                                                                           |
|  +-------------------------------------------------------------------+    |
|  |          Security Filter Chain (Reverse Proxy -> Backend)         |    |
|  |                                                                   |    |
|  |  +------------------+  +----------------+  +-------------------+  |    |
|  |  |  JWT Auth Filter  |  | Rate-Limit     |  |  Tenant           |  |    |
|  |  |  (RS256 Verify)   |  | Filter (Redis) |  |  Resolution Filter|  |    |
|  |  +------------------+  +----------------+  +-------------------+  |    |
|  +-------------------------------------------------------------------+    |
+---------------------------------------------------------------------------+
                                     |
                                     v
+---------------------------------------------------------------------------+
|                         IDENTITY MODULE                                   |
|                          (devflow-auth)                                   |
|                                                                           |
|  +------------------+  +------------------+  +-------------------------+  |
|  | Registration &   |  |  OAuth 2.0       |  |  RBAC Authorization     |  |
|  | Login Handlers   |  |  Provider Bridge |  |  (Permission Evaluator) |  |
|  +------------------+  +------------------+  +-------------------------+  |
+---------------------------------------------------------------------------+
                                     |
                                     v
+---------------------------------------------------------------------------+
|                          TOKEN SERVICE                                    |
|                                                                           |
|  +-------------------+  +-------------------+  +----------------------+  |
|  |  Access Token     |  |  Refresh Token    |  |  Token Revocation    |  |
|  |  Generator (RS256)|  |  Rotation Service |  |  Registry (Redis)    |  |
|  +-------------------+  +-------------------+  +----------------------+  |
+---------------------------------------------------------------------------+
                                     |
                                     v
+---------------------------------------------------------------------------+
|                       PROTECTED DOMAIN MODULES                            |
|                                                                           |
|  +----------+  +----------+  +----------+  +----------+  +-----------+   |
|  | Project  |  |  Repo    |  |    AI    |  |Knowledge |  | Analytics |   |
|  |  Mgmt   |  |  Intel   |  |  Engine  |  |   Base   |  |           |   |
|  +----------+  +----------+  +----------+  +----------+  +-----------+   |
+---------------------------------------------------------------------------+
```

### 2.2 Component Responsibilities

| Component | Responsibility |
| :--- | :--- |
| **Security Filter Chain** | Intercepts every inbound HTTP request before it reaches any module. Validates JWT signatures, enforces rate limits, and resolves the active tenant context. Unauthenticated requests are rejected at this boundary — no protected module code is executed. |
| **Identity Module (`devflow-auth`)** | The sole owner of user identity and organization membership data. Manages registration, login, OAuth flows, and credential validation. Provides a compiled public API (`AuthApi`) that other modules call to verify authorization scopes. |
| **Token Service** | A logical service within the Identity Module responsible exclusively for the creation, signing, and registration of access and refresh tokens. Maintains the Redis-backed revocation registry. |
| **Protected Domain Modules** | All other Bounded Contexts (Project Management, AI Engine, etc.). They do not perform authentication themselves. They trust the upstream Security Filter Chain to have already established an authenticated principal, and they apply RBAC checks via the `AuthApi` for resource-level authorization decisions. |

---

## 3. Supported Authentication Methods

### 3.1 Current Supported Methods

#### Email & Password
The foundational, universally available authentication mechanism. A user registers with a verified email address and a password that meets the platform's security policy. Credentials are validated at login by comparing the submitted password against a stored, salted, and hashed representation.

**Why it exists:** Not every developer uses Google or GitHub personal accounts. Enterprise organizations frequently provision team members with corporate email addresses that have no OAuth identity. Email/password provides a universal fallback.

#### Google OAuth 2.0
Users may authenticate using their Google identity via the standard OAuth 2.0 Authorization Code Flow with PKCE. On first use, DevFlow reads the verified email address from the Google token and either creates a new linked `User` account or associates the connection with an existing one.

**Why it exists:** Google Workspace is the dominant enterprise identity provider for engineering teams globally. Providing native Google OAuth removes the password management burden and leverages the stronger authentication standards embedded in Google accounts (including their internal MFA enforcement).

#### GitHub OAuth 2.0
Users may authenticate via their GitHub identity. This is the primary login flow for the developer persona that DevFlow targets. Beyond authentication, a GitHub OAuth connection establishes the foundation for the Repository Intelligence module to access private repositories.

**Why it exists:** DevFlow's core value proposition is tightly tied to Git repository intelligence. GitHub OAuth serves a dual purpose: it establishes identity and simultaneously bootstraps the repository integration authorization context. Authenticating via GitHub creates a natural, frictionless onboarding path from "login" to "connect my first repository" in a single flow.

---

### 3.2 Planned Future Methods

| Method | Purpose | Phase |
| :--- | :--- | :--- |
| **Personal Access Tokens (PATs)** | Non-interactive, long-lived credentials for the CLI tool and CI/CD automation pipelines. Scoped to specific organizations and permission sets. | Phase 2 |
| **Service Accounts** | Machine identity principals for automated background integrations and third-party webhook handlers that do not represent a human user. | Phase 2 |
| **Enterprise SSO (SAML 2.0 / OIDC)** | Allows enterprise customers to federate DevFlow authentication into their existing corporate identity providers (Okta, Azure AD, Ping Identity). | Phase 3 |

---

## 4. Authentication Lifecycle

### 4.1 Complete Lifecycle Map

```
  REGISTRATION          LOGIN              POST-LOGIN           TERMINATION
+------------+       +----------+       +--------------+     +-------------+
|  Submit    |       | Submit   |       | Access Token |     |   Logout    |
|  Email +   +-------> Creds or +-------> attached to  +-----> Requested   |
|  Password  |       | OAuth    |       | each request |     |             |
+------------+       +----+-----+       +------+-------+     +------+------+
      |                   |                    |                    |
      | Email             | Identity           | Token              | Access &
      | Verification      | Verified           | Expiry             | Refresh
      | Link Sent         |                    | (15 min)           | Tokens
      v                   v                    v                    | Revoked
+------------+       +----------+       +--------------+           v
|  Account   |       |   JWT    |       | Refresh Token|     +-------------+
|  Activated |       | Created  +-------> Exchange     |     |  Session    |
|            |       |          |       | (7-day token)|     |  Terminated |
+------------+       +----------+       +--------------+     +-------------+
```

### 4.2 Registration Flow

```
 Client                          Auth Module                    Email Service
   |                                  |                              |
   |--- POST /api/v1/auth/register -->|                              |
   |    {email, password, fullName}   |                              |
   |                                  |                              |
   |                                  |-- Validate email format      |
   |                                  |-- Check email uniqueness     |
   |                                  |-- Hash password w/ salt      |
   |                                  |-- Persist User (status:      |
   |                                  |   PENDING_VERIFICATION)      |
   |                                  |-- Generate signed email      |
   |                                  |   verification token         |
   |                                  |------------- Send verification email -->|
   |<-- 201 Created ------------------|                              |
   |    {message: "Check your email"} |                              |
   |                                  |                              |
   |--- GET /api/v1/auth/verify?t=... >|                              |
   |                                  |-- Validate token signature   |
   |                                  |-- Set User status: ACTIVE    |
   |<-- 200 OK ------------------------|                              |
```

### 4.3 Login & Token Issuance Flow

```
 Client          Security Filter       Auth Module          Redis       PostgreSQL
   |                   |                   |                  |              |
   |-- POST /api/v1/auth/login ----------->|                  |              |
   |   {email, password}                  |                  |              |
   |                   |                  |                  |              |
   |                   |-- Rate limit check (IP) ----------->|              |
   |                   |                  |                  |              |
   |                   |-- Route to Auth ->|                  |              |
   |                   |                  |                  |              |
   |                   |                  |-- Query User by email ---------->|
   |                   |                  |<-- User record ------------------|
   |                   |                  |                  |              |
   |                   |                  |-- Verify password hash          |
   |                   |                  |                  |              |
   |                   |                  |-- Generate signed Access Token   |
   |                   |                  |   (RS256, 15 min)               |
   |                   |                  |                  |              |
   |                   |                  |-- Generate Refresh Token         |
   |                   |                  |   (opaque, 7 days)              |
   |                   |                  |                  |              |
   |                   |                  |-- Register token IDs ----------->|
   |                   |                  |   in revocation registry        |
   |                   |                  |                  |              |
   |<-- 200 OK -------------------------------------------- |              |
   |    {accessToken, refreshToken, user}                    |              |
```

### 4.4 Authenticated Request Flow

```
 Client          Security Filter       Target Module        Auth Module
   |                   |                   |                   |
   |-- GET /api/v1/projects -------------->|                   |
   |   Authorization: Bearer <accessToken>|                   |
   |                   |                  |                   |
   |                   |-- Verify JWT signature (RS256)        |
   |                   |-- Check revocation registry (Redis)   |
   |                   |-- Extract claims: userId, orgId, roles|
   |                   |-- Populate SecurityContext            |
   |                   |-- Set TenantContext                   |
   |                   |                  |                   |
   |                   |-- Route request ->|                   |
   |                   |                  |-- Evaluate RBAC   |
   |                   |                  |   via AuthApi --->|
   |                   |                  |                   |
   |<-- 200 OK -------------------------------------------- -|
```

---

## 5. JWT Strategy

### 5.1 Why JWT was Selected

DevFlow operates with a stateless, multi-client architecture serving a web application, CLI tool, VS Code extension, and future mobile applications simultaneously. A session-based authentication model (server-side sessions stored in a database or Redis) would require every API request to perform a synchronous cache or database lookup to validate session state — adding latency to every request and creating a stateful dependency that is difficult to scale horizontally.

JSON Web Tokens solve this by embedding the verified identity claims directly inside the token itself, signed with a cryptographic key. Any backend instance can verify the token independently using the public key, without a round-trip to Redis or PostgreSQL for standard authenticated requests.

| Consideration | Session-Based | JWT (RS256) |
| :--- | :--- | :--- |
| Per-request latency | +1 Redis lookup | None (local verification) |
| Horizontal scalability | Requires sticky sessions or shared session store | Fully stateless — any instance verifies |
| Revocation | Immediate (delete session) | Requires revocation registry (Redis) |
| Token introspection | Requires lookup | Self-contained |
| Suitable for CLI/Mobile | Poor | Excellent |

### 5.2 Token Types

#### Access Token
- **Format:** Signed JSON Web Token (JWT)
- **Algorithm:** RS256 (RSA + SHA-256 asymmetric signing)
- **Lifetime:** 15 minutes
- **Purpose:** Authorizes individual API requests. Short-lived to minimize the window of exposure if a token is intercepted.
- **Transport:** Bearer token in the `Authorization` HTTP header. Never in query parameters or request bodies.

#### Refresh Token
- **Format:** Opaque cryptographically random string (not a JWT)
- **Storage:** Hashed representation stored in PostgreSQL (`auth` schema); the raw token returned only once to the client.
- **Lifetime:** 7 days
- **Purpose:** Exchanges for a new access token when the current one expires, without requiring the user to re-authenticate. The refresh token is the long-lived credential; the access token is the short-lived operational credential.

### 5.3 JWT Claims Structure

| Claim | Type | Description |
| :--- | :--- | :--- |
| `sub` | string | User's unique public identifier (UUID) |
| `email` | string | Verified email address of the authenticated user |
| `orgId` | string | Active organization's unique public identifier |
| `roles` | string[] | RBAC role names in the active organization (e.g., `["OWNER", "MEMBER"]`) |
| `scopes` | string[] | Granular permission scopes (e.g., `["project:read", "task:write"]`) |
| `jti` | string | JWT ID — unique token identifier used for per-token revocation |
| `iat` | number | Issued-at timestamp (Unix epoch seconds) |
| `exp` | number | Expiry timestamp (Unix epoch seconds, `iat` + 900 for 15 min) |

### 5.4 Signing & Key Management

- **Algorithm:** RS256. An asymmetric key pair is used: the private key signs tokens (held exclusively by the backend), and the public key verifies them. This allows future external services or a dedicated authorization service to verify tokens without holding the private key.
- **Key Storage:** Private signing keys are never embedded in source code or configuration files. They are loaded from the secrets management system (AWS Secrets Manager or HashiCorp Vault) at application startup.
- **Key Rotation:** Signing key pairs are rotated on a scheduled basis. During rotation, both the old and new public keys are exposed via a JWKS (JSON Web Key Set) endpoint, allowing in-flight tokens signed with the previous key to continue validating until they naturally expire. After the maximum access token lifetime passes, the old key is retired.

---

## 6. Refresh Token Strategy

### 6.1 Token Rotation Model

DevFlow implements **Refresh Token Rotation**. Every time a client exchanges a refresh token for a new access token, the server simultaneously:
1. Invalidates the consumed refresh token.
2. Issues a new refresh token.
3. Issues a new access token.

The client must store and use the new refresh token going forward. If an old (already consumed) refresh token is presented, it is treated as a **token theft signal**, triggering immediate revocation of the entire token family.

```
 Client                        Auth Module                  Redis / PostgreSQL
   |                               |                              |
   |-- POST /api/v1/auth/refresh ->|                              |
   |   {refreshToken: "token_abc"} |                              |
   |                               |-- Lookup token_abc hash ---->|
   |                               |   [Found, Active]            |
   |                               |-- Invalidate token_abc ----->|
   |                               |-- Issue new Access Token     |
   |                               |-- Issue new Refresh Token    |
   |                               |-- Register new tokens ------->|
   |<-- 200 OK --------------------|                              |
   |    {accessToken,              |                              |
   |     refreshToken: "token_xyz"}|                              |
   |                               |                              |
   |   ... (malicious actor reuse) |                              |
   |-- POST /api/v1/auth/refresh ->|                              |
   |   {refreshToken: "token_abc"} |                              |
   |                               |-- Lookup token_abc --------->|
   |                               |   [Found, REVOKED]           |
   |                               |-- THEFT DETECTED!            |
   |                               |-- Revoke ALL tokens          |
   |                               |   in session family -------->|
   |<-- 401 Unauthorized ----------|                              |
```

### 6.2 Refresh Token Lifecycle

| State | Trigger | Action |
| :--- | :--- | :--- |
| **Active** | Token issued after login or prior refresh | Accepted for exchange |
| **Consumed** | Token used to perform a refresh | Immediately invalidated; new token issued |
| **Expired** | 7-day TTL elapsed | Rejected; user must re-authenticate |
| **Revoked** | Logout, password change, admin action, or theft detection | Rejected; full session termination |

### 6.3 Multiple Device Support

Each login event from a new device (or client application type) issues an independent **token family** — a dedicated refresh token lineage tracked by a `deviceSessionId`. This allows:
- Independent logout per device without affecting other active sessions.
- Full session listing for the user's security dashboard (e.g., "Signed in from 3 devices").
- Targeted revocation of a specific device session by administrators during a security incident.

### 6.4 Secure Storage Requirements per Client

| Client | Recommended Refresh Token Storage | Rationale |
| :--- | :--- | :--- |
| **Web (Next.js)** | `HttpOnly`, `Secure`, `SameSite=Strict` cookie | Inaccessible to JavaScript; immune to XSS-based token theft |
| **CLI Tool** | OS-native credential store (Keychain / Credential Manager / Secret Service) | Encrypted at rest; not accessible by other processes |
| **VS Code Extension** | VS Code SecretStorage API | Extension-scoped encrypted secret storage |
| **Mobile (Future)** | iOS Keychain / Android Keystore | Hardware-backed, biometrically gated secure enclave |

---

## 7. Password Security

### 7.1 Password Hashing

User passwords are **never stored in plaintext** or in a reversibly encrypted form at any point. The only persisted representation is the output of a strong, adaptive, one-way hashing algorithm applied to the password combined with a per-user random salt.

The algorithm is designed to be computationally expensive and configurable: as hardware improves, the cost factor can be increased without invalidating existing password hashes (users will have their hash silently upgraded at next login).

### 7.2 Salt

Each password hash is computed with a unique, cryptographically random salt generated at registration time and stored alongside the hash. Per-user salting:
- Prevents precomputed rainbow table attacks from exposing bulk credentials.
- Ensures that two users with identical passwords produce entirely different stored hash values.

### 7.3 Password Policy

DevFlow enforces a minimum password quality policy at registration and password change time:
- Minimum length of 12 characters.
- Must include a combination of character classes (uppercase, lowercase, digits, special characters).
- Must not match a list of known common and compromised passwords checked against an internal blocklist.
- Must not match the user's own email address or display name.

The policy is enforced on the server side. Client-side validation may mirror it for UX, but the server is the authoritative gate.

### 7.4 Password Reset

Password reset is initiated through a secure, time-bound, single-use token:

```
 User                      Auth Module                      Email Service
  |                            |                                |
  |-- POST /api/v1/auth/       |                                |
  |   forgot-password -------->|                                |
  |   {email}                  |                                |
  |                            |-- Find user by email           |
  |                            |-- Generate cryptographically   |
  |                            |   random reset token           |
  |                            |-- Hash token, store with       |
  |                            |   15-min expiry                |
  |                            |---------------------- Send link ->|
  |<-- 200 OK (always) --------|   (token in URL)               |
  |   (no email existence leak)|                                |
  |                            |                                |
  |-- POST /api/v1/auth/       |                                |
  |   reset-password --------->|                                |
  |   {token, newPassword}     |                                |
  |                            |-- Validate token hash & expiry |
  |                            |-- Enforce password policy      |
  |                            |-- Hash new password            |
  |                            |-- Revoke all active tokens     |
  |                            |   for this user                |
  |<-- 200 OK -----------------|                                |
```

**Security properties of this flow:**
- The `/forgot-password` endpoint always returns `200 OK` regardless of whether the email exists, preventing account enumeration attacks.
- Reset tokens are single-use: once consumed, they are immediately invalidated.
- Completing a password reset triggers global session revocation across all devices.

### 7.5 Email Verification

New registrations receive a verification email containing a signed, time-bound token. Accounts in `PENDING_VERIFICATION` status cannot log in. This prevents typo-squatted account creation, use of another person's email without consent, and automated bot registrations.

---

## 8. OAuth Strategy

### 8.1 Authorization Code Flow with PKCE

All OAuth integrations use the **Authorization Code Flow with PKCE (Proof Key for Code Exchange)**. This eliminates the need to transmit a `client_secret` from the browser, protecting against authorization code interception attacks.

```
 User        Browser (Next.js)         Auth Module          OAuth Provider
  |                |                       |                     |
  |-- Click        |                       |                     |
  |   "Login with  |                       |                     |
  |    Google" --->|                       |                     |
  |                |                       |                     |
  |                |-- Generate code_verifier                    |
  |                |-- Compute code_challenge (SHA-256)          |
  |                |                       |                     |
  |                |-- Redirect to provider OAuth URL ----------->|
  |                |   with code_challenge                       |
  |                |                       |                     |
  |<---------------|  User authenticates with provider          |
  |                |                       |                     |
  |                |<----------- Authorization Code -------------|
  |                |                       |                     |
  |                |-- POST /api/v1/auth/  |                     |
  |                |   oauth/callback ---->|                     |
  |                |   {code,             |                     |
  |                |    code_verifier}    |                     |
  |                |                       |-- Exchange code --->|
  |                |                       |   + code_verifier  |
  |                |                       |<-- Provider tokens -|
  |                |                       |-- Fetch verified   |
  |                |                       |   profile & email  |
  |                |                       |-- Find or create   |
  |                |                       |   User record      |
  |                |                       |-- Issue DevFlow JWT|
  |<-- 200 OK ---------------------------------------------- ---|
  |    {accessToken, refreshToken}        |                     |
```

### 8.2 Google OAuth

- DevFlow requests `openid`, `email`, and `profile` scopes.
- The `email_verified` claim from Google's ID token is checked before account association. Unverified emails are rejected.
- The Google user's `sub` (subject identifier) is stored as an immutable external reference, tolerating future email address changes on the Google account.

### 8.3 GitHub OAuth

- DevFlow requests `read:user` and `user:email` scopes for authentication purposes.
- The GitHub user's primary verified email is used for account matching.
- Repository access scopes (`repo`, `read:org`) are requested **separately**, only during the Repository Intelligence integration setup — not at login time. Authentication and repository authorization are deliberately decoupled.
- The GitHub user `id` (numeric) is stored as an immutable external reference.

### 8.4 Account Linking

When an OAuth login is attempted and the email address extracted from the OAuth provider matches an existing `User` record:
- If the existing account was created via email/password, the OAuth connection is **linked** to that account.
- If the email originates from a different OAuth provider, both connections are stored on the same `User` record.

Account linking is performed only when the incoming email is **verified** by the OAuth provider. Unverified emails are never used for linking.

### 8.5 Future OAuth Providers

The `OAuthProviderRegistry` in `devflow-auth` is designed as a provider-agnostic registry. Adding a future OAuth provider requires:
1. Registering the provider configuration (authorization URL, token URL, required scopes).
2. Implementing a provider-specific profile normalizer that maps the provider's user object to DevFlow's internal identity model.

No structural changes to the authentication architecture are required.

---

## 9. Session Management

### 9.1 Stateless Architecture

DevFlow's authentication model is fundamentally stateless at the access token level. Backend application instances do not hold any in-memory session state between requests. The authenticated principal is reconstituted from the verified JWT claims on every request.

This design provides:
- **Horizontal scalability:** Any backend instance can serve any request without session affinity requirements.
- **Resilience:** Instance failure does not invalidate active user sessions.
- **Operational simplicity:** No distributed session store replication is needed for the access token path.

### 9.2 Device Sessions (Refresh Token Registry)

While access tokens are stateless, **refresh tokens are stateful** — they are registered in the PostgreSQL `auth` schema with device session metadata:

| Field | Purpose |
| :--- | :--- |
| `deviceSessionId` | Unique identifier for this login event / device pairing |
| `userId` | The authenticated user |
| `tokenFamily` | Groups the rotation chain for theft detection |
| `deviceName` | Human-readable label (e.g., "Chrome on macOS", "CLI on ubuntu-server") |
| `ipAddress` | IP address at time of login |
| `userAgent` | Client user agent string |
| `lastUsedAt` | Timestamp of last refresh activity |
| `expiresAt` | Absolute expiry of the session |
| `revokedAt` | Null unless explicitly terminated |

### 9.3 Session Expiration

- **Idle expiration:** If a refresh token is not used within its 7-day window, it expires naturally. The user must re-authenticate.
- **Absolute expiration:** Refresh tokens do not self-renew their TTL. Extended sessions require periodic re-authentication, configurable per organization for enterprise compliance requirements.

### 9.4 Forced Logout & Session Termination

An authorized user can view all active device sessions and terminate individual ones. Platform administrators can force-terminate all sessions for a specific user account. See Section 10 for the complete revocation model.

---

## 10. Token Revocation

### 10.1 Revocation Architecture

Because access tokens are stateless and self-validating, revocation requires checking against a **blocklist registry stored in Redis**. The access token's `jti` claim provides the per-token handle for this registry.

```
+--------------------------------------------------------------------------+
|                       TOKEN REVOCATION REGISTRY                          |
|                              (Redis 7)                                   |
|                                                                          |
|  Access token revocation:                                                |
|  Key:   revoked:jti:{jti_value}                                         |
|  Value: "1" (presence indicates revocation)                             |
|  TTL:   Matches remaining access token lifetime (max 15 min)            |
|                                                                          |
|  Refresh token / device session:                                         |
|  Key:   session:{deviceSessionId}                                        |
|  Value: Serialized session state (active / revoked)                     |
|  TTL:   Refresh token expiry (7 days)                                   |
+--------------------------------------------------------------------------+
```

### 10.2 Revocation Triggers

| Trigger | Access Tokens Revoked | Refresh Tokens Revoked | Scope |
| :--- | :--- | :--- | :--- |
| **User-initiated logout** | Current session JTI blocklisted | Current device session revoked | Single device session |
| **Logout all devices** | All active JTIs blocklisted | All device sessions revoked | All sessions for the user |
| **Password change** | All active JTIs blocklisted | All device sessions revoked | All sessions for the user |
| **Email change** | All active JTIs blocklisted | All device sessions revoked | All sessions for the user |
| **Account suspension** | All active JTIs blocklisted | All device sessions revoked | All sessions for the user |
| **Refresh token reuse detected** | Current family JTIs blocklisted | Entire token family revoked | Specific session family |
| **Admin revocation** | All or targeted JTIs blocklisted | Selected sessions revoked | Administrator-defined scope |
| **Security incident** | All active JTIs blocklisted | All device sessions revoked | Organization-wide or platform-wide |

### 10.3 Revocation Latency

Access token revocation is **eventually consistent with a bounded maximum window of 15 minutes**. A revoked access token remains technically valid until its natural expiry. This window is an accepted trade-off for stateless performance.

For high-severity events (account suspension, security incidents), revocation is made effective immediately for refresh tokens. Any attempt to use an expired access token to refresh will be blocked at the refresh endpoint.

---

## 11. Multi-Tenant Authentication

### 11.1 Identity vs. Tenant Separation

DevFlow enforces a critical architectural separation: a user's **global identity** (`User`) is distinct from their **tenant membership** (`WorkspaceMembership`). This distinction has direct authentication consequences.

```
+----------------------------------------------------------------+
|                   IDENTITY LAYER (Global)                      |
|                                                                |
|   User (email: alice@example.com, id: usr_abc)                |
|   |-- One identity across the entire platform                 |
+-----------------------------+----------------------------------+
                              |
                              | Has memberships in multiple orgs
                              |
             +----------------+----------------+
             |                |                |
             v                v                v
+------------------+  +------------------+  +------------------+
|  Org: Acme Corp  |  |  Org: Startup X  |  |  Org: Personal   |
|  Role: OWNER     |  |  Role: MEMBER    |  |  Role: OWNER     |
+------------------+  +------------------+  +------------------+
      TENANT A               TENANT B              TENANT C
```

A user's JWT always encodes a **single active organization context** (`orgId` claim). Switching between organizations requires a new token issuance — not re-authentication, but a dedicated token refresh that changes the embedded `orgId` and `roles` claims.

### 11.2 Organization Membership Validation

When a protected resource is accessed:
1. The Security Filter extracts `userId` and `orgId` from the JWT.
2. The `TenantResolutionFilter` validates that an active `WorkspaceMembership` record exists for `(userId, orgId)` with a non-suspended status.
3. The database connection pool is configured to operate within the `orgId`-mapped PostgreSQL schema for the duration of the request.
4. All domain module queries are automatically scoped to the active tenant.

### 11.3 Tenant Isolation Guarantee

An authenticated user with a valid JWT for Organization A **cannot access any resource belonging to Organization B**, even if they are simultaneously a member of Organization B. The `orgId` in the JWT is the hard tenant boundary enforced at every layer of the stack. Accessing resources in a different organization requires re-issuing a token for that organization's context.

---

## 12. CLI Authentication

### 12.1 Challenge: No Browser, No Redirect

The CLI operates in terminal environments that may not have a browser readily available, and cannot host an HTTP callback server reliably across all operating systems and network configurations. Standard OAuth redirect flows are not appropriate for CLI authentication.

### 12.2 OAuth 2.0 Device Authorization Flow

DevFlow's CLI implements the **OAuth 2.0 Device Authorization Grant** (RFC 8628).

```
 Developer Terminal       DevFlow Backend          DevFlow Web App (Browser)
        |                       |                              |
        |-- devflow auth login ->|                              |
        |                       |-- Generate device_code       |
        |                       |   + user_code (expires 5 min)|
        |<-- 200 OK ------------|                              |
        |                       |                              |
        | +----------------------------------------------------+
        | | Open browser: https://devflow.ai/activate          |
        | | Enter code:   ABCD-EFGH                            |
        | +----------------------------------------------------+
        |                       |                              |
        |-- POST /api/v1/auth/  |    Developer opens URL       |
        |   device/token ------>|    in browser                |
        |   {device_code}       |                              |
        |<-- 202 (pending) -----|                              |
        |                       |                              |
        |  [Polls every 5s]     |<-- User enters user_code ----|
        |                       |    and approves in browser   |
        |                       |-- Mark device_code approved  |
        |                       |                              |
        |-- POST /api/v1/auth/  |                              |
        |   device/token ------>|                              |
        |                       |-- Generate Access Token      |
        |                       |-- Generate Refresh Token     |
        |<-- 200 OK ------------|                              |
        |   {accessToken,       |                              |
        |    refreshToken}      |                              |
        |                       |                              |
        |-- Persist to OS       |                              |
        |   credential store    |                              |
```

### 12.3 CLI Token Storage

Tokens acquired through the Device Authorization Flow are stored in the operating system's native credential management facility. This provides encryption at rest without requiring the CLI to implement its own secrets management layer.

### 12.4 Future: Personal Access Tokens (PATs)

For non-interactive CI/CD contexts (e.g., GitHub Actions workflows, automated scripts) where the Device Authorization Flow is impractical, DevFlow will introduce **Personal Access Tokens**:
- Generated by authenticated users via the web application.
- Scoped to a specific organization and selected permission sets.
- Issued with configurable expiry dates (30, 90, 365 days, or no expiry with explicit opt-in).
- Revocable individually from the security settings dashboard.
- Validated through the same authentication filter chain via a dedicated token type check.

---

## 13. VS Code Extension Authentication

### 13.1 Design Constraint

The VS Code Extension must not implement its own login flow. It must delegate authentication entirely to the user's established web browser session. This prevents a fragmented authentication UX where the user maintains separate identities in their browser and in their editor.

### 13.2 Browser-Delegated Authentication Flow

```
 VS Code Extension         DevFlow Backend            Browser (Next.js)
        |                       |                              |
        |-- User clicks         |                              |
        |   "Sign In" in        |                              |
        |   extension panel     |                              |
        |                       |                              |
        |-- Generate state +    |                              |
        |   local callback URI  |                              |
        |-- Open browser to:    |                              |
        |   /auth/vscode-link?  |                              |
        |   state={state} ---------------------------------->  |
        |                       |    User logs in (if needed) |
        |                       |                              |
        |                       |<-- POST /api/v1/auth/        |
        |                       |    vscode/authorize          |
        |                       |    (state verified)          |
        |                       |                              |
        |                       |-- Issue short-lived          |
        |                       |   exchange code (1 min TTL)  |
        |<--- Redirect to vscode://devflow.app/auth            |
        |     ?code={exchange_code}                            |
        |                       |                              |
        |-- POST /api/v1/auth/  |                              |
        |   vscode/exchange --->|                              |
        |   {code, state}       |                              |
        |                       |-- Validate exchange code     |
        |                       |-- Issue Access + Refresh     |
        |                       |   Tokens                     |
        |<-- 200 OK ------------|                              |
        |   {accessToken,       |                              |
        |    refreshToken}      |                              |
        |                       |                              |
        |-- Persist to VS Code  |                              |
        |   SecretStorage API   |                              |
```

### 13.3 Secure Token Storage

The extension stores all tokens exclusively through the VS Code `SecretStorage` API, which encrypts secrets at rest using the OS keychain on macOS, Windows Credential Manager on Windows, and `libsecret` on Linux. Tokens are never written to `globalState`, `workspaceState`, or any file on disk.

### 13.4 Token Refresh in the Extension

The extension maintains an in-memory token refresh loop:
- Proactively refreshes the access token 2 minutes before its expiry.
- On refresh failure (e.g., revoked refresh token), clears stored credentials and presents a re-authentication prompt within the extension sidebar.

---

## 14. Security Considerations

### 14.1 Transport Security (HTTPS / TLS)

All communication between clients and the DevFlow backend is strictly over **TLS 1.2 minimum, TLS 1.3 preferred**. HTTP connections are refused or redirected to HTTPS at the load balancer layer. Strict-Transport-Security (HSTS) headers with `max-age=31536000; includeSubDomains; preload` are set on all responses to enforce this policy in browsers.

### 14.2 Replay Attack Protection

Each JWT contains a unique `jti` claim. The Security Filter validates that a `jti` has not already been processed within its validity window (using a short-lived Redis entry) for operations where replay risk is particularly high. Access tokens' short 15-minute lifetime naturally limits the replay window.

### 14.3 Brute-Force Protection

Login endpoints are protected by a multi-layer rate limiting strategy:

| Layer | Scope | Strategy |
| :--- | :--- | :--- |
| **IP-level** | Per IP address | Fixed window; lockout on excessive failures |
| **Account-level** | Per email address | Progressive delay + account lockout after N consecutive failures |
| **CAPTCHA** | After 3+ failures | Challenge before allowing further attempts |

Account lockouts notify the legitimate account owner via email, including the IP address that triggered the lockout. Lockout duration follows an exponential backoff model.

### 14.4 Credential Stuffing Mitigation

Beyond rate limiting, DevFlow applies:
- **Compromised password detection:** Passwords are checked against a local hash prefix list derived from known breach datasets (using a k-Anonymity model — no plaintext passwords leave the server).
- **Anomaly detection on login geography:** Logins from new countries or dramatically different IP geolocation ranges trigger a verification step before granting access.

### 14.5 Token Theft Mitigation

- **Short access token lifetime (15 min):** Limits the damage window of a stolen access token.
- **Refresh token rotation:** Reuse of a consumed refresh token triggers immediate full session revocation.
- **Secure transport only:** Tokens are never transmitted over non-TLS channels.
- **HttpOnly cookies for web refresh tokens:** JavaScript cannot read the refresh token, eliminating XSS as an extraction vector for the most sensitive credential.

### 14.6 CSRF Considerations

- **Access token in Authorization header:** The API's primary authentication mechanism uses the `Authorization: Bearer` header, which is not automatically included by browsers in cross-site requests. Standard CSRF attacks do not apply to this pattern.
- **Cookie-based refresh tokens:** The refresh token cookie is `SameSite=Strict`, preventing it from being sent in cross-origin requests. The refresh endpoint additionally validates a CSRF token bound to the user's browser session for the cookie-based path.

### 14.7 XSS Considerations

- The refresh token is stored in an `HttpOnly` cookie — it is invisible to any JavaScript, including injected malicious scripts.
- The access token is held in JavaScript memory (not `localStorage` or `sessionStorage`) on the web client. It is lost on page refresh, at which point the HttpOnly refresh token cookie is used to silently re-issue it.
- All API responses set `Content-Type: application/json` explicitly, preventing browser MIME-sniffing attacks.

### 14.8 Rate Limiting on Authentication Endpoints

Authentication-specific endpoints apply stricter limits than standard API endpoints:
- `/api/v1/auth/register`: Limits per IP, with email domain analysis to block disposable address registrations.
- `/api/v1/auth/forgot-password`: Strict per-IP rate limit to prevent mass reset link flooding.
- `/api/v1/auth/refresh`: Per-refresh-token limit; a single token cannot be exchanged more than once (enforced by the rotation model).

### 14.9 Audit Logging

Every authentication event is written to an immutable audit log:

| Event | Logged Data |
| :--- | :--- |
| Successful login | userId, orgId, IP, userAgent, timestamp, method |
| Failed login | email attempted, IP, userAgent, timestamp, failure reason |
| Token refresh | userId, deviceSessionId, IP, timestamp |
| Password reset requested | email, IP, timestamp |
| Password changed | userId, IP, timestamp |
| OAuth linked | userId, provider, timestamp |
| Session revoked | userId, deviceSessionId, revokedBy, reason, timestamp |
| Account locked | userId, IP, lockout duration, timestamp |

Audit logs are write-once records and are shipped to the centralized observability stack (Grafana Loki) for long-term retention and compliance reporting.

---

## 15. Future Evolution

The current authentication architecture is designed to accommodate the following capabilities without fundamental structural changes:

### 15.1 Multi-Factor Authentication (MFA)

The login flow has a deliberate architectural gap after primary credential verification: a "factors pending" state. This state will be used to require a secondary factor before token issuance. Planned factor types:
- **TOTP (Time-Based One-Time Passwords):** Compatible with standard authenticator apps (Google Authenticator, Authy).
- **SMS OTP:** Fallback mechanism (lower security; usage discouraged for engineering teams).
- **Hardware Security Keys (FIDO2/WebAuthn):** See Passkeys section below.

### 15.2 Passkeys (WebAuthn)

The WebAuthn / FIDO2 standard enables passwordless authentication using device biometrics (Face ID, Touch ID, Windows Hello) or hardware security keys. The `devflow-auth` module's authentication handler is architecturally capable of receiving and validating WebAuthn assertion responses in place of a password, producing the same downstream JWT issuance result.

### 15.3 Enterprise SSO (SAML 2.0 and OIDC)

The `OAuthProviderRegistry` architecture supports plugging in SAML Identity Provider (IdP) adapters and OIDC provider integrations. Enterprise organizations will configure their Okta, Azure Active Directory, or Ping Identity integration from the organization administration panel, directing all members' authentication through their corporate IdP.

### 15.4 Passwordless Authentication (Magic Links)

Passwordless email link authentication requires only:
1. User submits their email.
2. A signed, time-bound, single-use login token is sent via email.
3. Clicking the link validates the token and issues a DevFlow JWT.

This flow uses the same token validation infrastructure as password reset and can be offered as an opt-in authentication method without architectural changes.

### 15.5 SCIM Provisioning

For enterprise SSO customers, SCIM (System for Cross-domain Identity Management) allows the corporate IdP to automatically provision and deprovision user accounts and organization memberships in DevFlow as employees join or leave the company.

---

## 16. Architectural Principles & Key Design Decisions

| # | Principle | Rationale |
| :--- | :--- | :--- |
| **1** | **Authentication is a single Bounded Context.** The `devflow-auth` module is the sole owner of identity and session data. Other modules consume authentication results through a compiled public interface, never by querying the `auth` schema directly. | Prevents authentication logic from leaking into business domains and enables the auth module to evolve independently. |
| **2** | **Access tokens are short-lived by design.** The 15-minute access token lifetime is an intentional security trade-off against stateless performance. | Bounds the damage window of any credential interception to a narrow time frame. |
| **3** | **Refresh tokens are stateful by design.** Unlike access tokens, refresh tokens are registered server-side to enable revocation. | Provides reliable session termination capability that pure stateless JWTs cannot offer alone. |
| **4** | **Token rotation is non-negotiable.** Every refresh exchange consumes the presented token and issues a new one. Reuse of a consumed token is treated as a theft signal. | Provides automatic detection of credential theft from passive interception. |
| **5** | **Identity and tenant are separated.** A `User` exists independently of any `Organization`. JWT claims encode a single active tenant context. | Supports multi-organization membership cleanly and enables future corporate account federation without restructuring the identity model. |
| **6** | **The refresh token is never accessible to JavaScript in a web browser.** HttpOnly, Secure, SameSite=Strict cookies are the mandated storage mechanism. | Eliminates XSS-based refresh token theft, which is the highest-impact credential theft vector for web clients. |
| **7** | **OAuth is used for authentication; repository scopes are requested separately.** GitHub OAuth login does not request `repo` scope at login time. | Follows the principle of least privilege. Users are not asked for more permissions than necessary during authentication. |
| **8** | **Every authentication event is audited.** Successful logins, failures, token refreshes, and revocations are written to an immutable audit log. | Provides the foundation for compliance reporting, security incident investigation, and anomaly detection. |
| **9** | **Revocation is bounded, not instant for access tokens.** The maximum revocation lag for access tokens is 15 minutes. For refresh tokens, revocation is immediate. | This is an accepted architectural trade-off. High-severity revocation enforces the boundary at the refresh token layer, not the access token layer. |
| **10** | **Every client surface uses the same token standard.** Web, CLI, VS Code Extension, and future Mobile all receive RS256-signed JWTs and opaque refresh tokens. | Enables a single authentication verification path in the Security Filter Chain regardless of client origin. |

---

*This document is the official authentication architecture specification for DevFlow. Changes to the authentication design require review and approval from the Architecture Review Board (ARB) and the Security Review function.*
