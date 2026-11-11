# Stage 9: Final Hardening & Production Readiness

> **Status:** Complete — Live Infrastructure Validated & Closed (Stage 9 Final Assessment)\
> **Date:** 2026-11-12\
> **Author:** DevFlow Core Security & Platform Engineering  
> **Applies to:** `backend/app`, `backend/devflow-auth`, Flyway `V1`–`V16`, production configuration  
> **Policy Source of Truth:** [AUTHORIZATION_MODEL.md](AUTHORIZATION_MODEL.md) (unmodified) & [ADR-001](ADR-001-AUTHORIZATION-POLICY-RESOLUTION.md)  
> **Preceding Stages:** [Stage 4](STAGE-4-PERSISTENCE-ARCHITECTURE.md), [Stage 5](STAGE-5-APPLICATION-INTEGRATION.md), [Stage 6](STAGE-6-JWT-CACHE-ROLE-MANAGEMENT.md), [Stage 7](STAGE-7-CUSTOM-ROLES-GOVERNANCE.md), [Stage 8](STAGE-8-AUDIT-SECURITY-MONITORING.md)

---

## 1. Executive Summary

Stage 9 conducted a comprehensive production-readiness audit and live infrastructure closure across DevFlow's centralized authorization system (`backend/devflow-auth` and `backend/app`), validating Areas **A–H** against real **PostgreSQL 17.11** and live **Redis 8.10.3**:

- **A. End-to-End Authorization Testing**
- **B. Multi-Tenant Isolation & Anti-Enumeration**
- **C. Concurrency & Race Conditions**
- **D. Authorization Cache & Redis Reliability**
- **E. Audit Integrity & Security Monitoring Reliability**
- **F. Database Migration & Recovery**
- **G. Performance & Load Benchmarking**
- **H. Production Configuration & Deployment Readiness**

No architectural redesigns or changes to `docs/security/AUTHORIZATION_MODEL.md` or historical migrations `V1`–`V15` were introduced. Every defect uncovered during code hardening and live-infrastructure validation (including `@Transactional` rollback loss of security-violation audits in `RoleAssignmentService` / `CustomRoleService`) was fixed cleanly with targeted hardening and regression tests.

**Overall Readiness Verdict:** Ready for staging/production deployment once managed production infrastructure (PostgreSQL 17, TLS-enabled Redis Cluster/Sentinel, TLS reverse proxy, and injected runtime secrets) is provisioned. All Areas **A–H** now **PASS** against live local PostgreSQL 17.11 and standalone Redis 8.10.3 (noting that multi-node HA failover and Redis TLS transport remain to be validated in the staging/production environment itself).

---

## 2. Validation Summary (Areas A–H)

