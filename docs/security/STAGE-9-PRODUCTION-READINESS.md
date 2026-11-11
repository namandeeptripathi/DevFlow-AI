# Stage 9: Final Hardening & Production Readiness

> **Status:** Complete — Validated & Hardened (Stage 9 Final Assessment)  
> **Date:** 2026-11-11  
> **Author:** DevFlow Core Security & Platform Engineering  
> **Applies to:** `backend/app`, `backend/devflow-auth`, Flyway `V1`–`V16`, production configuration  
> **Policy Source of Truth:** [AUTHORIZATION_MODEL.md](AUTHORIZATION_MODEL.md) (unmodified) & [ADR-001](ADR-001-AUTHORIZATION-POLICY-RESOLUTION.md)  
> **Preceding Stages:** [Stage 4](STAGE-4-PERSISTENCE-ARCHITECTURE.md), [Stage 5](STAGE-5-APPLICATION-INTEGRATION.md), [Stage 6](STAGE-6-JWT-CACHE-ROLE-MANAGEMENT.md), [Stage 7](STAGE-7-CUSTOM-ROLES-GOVERNANCE.md), [Stage 8](STAGE-8-AUDIT-SECURITY-MONITORING.md)

---

## 1. Executive Summary

Stage 9 conducted a comprehensive production-readiness audit across DevFlow's centralized authorization system (`backend/devflow-auth` and `backend/app`), validating Areas **A–H**:

- **A. End-to-End Authorization Testing**
- **B. Multi-Tenant Isolation & Anti-Enumeration**
- **C. Concurrency & Race Conditions**
- **D. Authorization Cache & Redis Reliability**
- **E. Audit Integrity & Security Monitoring Reliability**
- **F. Database Migration & Recovery**
- **G. Performance & Load Benchmarking**
- **H. Production Configuration & Deployment Readiness**

No architectural redesigns or changes to `docs/security/AUTHORIZATION_MODEL.md` were introduced. Every defect uncovered during validation was fixed cleanly with targeted hardening and regression tests.

**Overall Readiness Verdict:** Ready for staging/production deployment once production environment secrets and managed PostgreSQL 17 + TLS Redis endpoints are provisioned, with one explicitly noted test-environment constraint (`PARTIAL` on live Redis daemon benchmarking; Redis failover, recovery replay, and cursor `SCAN` were verified against mocked `StringRedisTemplate` plus live connection-refused fallback paths).

---

## 2. Validation Summary (Areas A–H)