| Area | Domain | Status | Evidence & Notes |
| :--- | :--- | :--- | :--- |
| **A** | End-to-End Authorization | **PASS** | Validated JWT auth separation, refresh token rejection on API endpoints, header spoofing resistance (`X-User-Id`, `X-Roles`, `X-Organization-Id`), canonical roles (10), custom roles (48 permissions), ownership (`TeamRole.LEAD`), org-to-project inheritance, composite AND semantics, expired assignments, and default-deny (`Stage9SecurityAndIsolationIntegrationTest`, plus live HTTP `RANDOM_PORT` verification in `Stage9EndToEndLiveSmokeIntegrationTest`). |
| **B** | Multi-Tenant Isolation & Security | **PASS** | Verified uniform `403 Forbidden` (never `404`) for unauthorized cross-tenant `custom-roles` probes, `MEMBERSHIP_INACTIVE` evaluation before project boundary checks in `AuthorizationEvaluator`, monitoring controller pagination bounds & denial auditing (`OrganizationSecurityMonitoringController`), and live cross-tenant anti-enumeration over HTTP (`Stage9EndToEndLiveSmokeIntegrationTest`). |
| **C** | Concurrency & Race Conditions | **PASS** | Verified under concurrent executors against PostgreSQL 17.11: `PESSIMISTIC_WRITE` (`OrganizationRepository.findByIdForUpdate`) serializes simultaneous owner removals & concurrent org role grant/revoke races (`concurrentRoleGrantAndRevokeMaintainConsistentState`); PostgreSQL advisory locks (`pg_advisory_xact_lock`) serialize monotonic audit sequences and alert deduplication; transaction rollbacks preserve prior hash-chain continuity without orphaned state (`Stage9ConcurrencyAndAuditIntegrityIntegrationTest`). |
| **D** | Authorization Cache & Redis Reliability | **PASS** | Verified all 15 live-Redis scenarios against real Redis 8.10.3 (`LiveRedisAuthorizationCacheIntegrationTest`, `LiveRedisOutageRecoveryIntegrationTest`, and unit suite `RedisAuthorizationCacheHardeningTest`): cache miss/population/hit, TTL expiry (`1s`), org grant/revoke & project revoke invalidation, custom-role permission update invalidation, membership removal clearing `roles:org`/`roles:proj`/`member`/`perms:custom:org`, tenant key isolation, cursor `SCAN` over `1,250` keys preserving sibling tenants, 6-thread concurrent read/invalidation stability, Redis outage (`SHUTDOWN NOSAVE`) fallback & stale RDB recovery eviction replay, and healthy Redis misses bypassing stale `localCache`. |
| **E** | Audit Integrity & Monitoring Reliability | **PASS** | Verified gapless concurrent hash chaining, idempotent `eventId` retries, PostgreSQL append-only triggers blocking `UPDATE`/`DELETE` on `authorization_audit_events` and `audit_integrity_checkpoints`, genesis/wipe/tail-truncation/rewrite detection, constant-time HMAC verification, sanitized `X-Request-ID`, PostgreSQL-safe Criteria/split queries, and `noRollbackFor` retention of denied escalation/delegation audit events & alert lifecycle (`Stage9ConcurrencyAndAuditIntegrityIntegrationTest`, `Stage9EndToEndLiveSmokeIntegrationTest`). |
| **F** | Database Migration & Recovery | **PASS** | Validated clean Flyway `V1`–`V16` installation and existing populated `V1`–`V15 -> V16` upgrade on isolated PostgreSQL 17.11 databases (`failed = 0`), service restart & recovery (`postgresql@17`), canonical seed parity (10 roles / 48 permissions), and partial unique constraints (`V16__harden_audit_and_concurrency_constraints.sql`) while keeping `V1`–`V15` unmodified. |
| **G** | Performance & Load Testing | **PASS** | Measured pure in-process evaluator throughput (68k–196k ops/s, <= 0.015 ms mean), full `AuthApi` with live Redis 8.10.3 + membership + synchronous audit persistence (~112–193 ops/s, ~5.18–8.93 ms mean), and durable PostgreSQL hash-chained audit writes (~365 ops/s, ~2.74 ms mean) via `Stage9AuthorizationBenchmarkTest`. See Section 4 for full metrics. |
| **H** | Production Deployment Readiness | **PASS** | Added fail-fast `@PostConstruct` startup validator (`ConfigurationValidator`, 9/9 tests including live `ApplicationContextRunner` verification of `application-prod.yml` bindings), disabled in-memory audit buffer in `prod` (`in-memory-buffer-size: 0`), enabled `spring.data.redis.ssl.enabled: true` in `prod`, enforced strict issuer & token type JWT checks, and verified `bootJar` packaging (`devflow-backend-0.0.1-SNAPSHOT.jar`, `73M`). |

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

### 3.8 Transactional Rollback Losing Security-Violation Audits & Partial Custom-Role Metadata Mutation
- **Finding (uncovered during live E2E HTTP validation):** Security denial paths inside `@Transactional` methods (`assignOrganizationRole` and `assignCustomRole` in `RoleAssignmentService`, plus `createCustomRole` and `updateCustomRole` in `CustomRoleService`) emitted `PRIVILEGED_ROLE_GRANT_ATTEMPTED` / `PERMISSION_DELEGATION_VIOLATION_ATTEMPTED` audit events and triggered security alerts before throwing `RoleAssignmentAccessDeniedException` or `CustomRoleAccessDeniedException`. Spring's default runtime-exception rollback rule reverted the enclosing database transaction, erasing the persistent audit rows and security alerts while still returning `403 Forbidden`. Additionally, `CustomRoleService.updateCustomRole` mutated managed `displayName`, `description`, and `active` entity fields prior to running `validatePermissions` and `validateDelegationAuthority`.
- **Fix:**
  - Configured `@Transactional(noRollbackFor = ...)` for `RoleAssignmentAccessDeniedException` on `assignOrganizationRole` and `{RoleAssignmentAccessDeniedException.class, CustomRoleAccessDeniedException.class}` on `assignCustomRole` in [`RoleAssignmentService.java`](../../backend/app/src/main/java/com/devflow/role/service/RoleAssignmentService.java), after verifying no mutating writes precede those denied throws.
  - Configured `@Transactional(noRollbackFor = CustomRoleAccessDeniedException.class)` on `createCustomRole` and `updateCustomRole` in [`CustomRoleService.java`](../../backend/app/src/main/java/com/devflow/role/service/CustomRoleService.java), and reordered `updateCustomRole` so `validatePermissions` and `validateDelegationAuthority` execute before any entity field setters run.