| Area | Domain | Status | Evidence & Notes |
| :--- | :--- | :--- | :--- |
| **A** | End-to-End Authorization | **PASS** | Validated JWT auth separation, refresh token rejection on API endpoints, header spoofing resistance (`X-User-Id`, `X-Roles`, `X-Organization-Id`), canonical roles (10), custom roles (48 permissions), ownership (`TeamRole.LEAD`), org-to-project inheritance, composite AND semantics, expired assignments, and default-deny (`Stage9SecurityAndIsolationIntegrationTest`). |
| **B** | Multi-Tenant Isolation & Security | **PASS** | Verified uniform `403 Forbidden` (never `404`) for unauthorized cross-tenant `custom-roles` probes, `MEMBERSHIP_INACTIVE` evaluation before project boundary checks in `AuthorizationEvaluator`, monitoring controller pagination bounds & denial auditing (`OrganizationSecurityMonitoringController`). |
| **C** | Concurrency & Race Conditions | **PASS** | Verified under concurrent executors against PostgreSQL: `PESSIMISTIC_WRITE` (`OrganizationRepository.findByIdForUpdate`) serializes simultaneous owner removals & owner role revocations so sole active owner invariant always holds; PostgreSQL advisory locks (`pg_advisory_xact_lock`) serialize monotonic audit sequences and alert deduplication (`Stage9ConcurrencyAndAuditIntegrityIntegrationTest`). |
| **D** | Authorization Cache & Redis Reliability | **PARTIAL** | All hardening & recovery logic verified (`RedisAuthorizationCacheHardeningTest`, `Stage9SecurityAndIsolationIntegrationTest`), including `SCAN` cursor eviction, bounded L1 cache, after-commit re-eviction, invitee cache eviction (`OrganizationInvitationService.acceptInvitation`), no stale local reads when Redis is healthy, and outage eviction replay on recovery. **Constraint:** Standalone Redis daemon at `localhost:6370` was offline in the test environment, so live networked Redis socket throughput was not benchmarked directly. |
| **E** | Audit Integrity & Monitoring Reliability | **PASS** | Verified gapless concurrent hash chaining, idempotent `eventId` retries, PostgreSQL append-only triggers blocking `UPDATE`/`DELETE` on `authorization_audit_events` and `audit_integrity_checkpoints`, genesis/wipe/tail-truncation/rewrite detection, constant-time HMAC verification, sanitized `X-Request-ID`, and PostgreSQL-safe Criteria/split queries for audit & security monitoring (`Stage9ConcurrencyAndAuditIntegrityIntegrationTest`). |
| **F** | Database Migration & Recovery | **PASS** | Validated clean Flyway schema history `V1`–`V16` on PostgreSQL 17 (`failed = 0`), canonical seed parity (10 roles / 48 permissions), and new unique partial sequence/checkpoint indexes (`V16__harden_audit_and_concurrency_constraints.sql`). Pre-existing migrations `V1`–`V15` were left untouched. |
| **G** | Performance & Load Testing | **PASS** | Measured pure in-process evaluator throughput (117k–166k ops/s, <0.01 ms mean) and durable PostgreSQL hash-chained audit writes (~336 ops/s, ~2.98 ms mean) via `Stage9AuthorizationBenchmarkTest`. See Section 4 for full caveat and latency breakdown. |
| **H** | Production Deployment Readiness | **PASS** | Added fail-fast `@PostConstruct` startup validator (`ConfigurationValidator`, 7/7 tests), disabled in-memory audit buffer in `prod` (`in-memory-buffer-size: 0`), enforced strict issuer & token type JWT checks, and verified `bootJar` packaging (`devflow-backend-0.0.1-SNAPSHOT.jar`). |

---

## 3. Defects Identified & Remediated

### 3.1 Refresh Tokens Could Authenticate Protected Endpoints & Permissive JWT Validation
- **Finding:** `JwtTokenProvider` and `JwtAuthenticationFilter` did not distinguish refresh tokens from access tokens via a dedicated claim or block refresh tokens during API request filter authentication; `validateToken` did not explicitly enforce expected `issuer`.
- **Fix:**
  - Added `TOKEN_TYPE_CLAIM = "token_type"` (`"access"` / `"refresh"`) in [`JwtTokenProvider.java`](../../backend/app/src/main/java/com/devflow/security/jwt/JwtTokenProvider.java) alongside `requireIssuer(jwtProperties.getIssuer())` and `isRefreshToken(String)`.
  - Updated [`JwtAuthenticationFilter.java`](../../backend/app/src/main/java/com/devflow/security/jwt/JwtAuthenticationFilter.java) to reject tokens where `jwtTokenProvider.isRefreshToken(jwt)` is true.
- **Verification:** `Stage9SecurityAndIsolationIntegrationTest#rejectsRefreshJwtAndIgnoresSpoofedHeaders`.

### 3.2 PostgreSQL Hibernate 6 `PSQLException` on Nullable JPQL Filter Parameters
- **Finding:** Executing repository queries with `(:param IS NULL OR ...)` against PostgreSQL 17 + Hibernate 6 throws `PSQLException: ERROR: could not determine data type of parameter` whenever an optional `Instant`, enum, or nullable filter (`eventType`, `status`, `severity`, `fromTime`, `toTime`) is bound as `null`. Crucially, live security detection rules `RapidBulkRoleModificationsRule` and `ProjectScopeViolationRule` call `AuthorizationAuditEventRepository.findRecentEvidenceEventIds(..., null, ...)` in production.
- **Fix:**
  - Converted `findTenantSecurityAlerts` ([`SecurityAlertRepository.java`](../../backend/app/src/main/java/com/devflow/security/monitoring/repository/SecurityAlertRepository.java)), `findTenantSecurityEvents` ([`SecurityEventRepository.java`](../../backend/app/src/main/java/com/devflow/security/monitoring/repository/SecurityEventRepository.java)), and `findTenantAuditLogs` ([`AuthorizationAuditEventRepository.java`](../../backend/app/src/main/java/com/devflow/audit/repository/AuthorizationAuditEventRepository.java)) to `JpaSpecificationExecutor`-backed `default` methods (with count-query-safe sorting guards).
  - Split `findRecentEvidenceEventIds` into `findRecentEvidenceEventIdsAnyType` and `findRecentEvidenceEventIdsByType` dispatched by a `default` interface method.
  - Simplified [`AuthorizationAuditService.java`](../../backend/app/src/main/java/com/devflow/audit/service/AuthorizationAuditService.java), [`SecurityMonitoringService.java`](../../backend/app/src/main/java/com/devflow/security/monitoring/service/SecurityMonitoringService.java), and [`SecurityAlertService.java`](../../backend/app/src/main/java/com/devflow/security/monitoring/service/SecurityAlertService.java) to delegate directly to those repository methods without duplicating predicates.