- **Verification:** `OrganizationRoleControllerIntegrationTest`, `CustomRoleControllerIntegrationTest`, `PermissionGovernanceServiceTest`, `Stage9SecurityAndIsolationIntegrationTest`, and `Stage9EndToEndLiveSmokeIntegrationTest` (confirming `403` escalation and delegation-violation requests retain persistent audits, open alerts, and clean HMAC chain integrity without partial metadata updates).

### 3.9 Operational Observation — Lettuce Reconnect Backoff After Redis Restart
- **Observation (during live `SHUTDOWN NOSAVE` & restart testing):** Immediately after a restarted Redis server begins answering TCP `PING -> PONG`, Lettuce's shared `StatefulRedisConnection` `ConnectionWatchdog` can remain in exponential reconnect backoff for ~1–4 seconds, causing initial `StringRedisTemplate` lookups to time out at `spring.data.redis.timeout` (`2000ms` default in `application.yml`; `1s` in recovery tests) and temporarily fall back to local cache/DB.
- **Mitigation & Verified Guarantee:** Once Lettuce reconnects, `RedisAuthorizationCacheService.replayPendingRedisEvictionsIfNeeded()` flushes queued outage evictions/keys before executing subsequent reads or writes, preventing stale RDB entries from being served and ensuring healthy Redis misses never fall back to stale `localCache` (`LiveRedisOutageRecoveryIntegrationTest`).

---

## 4. Performance Benchmark Results

Measured in [`Stage9AuthorizationBenchmarkTest.java`](../../backend/app/src/test/java/com/devflow/performance/Stage9AuthorizationBenchmarkTest.java) against live **PostgreSQL 17.11** (`localhost:5432`) and live **Redis 8.10.3** (`127.0.0.1:6370`) during the final `cleanTest test build bootJar` run:

| Benchmark | Iterations | Throughput (ops/s) | Mean (ms) | p50 (ms) | p95 (ms) | p99 (ms) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| `PureEvaluator` Organization Authz (in-process) | 5,000 | 94,317.0 | 0.0105 | 0.0066 | 0.0239 | 0.0329 |
| `PureEvaluator` Project Authz (in-process) | 5,000 | 68,085.4 | 0.0146 | 0.0079 | 0.0281 | 0.0413 |
| `PureEvaluator` Custom Role Authz (in-process) | 5,000 | 196,001.6 | 0.0051 | 0.0044 | 0.0069 | 0.0209 |
| `Full AuthApi` Organization Cache Hit (live Redis) | 100 | 112.0 | 8.9290 | 6.1606 | 7.5705 | 69.6340 |
| `Full AuthApi` Project Cache Hit (live Redis) | 100 | 149.0 | 6.7103 | 6.5628 | 7.8959 | 9.7340 |
| `Full AuthApi` Custom Role Cache Hit (live Redis) | 100 | 193.1 | 5.1785 | 5.0821 | 5.9003 | 6.2550 |
| `Full AuthApi` Organization Cache Miss + DB | 50 | 76.1 | 13.1430 | 13.1630 | 14.0294 | 15.0812 |
| Role Grant + Revoke Cycle (Lock + Audit + DB) | 25 | 30.2 | 33.1484 | 31.0790 | 48.5780 | 51.7551 |
| Durable PostgreSQL Audit Persist + HashChain | 100 | 365.4 | 2.7360 | 2.7090 | 3.2768 | 3.3983 |

> **Benchmark Environment Note:** Unlike the earlier offline-Redis run, all `Full AuthApi ... Cache Hit` timings above executed against a live Redis 8.10.3 socket (`127.0.0.1:6370`). Each end-to-end `AuthApi` authorization call still includes active membership validation and synchronous hash-chained PostgreSQL audit persistence (~`2.74 ms` mean standalone), which accounts for the ~`5.18–8.93 ms` full-path latency. Pure policy evaluation (`AuthorizationEvaluator`) remains sub-15 microseconds (~68k–196k ops/s).

---

## 5. Verification & Build Results