- **Verification:** Live PostgreSQL queries with all-null optional parameters verified in `Stage9ConcurrencyAndAuditIntegrityIntegrationTest` alongside all existing monitoring unit tests.

### 3.3 Cross-Tenant Enumeration & Monitoring Endpoint Gaps
- **Finding:**
  1. `CustomRoleService` could evaluate resource existence before verifying tenant admin/owner authorization on `listCustomRoles`, `updateCustomRole`, and `deleteCustomRole`.
  2. `AuthorizationEvaluator` evaluated project-boundary membership before checking whether the principal held active organization membership, allowing non-members to potentially observe `PROJECT_NOT_IN_ORGANIZATION` vs `MEMBERSHIP_INACTIVE` differences.
  3. `OrganizationSecurityMonitoringController` lacked explicit upper page-size bounds and explicit denial audit emission on monitoring access denials.
  4. `OrganizationInvitationService.acceptInvitation` did not evict negative authorization cache entries for the newly accepted invitee.
- **Fix:**
  - Reordered [`CustomRoleService.java`](../../backend/app/src/main/java/com/devflow/role/service/CustomRoleService.java) to run `verifyCallerIsAdminOrOwner` before resource lookups.
  - Reordered [`AuthorizationEvaluator.java`](../../backend/devflow-auth/src/main/java/com/devflow/modules/auth/evaluator/AuthorizationEvaluator.java) to check `membershipValidator.isActiveMember` prior to project-in-organization boundary validation.
  - Added pagination clamping (`MAX_PAGE_SIZE = 100`, `page >= 0`) and fail-safe `AUTHORIZATION_DENIED` recording in [`OrganizationSecurityMonitoringController.java`](../../backend/app/src/main/java/com/devflow/security/monitoring/controller/OrganizationSecurityMonitoringController.java).
  - Wired optional `AuthorizationCacheService` into [`OrganizationInvitationService.java`](../../backend/app/src/main/java/com/devflow/organization/service/OrganizationInvitationService.java) to call `evictUser(orgId, authenticatedUserId)` upon acceptance.

### 3.4 Concurrency Races: Owner Removal, Role Mutations, Audit Chain & Alert Deduplication
- **Finding:** Simultaneous transactions could race past active owner counts, duplicate sequence numbers in `authorization_audit_events`, or create duplicate `OPEN` `security_alerts` under concurrent bursts.
- **Fix:**
  - Added `@Lock(LockModeType.PESSIMISTIC_WRITE)` `OrganizationRepository.findByIdForUpdate` ([`OrganizationRepository.java`](../../backend/app/src/main/java/com/devflow/organization/repository/OrganizationRepository.java)) and acquired it at the start of state-mutating methods in [`RoleAssignmentService.java`](../../backend/app/src/main/java/com/devflow/role/service/RoleAssignmentService.java) and [`OrganizationMembershipService.java`](../../backend/app/src/main/java/com/devflow/organization/service/OrganizationMembershipService.java).
  - Added PostgreSQL `pg_advisory_xact_lock(hashtextextended(:lockName, 0))` + active-transaction `entityManager.flush()` in [`AuthorizationAuditService.java`](../../backend/app/src/main/java/com/devflow/audit/service/AuthorizationAuditService.java) (with idempotent `findByEventId` check and JVM fallback lock when `entityManager == null`) and in [`SecurityAlertService.java`](../../backend/app/src/main/java/com/devflow/security/monitoring/service/SecurityAlertService.java).
  - Added Flyway migration [`V16__harden_audit_and_concurrency_constraints.sql`](../../backend/app/src/main/resources/db/migration/V16__harden_audit_and_concurrency_constraints.sql) creating partial unique indexes `uk_authz_audit_org_seq`, `uk_authz_audit_platform_seq`, and `uk_audit_checkpoint_org_range`.