Executed `cd backend && ./gradlew cleanTest test build bootJar` with live PostgreSQL 17.11 and live Redis 8.10.3 (`127.0.0.1:6370`):

| Check / Suite | Outcome | Observed Result |
| :--- | :--- | :--- |
| `:devflow-auth:test` | **PASS** | **208 / 208** tests passed across **15** test suites (0 skipped, 0 failures, 0 errors) |
| `:app:test` | **PASS** | **768 / 768** tests passed across **195** test suites (0 skipped, 0 failures, 0 errors) |
| Combined Backend Test Suite | **PASS** | **976 / 976** tests passed across **210** test suites (0 skipped, 0 failures, 0 errors) |
| `./gradlew build` | **PASS** | `BUILD SUCCESSFUL` across `:app` and `:devflow-auth` (`1m 19s`) |
| `./gradlew bootJar` | **PASS** | Built `backend/app/build/libs/devflow-backend-0.0.1-SNAPSHOT.jar` (`73M`) |

### Stage 9 Verification Suites Added / Updated
- [`ConfigurationValidatorTest.java`](../../backend/app/src/test/java/com/devflow/config/ConfigurationValidatorTest.java) (**9** tests — includes `application-prod.yml` `ApplicationContextRunner` binding validation)
- [`RedisAuthorizationCacheHardeningTest.java`](../../backend/app/src/test/java/com/devflow/security/authorization/cache/RedisAuthorizationCacheHardeningTest.java) (**5** unit tests)
- [`LiveRedisAuthorizationCacheIntegrationTest.java`](../../backend/app/src/test/java/com/devflow/security/authorization/cache/LiveRedisAuthorizationCacheIntegrationTest.java) (**8** integration tests against live Redis `8.10.3`)
- [`LiveRedisOutageRecoveryIntegrationTest.java`](../../backend/app/src/test/java/com/devflow/security/authorization/cache/LiveRedisOutageRecoveryIntegrationTest.java) (**2** live-process outage & RDB recovery integration tests)
- [`Stage9SecurityAndIsolationIntegrationTest.java`](../../backend/app/src/test/java/com/devflow/security/Stage9SecurityAndIsolationIntegrationTest.java) (**4** integration tests)
- [`Stage9ConcurrencyAndAuditIntegrityIntegrationTest.java`](../../backend/app/src/test/java/com/devflow/security/Stage9ConcurrencyAndAuditIntegrityIntegrationTest.java) (**8** integration tests, adding transaction rollback audit continuity and concurrent org role grant/revoke tests)
- [`Stage9EndToEndLiveSmokeIntegrationTest.java`](../../backend/app/src/test/java/com/devflow/security/Stage9EndToEndLiveSmokeIntegrationTest.java) (**1** full HTTP `RANDOM_PORT` live smoke test)
- [`Stage9AuthorizationBenchmarkTest.java`](../../backend/app/src/test/java/com/devflow/performance/Stage9AuthorizationBenchmarkTest.java) (**1** benchmark suite against live PostgreSQL + live Redis)

### Live Infrastructure Closure Checks (PostgreSQL 17.11 & Redis 8.10.3)