### 3.5 Redis Cache Split-Brain, Blocking `KEYS`, Unbounded L1 Heap & Outage Recovery
- **Finding:** Under Redis outages, healthy misses fell back to stale local caches, pattern invalidations used blocking Redis `KEYS`, `localCache` was unbounded, transactional evictions could be re-populated before TX commit, and evictions missed during an outage were lost when Redis recovered.
- **Fix:**
  - Added `maxLocalEntries = 10_000` in [`AuthorizationCacheProperties.java`](../../backend/app/src/main/java/com/devflow/security/authorization/cache/AuthorizationCacheProperties.java).
  - Hardened [`RedisAuthorizationCacheService.java`](../../backend/app/src/main/java/com/devflow/security/authorization/cache/RedisAuthorizationCacheService.java): eliminated local fallback on healthy Redis misses, prevented split-brain L1 mirroring on healthy Redis writes (`localCache.remove(key)`), replaced blocking `KEYS` with cursor `ScanOptions` `SCAN` (`SCAN_BATCH_SIZE = 500`) + glob matcher, queued failed Redis evictions/patterns (`MAX_PENDING_EVICTIONS = 5_000` with full-flush escalation) replayed on recovery before Redis reads/writes, and registered `TransactionSynchronization.afterCommit()` re-eviction.

### 3.6 Audit Cryptographic Verification & Correlation ID Column Safety
- **Finding:** Full-chain integrity checks did not assert starting at sequence `1` and `GENESIS_HASH`, wiped ledgers could appear empty-valid despite existing checkpoints, tail truncation after checkpoints was unchecked, and oversized/control-char `X-Request-ID` headers risked log injection or column overflow failing closed admin mutations.
- **Fix:**
  - Hardened [`AuditIntegrityService.java`](../../backend/app/src/main/java/com/devflow/audit/service/AuditIntegrityService.java) to enforce genesis linkage (`sequence = 1`, `GENESIS_HASH`) on full checks (`from == null && to == null`), detect wiped ledgers via `findTopByOrganizationIdOrderByEndSequenceDesc`, detect tail truncation beyond latest checkpoints, verify `recordCount` and `cumulativeHash`, and compare HMACs in constant time via `MessageDigest.isEqual`.
  - Added `TraceContext.sanitize` (`MAX_CORRELATION_ID_LENGTH = 100`, stripping `Character.isISOControl`) in [`TraceContext.java`](../../backend/app/src/main/java/com/devflow/util/TraceContext.java), plus pre-hash varchar column clamping in `AuthorizationAuditService`.

### 3.7 Production Startup Safety & Heap Bounds
- **Finding:** Missing startup guardrails for default development secrets in `prod`, wildcard CORS with credentials, exposed SpringDoc in `prod`, and unbounded diagnostic ring buffer in `LoggingAuthorizationAuditRecorder`.
- **Fix:**
  - Created [`ConfigurationValidator.java`](../../backend/app/src/main/java/com/devflow/config/ConfigurationValidator.java) (`@PostConstruct` validation across all profiles + strict `prod` enforcement).
  - Bounded [`LoggingAuthorizationAuditRecorder.java`](../../backend/app/src/main/java/com/devflow/audit/LoggingAuthorizationAuditRecorder.java) via `devflow.security.audit.in-memory-buffer-size` (default `1000` in [`application.yml`](../../backend/app/src/main/resources/application.yml), `0` in [`application-prod.yml`](../../backend/app/src/main/resources/application-prod.yml)).

---

## 4. Performance Benchmark Results

Measured in [`Stage9AuthorizationBenchmarkTest.java`](../../backend/app/src/test/java/com/devflow/performance/Stage9AuthorizationBenchmarkTest.java) against local PostgreSQL 17 during the full `cleanTest test build bootJar` run:

| Benchmark | Iterations | Throughput (ops/s) | Mean (ms) | p50 (ms) | p95 (ms) | p99 (ms) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| `PureEvaluator` Organization Authz (in-process) | 5,000 | 138,890.0 | 0.0072 | 0.0059 | 0.0083 | 0.0175 |
| `PureEvaluator` Project Authz (in-process) | 5,000 | 117,167.4 | 0.0085 | 0.0080 | 0.0108 | 0.0159 |
| `PureEvaluator` Custom Role Authz (in-process) | 5,000 | 166,686.1 | 0.0060 | 0.0058 | 0.0074 | 0.0094 |
| Full `AuthApi` Organization Cache Hit * | 100 | 189.1 | 5.2871 | 5.2265 | 6.2176 | 7.0659 |
| Full `AuthApi` Project Cache Hit * | 100 | 133.8 | 7.4746 | 6.7892 | 10.8255 | 13.0420 |
| Full `AuthApi` Custom Role Cache Hit * | 100 | 173.1 | 5.7757 | 5.3518 | 8.3868 | 10.3496 |
| Full `AuthApi` Organization Cache Miss + DB | 50 | 77.8 | 12.8506 | 11.4988 | 22.9639 | 32.2958 |
| Role Grant + Revoke Cycle (Lock + Audit + DB) | 25 | 33.7 | 29.6770 | 29.7169 | 35.9858 | 36.1541 |
| Durable PostgreSQL Audit Persist + HashChain | 100 | 335.9 | 2.9760 | 2.7935 | 4.4010 | 5.8398 |

> **\*Benchmark Environment Note:** In the test profile ([`application-test.yml`](../../backend/app/src/main/resources/application-test.yml)), Redis points to `localhost:6370` where no Redis server was running. Therefore, each `Full AuthApi ... Cache Hit` operation incurred a TCP connection-refusal round-trip on Redis reads before hitting the local L1 fallback cache. Against a live healthy local/VPC Redis instance, sub-millisecond cache hit reads are expected instead of socket-error fallback overhead.

---

## 5. Verification & Build Results

Executed `cd backend && ./gradlew cleanTest test build bootJar`:

| Check / Suite | Outcome | Observed Result |
| :--- | :--- | :--- |
| `:devflow-auth:test` | **PASS** | **208 / 208** tests passed across **15** test suites (0 skipped, 0 failures, 0 errors) |
| `:app:test` (incl. 23 new Stage 9 tests) | **PASS** | **753 / 753** tests passed across **192** test suites (0 skipped, 0 failures, 0 errors) |
| Combined Backend Test Suite | **PASS** | **961 / 961** tests passed (0 skipped, 0 failures, 0 errors) |
| `./gradlew build` | **PASS** | `BUILD SUCCESSFUL` across `:app` and `:devflow-auth` |
| `./gradlew bootJar` | **PASS** | Built `backend/app/build/libs/devflow-backend-0.0.1-SNAPSHOT.jar` (73M) |

### New Stage 9 Test Suites Added
- [`ConfigurationValidatorTest.java`](../../backend/app/src/test/java/com/devflow/config/ConfigurationValidatorTest.java) (7 tests)
- [`RedisAuthorizationCacheHardeningTest.java`](../../backend/app/src/test/java/com/devflow/security/authorization/cache/RedisAuthorizationCacheHardeningTest.java) (5 tests)
- [`Stage9SecurityAndIsolationIntegrationTest.java`](../../backend/app/src/test/java/com/devflow/security/Stage9SecurityAndIsolationIntegrationTest.java) (4 integration tests)
- [`Stage9ConcurrencyAndAuditIntegrityIntegrationTest.java`](../../backend/app/src/test/java/com/devflow/security/Stage9ConcurrencyAndAuditIntegrityIntegrationTest.java) (6 integration tests)
- [`Stage9AuthorizationBenchmarkTest.java`](../../backend/app/src/test/java/com/devflow/performance/Stage9AuthorizationBenchmarkTest.java) (1 benchmark suite)

---

## 6. Production Deployment Configuration & Environment Variables

When deploying with `SPRING_PROFILES_ACTIVE=prod` ([`application-prod.yml`](../../backend/app/src/main/resources/application-prod.yml) & [`application.yml`](../../backend/app/src/main/resources/application.yml)), supply all required runtime variables via your orchestrator or secrets manager (never commit real secrets):