| Infrastructure Track | Scope & Method | Status | Notes |
| :--- | :--- | :--- | :--- |
| Live PostgreSQL Migrations & Restart | Isolated DB clean `V1`–`V16`, populated `V1`–`V15 -> V16` upgrade, `postgresql@17` service restart & query continuity (temp DBs dropped afterward) | **PASS** | `V1`–`V15` untouched; `V16` triggers/indexes intact. |
| Live Redis 15-Scenario Cache Matrix | Cache miss/hit, `1s` TTL expiration, org grant/revoke & project revoke invalidation, custom-role permission update invalidation, membership removal clearing 4 key namespaces, tenant isolation, cursor `SCAN` over `1,250` org keys, 6-thread concurrency, `SHUTDOWN NOSAVE` outage & stale RDB replay recovery, and wiped-Redis healthy miss bypassing stale L1 | **PASS** | `LiveRedisAuthorizationCacheIntegrationTest` & `LiveRedisOutageRecoveryIntegrationTest` expect live Redis on test port `6370` (`application-test.yml` / `docker-compose.yml`). Single-node local Redis tested; multi-node Cluster/Sentinel HA & TLS Redis transport apply to staging/prod. |
| Live E2E HTTP Security Smoke (`RANDOM_PORT`) | `/api/v1/auth/login` JWT issuance, `401` on unauth/refresh token, spoofed header rejection (`403`), canonical & custom role live updates invalidating Redis cache (`403 -> 201 -> 403`), cross-tenant anti-enumeration (`403`), escalation/delegation audit retention (`noRollbackFor`), HMAC chain verify (`valid=true, tampered=false`), and alert `ACKNOWLEDGED` -> `RESOLVED` | **PASS** | `Stage9EndToEndLiveSmokeIntegrationTest` |
| Production Profile & Config Audit | Tested actual `application.yml` + `application-prod.yml` loading under `prod` profile (`ConfigurationValidatorTest`) | **PASS** | Confirmed both `DEVFLOW_SECURITY_CORS_ALLOWED_ORIGINS` and `DEVFLOW_CORS_ALLOWED_ORIGINS` bind properly (`[]` when unset), `springdoc` disabled, `in-memory-buffer-size: 0`, and `spring.data.redis.ssl.enabled: true`. |

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
| `DEVFLOW_SECURITY_CORS_ALLOWED_ORIGINS` / `DEVFLOW_CORS_ALLOWED_ORIGINS` | `devflow.security.cors.allowed-origins` | Explicit comma-separated HTTPS origin list (`application-prod.yml` binds `${DEVFLOW_CORS_ALLOWED_ORIGINS:}` and `DEVFLOW_SECURITY_CORS_ALLOWED_ORIGINS` overrides via relaxed env binding; defaults to `[]` if unset; wildcards `*` and blank entries are rejected). |

### Optional Operational & Hardening Settings

| Variable / Property | Default (`prod`) | Notes |
| :--- | :--- | :--- |
| `PORT` | `8080` | HTTP server port (`server.forward-headers-strategy: native`). |
| `MANAGEMENT_PORT` | `8081` | Restricted Actuator port exposing only `health,info,metrics,prometheus`. Keep internal/non-public. |
| `DEVFLOW_AUDIT_BUFFER_SIZE` | `0` (`application-prod.yml`) | Disables in-memory audit ring buffer in `prod` to bound JVM heap. |
| `DEVFLOW_AVATAR_UPLOAD_DIR` / `BASE_URL` / `MAX_SIZE_BYTES` | `./uploads/avatars` / local / `5242880` | Point upload dir/CDN URL to persistent volume / object storage CDN in prod. |
| `springdoc.api-docs.enabled` / `swagger-ui.enabled` | `false` (`application-prod.yml`) | Enforced `false` by `ConfigurationValidator` when `prod` profile is active. |

> **Test Suite Infrastructure Prerequisite:** Running `:app:test` requires PostgreSQL (default `jdbc:postgresql://localhost:5432/devflow_test`) and Redis (default `localhost:6370`, matching [`docker-compose.yml`](../../backend/docker-compose.yml)) because `LiveRedisAuthorizationCacheIntegrationTest` and `Stage9EndToEndLiveSmokeIntegrationTest` assert live Redis connectivity (`PING`).

---

## 7. Pre-Release Production Checklist

- [ ] Provision managed PostgreSQL 17 with automated encrypted backups and run Flyway migrations (`V1`–`V16`) under `validate-on-migrate: true`.
- [ ] Verify DB triggers `trg_prevent_audit_log_update_delete` and `trg_prevent_checkpoint_update_delete` remain enabled and the runtime DB user lacks `SUPERUSER`/trigger-bypass privileges.
- [ ] Inject freshly generated high-entropy `DEVFLOW_JWT_SECRET` (>= 64 bytes) and `DEVFLOW_AUDIT_INTEGRITY_KEY` (>= 32 bytes) from secrets manager.
- [ ] Configure TLS-enabled Redis (`DEVFLOW_REDIS_HOST`, `DEVFLOW_REDIS_PASSWORD`), verify staging HA failover with Lettuce backoff (`spring.data.redis.timeout`), and monitor eviction-replay logs (`RedisAuthorizationCacheService`) in log aggregation.
- [ ] Configure explicit `DEVFLOW_SECURITY_CORS_ALLOWED_ORIGINS` (or `DEVFLOW_CORS_ALLOWED_ORIGINS`) for deployed web origins.
- [ ] Expose application port (`PORT`) via TLS-terminating reverse proxy and keep Actuator (`MANAGEMENT_PORT`) isolated on internal cluster network only.
- [ ] Re-run `Stage9AuthorizationBenchmarkTest` against managed staging PostgreSQL + TLS Redis to confirm production latency SLOs under cluster network RTT.