### Required Secrets & Core Infrastructure Variables

| Environment Variable | Property Mapped | Requirement & Production Constraint |
| :--- | :--- | :--- |
| `SPRING_PROFILES_ACTIVE` | `spring.profiles.active` | Must be `prod`. |
| `DEVFLOW_DB_URL` | `spring.datasource.url` | PostgreSQL JDBC URL (with SSL/TLS in prod). |
| `DEVFLOW_DB_USERNAME` | `spring.datasource.username` | Least-privileged PostgreSQL application role. |
| `DEVFLOW_DB_PASSWORD` | `spring.datasource.password` | Secret DB credential. |
| `DEVFLOW_REDIS_HOST` | `spring.data.redis.host` | Managed Redis host (`spring.data.redis.ssl.enabled: true` in `prod`). |
| `DEVFLOW_REDIS_PORT` | `spring.data.redis.port` | Defaults to `6379` in `prod`. |
| `DEVFLOW_REDIS_PASSWORD` | `spring.data.redis.password` | Redis ACL / AUTH credential. |
| `DEVFLOW_JWT_SECRET` | `devflow.security.jwt.secret` | **Required by `ConfigurationValidator`:** non-default secret, >= 64 UTF-8 bytes (512 bits for HS512). |
| `DEVFLOW_AUDIT_INTEGRITY_KEY` | `devflow.security.audit.integrity-key` | **Required by `ConfigurationValidator`:** non-default HMAC secret, >= 32 UTF-8 bytes for SHA-256 checkpoint signing. |
| `DEVFLOW_SECURITY_CORS_ALLOWED_ORIGINS` | `devflow.security.cors.allowed-origins` | Explicit comma-separated HTTPS origin list (wildcards `*` and blank entries are rejected). |

### Optional Operational & Hardening Settings

| Variable / Property | Default (`prod`) | Notes |
| :--- | :--- | :--- |
| `PORT` | `8080` | HTTP server port (`server.forward-headers-strategy: native`). |
| `MANAGEMENT_PORT` | `8081` | Restricted Actuator port exposing only `health,info,metrics,prometheus`. Keep internal/non-public. |
| `DEVFLOW_AUDIT_BUFFER_SIZE` | `0` (`application-prod.yml`) | Disables in-memory audit ring buffer in `prod` to bound JVM heap. |
| `DEVFLOW_AVATAR_UPLOAD_DIR` / `BASE_URL` / `MAX_SIZE_BYTES` | `./uploads/avatars` / local / `5242880` | Point upload dir/CDN URL to persistent volume / object storage CDN in prod. |
| `springdoc.api-docs.enabled` / `swagger-ui.enabled` | `false` (`application-prod.yml`) | Enforced `false` by `ConfigurationValidator` when `prod` profile is active. |

---

## 7. Pre-Release Production Checklist

- [ ] Provision PostgreSQL 17 with automated encrypted backups and run Flyway migrations (`V1`–`V16`) under `validate-on-migrate: true`.
- [ ] Verify DB triggers `trg_prevent_audit_log_update_delete` and `trg_prevent_checkpoint_update_delete` remain enabled and the runtime DB user lacks `SUPERUSER`/trigger-bypass privileges.
- [ ] Inject freshly generated high-entropy `DEVFLOW_JWT_SECRET` (>= 64 bytes) and `DEVFLOW_AUDIT_INTEGRITY_KEY` (>= 32 bytes) from secrets manager.
- [ ] Configure TLS-enabled Redis (`DEVFLOW_REDIS_HOST`, `DEVFLOW_REDIS_PASSWORD`) and monitor Redis reconnect/eviction-replay logs (`RedisAuthorizationCacheService`) in log aggregation.
- [ ] Configure explicit `DEVFLOW_SECURITY_CORS_ALLOWED_ORIGINS` for deployed web origins.
- [ ] Expose application port (`PORT`) via TLS-terminating reverse proxy and keep Actuator (`MANAGEMENT_PORT`) isolated on internal cluster network only.
- [ ] Re-run `Stage9AuthorizationBenchmarkTest` against a live TLS Redis endpoint in staging to establish baseline Redis cache hit latency SLOs.
