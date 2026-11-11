package com.devflow.security;

import com.devflow.audit.dto.AuditIntegrityVerificationResponse;
import com.devflow.auth.dto.AuthenticationResponse;
import com.devflow.auth.dto.LoginRequest;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.dto.CreateOrganizationRequest;
import com.devflow.organization.dto.OrganizationResponse;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.role.dto.CreateCustomRoleRequest;
import com.devflow.role.dto.CustomRoleResponse;
import com.devflow.role.dto.GrantRoleRequest;
import com.devflow.role.dto.RoleAssignmentResponse;
import com.devflow.role.dto.UpdateCustomRoleRequest;
import com.devflow.security.authorization.cache.AuthorizationCacheService;
import com.devflow.security.authorization.cache.RedisAuthorizationCacheService;
import com.devflow.security.monitoring.domain.SecurityAlertStatus;
import com.devflow.security.monitoring.dto.ResolveSecurityAlertRequest;
import com.devflow.security.monitoring.dto.SecurityAlertResponse;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import com.devflow.workspace.dto.CreateWorkspaceRequest;
import com.devflow.workspace.dto.WorkspaceResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live full-stack HTTP security smoke suite running over {@link SpringBootTest.WebEnvironment#RANDOM_PORT}
 * backed by real PostgreSQL and live Redis (Stage 9 Phase 4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Stage 9 Phase 4: Live HTTP End-to-End Security Smoke Tests")
class Stage9EndToEndLiveSmokeIntegrationTest {

    private static final String TEST_SECRET_CREDENTIAL = "SmokeTest!Pass2026#Secure";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuthorizationCacheService cacheService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> createdOrgIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdUserIds = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void verifyLiveInfrastructure() {
        assertThat(cacheService).isInstanceOf(RedisAuthorizationCacheService.class);
        String pong = stringRedisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
        assertThat(pong).isEqualToIgnoringCase("PONG");
        cacheService.clear();
    }

    @AfterEach
    void cleanUpCommittedEntities() {
        for (UUID orgId : createdOrgIds) {
            cacheService.evictOrganization(orgId);
            String orgIdStr = orgId.toString();
            jdbcTemplate.update("DELETE FROM security_alerts WHERE organization_id = ?", orgIdStr);
            jdbcTemplate.update("DELETE FROM security_events WHERE organization_id = ?", orgIdStr);
            jdbcTemplate.update("DELETE FROM workspaces WHERE organization_id = ?", orgIdStr);
            jdbcTemplate.update("DELETE FROM custom_role_assignments WHERE organization_id = ?", orgIdStr);
            jdbcTemplate.update(
                    "DELETE FROM custom_role_permissions WHERE custom_role_id IN (SELECT id FROM custom_roles WHERE organization_id = ?)",
                    orgIdStr);
            jdbcTemplate.update("DELETE FROM custom_roles WHERE organization_id = ?", orgIdStr);
            jdbcTemplate.update("DELETE FROM project_role_assignments WHERE organization_id = ?", orgIdStr);
            jdbcTemplate.update("DELETE FROM organization_role_assignments WHERE organization_id = ?", orgIdStr);
            jdbcTemplate.update("DELETE FROM organization_invitations WHERE organization_id = ?", orgIdStr);
            jdbcTemplate.update("DELETE FROM organization_members WHERE organization_id = ?", orgIdStr);
            jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", orgIdStr);
        }
        createdOrgIds.clear();

        for (UUID userId : createdUserIds) {
            String userIdStr = userId.toString();
            jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", userIdStr);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userIdStr);
        }
        createdUserIds.clear();
    }

    @Test
    @DisplayName("Full HTTP flow: JWT login, spoof-header resistance, escalation audit persistence, custom roles + Redis invalidation, tenant isolation, and alert lifecycle")
    void endToEndHttpSecuritySmokeAcrossLivePostgresAndRedis() {
        // 1. Provision active users and authenticate via real /api/v1/auth/login
        User ownerA = createVerifiedUser("smoke-own-a");
        User adminA = createVerifiedUser("smoke-adm-a");
        User devA = createVerifiedUser("smoke-dev-a");
        User viewerA = createVerifiedUser("smoke-view-a");
        User ownerB = createVerifiedUser("smoke-own-b");

        AuthenticationResponse ownerAAuth = login(ownerA);
        AuthenticationResponse adminAAuth = login(adminA);
        AuthenticationResponse devAAuth = login(devA);
        AuthenticationResponse viewerAAuth = login(viewerA);
        AuthenticationResponse ownerBAuth = login(ownerB);

        String ownerAToken = ownerAAuth.getAccessToken();
        String adminAToken = adminAAuth.getAccessToken();
        String devAToken = devAAuth.getAccessToken();
        String viewerAToken = viewerAAuth.getAccessToken();
        String ownerBToken = ownerBAuth.getAccessToken();

        // 2. Owner A creates Organization A via HTTP; Owner B creates Organization B
        ResponseEntity<OrganizationResponse> createOrgAResp = exchangeJson(
                "/api/v1/organizations", HttpMethod.POST, ownerAToken,
                CreateOrganizationRequest.builder()
                        .name("Smoke Tenant Alpha")
                        .slug("smoke-alpha-" + UUID.randomUUID().toString().substring(0, 8))
                        .description("Primary smoke test tenant")
                        .build(),
                OrganizationResponse.class);
        assertThat(createOrgAResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID orgAId = createOrgAResp.getBody().getId();
        createdOrgIds.add(orgAId);

        ResponseEntity<OrganizationResponse> createOrgBResp = exchangeJson(
                "/api/v1/organizations", HttpMethod.POST, ownerBToken,
                CreateOrganizationRequest.builder()
                        .name("Smoke Tenant Beta")
                        .slug("smoke-beta-" + UUID.randomUUID().toString().substring(0, 8))
                        .description("Secondary isolated tenant")
                        .build(),
                OrganizationResponse.class);
        assertThat(createOrgBResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID orgBId = createOrgBResp.getBody().getId();
        createdOrgIds.add(orgBId);

        // Attach memberships in Org A
        Organization orgA = organizationRepository.findById(orgAId).orElseThrow();
        addActiveMembership(orgA, adminA, OrganizationRole.ADMIN);
        addActiveMembership(orgA, devA, OrganizationRole.DEVELOPER);
        addActiveMembership(orgA, viewerA, OrganizationRole.VIEWER);

        // 3. Unauthenticated and refresh-token-as-bearer probes -> 401 without stack trace
        ResponseEntity<String> unauthResp = restTemplate.getForEntity(
                "/api/v1/organizations/" + orgAId + "/security/audit-logs", String.class);
        assertThat(unauthResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unauthResp.getBody()).doesNotContain("Exception", "at com.devflow", TEST_SECRET_CREDENTIAL);

        ResponseEntity<String> refreshAsBearer = exchangeJson(
                "/api/v1/organizations/" + orgAId, HttpMethod.GET, ownerAAuth.getRefreshToken(), null, String.class);
        assertThat(refreshAsBearer.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 4. Spoofed identity/role headers ignored: Dev A attempting security monitoring read gets 403
        HttpHeaders spoofHeaders = bearerHeaders(devAToken);
        spoofHeaders.set("X-User-Id", ownerA.getId().toString());
        spoofHeaders.set("X-Organization-Id", orgAId.toString());
        spoofHeaders.set("X-Roles", "ORGANIZATION_OWNER,PLATFORM_ADMIN");
        spoofHeaders.set("X-Permissions", "org.manage,audit.read,alert.resolve");
        ResponseEntity<String> spoofResp = restTemplate.exchange(
                "/api/v1/organizations/" + orgAId + "/security/alerts",
                HttpMethod.GET,
                new HttpEntity<>(spoofHeaders),
                String.class);
        assertThat(spoofResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 5. Grant canonical org roles & test workspace creation + project role grant/revoke over HTTP
        ResponseEntity<RoleAssignmentResponse> adminGrantResp = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/roles", HttpMethod.POST, ownerAToken,
                GrantRoleRequest.builder().userId(adminA.getId()).role("ORGANIZATION_ADMIN").build(),
                RoleAssignmentResponse.class);
        assertThat(adminGrantResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<RoleAssignmentResponse> viewerGrantResp = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/roles", HttpMethod.POST, ownerAToken,
                GrantRoleRequest.builder().userId(viewerA.getId()).role("ORGANIZATION_VIEWER").build(),
                RoleAssignmentResponse.class);
        assertThat(viewerGrantResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<WorkspaceResponse> wsByAdminResp = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/workspaces", HttpMethod.POST, adminAToken,
                CreateWorkspaceRequest.builder().name("Alpha Workspace").description("Live smoke workspace").build(),
                WorkspaceResponse.class);
        assertThat(wsByAdminResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID workspaceId = wsByAdminResp.getBody().getId();

        ResponseEntity<String> wsByViewerDenied = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/workspaces", HttpMethod.POST, viewerAToken,
                CreateWorkspaceRequest.builder().name("Denied Workspace").build(),
                String.class);
        assertThat(wsByViewerDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        UUID projectId = UUID.randomUUID();
        ResponseEntity<RoleAssignmentResponse> projGrantResp = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/projects/" + projectId + "/roles", HttpMethod.POST, ownerAToken,
                GrantRoleRequest.builder().userId(devA.getId()).role("PROJECT_LEAD").build(),
                RoleAssignmentResponse.class);
        assertThat(projGrantResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID projAssignmentId = projGrantResp.getBody().getId();

        ResponseEntity<Void> projRevokeResp = restTemplate.exchange(
                "/api/v1/organizations/" + orgAId + "/projects/" + projectId + "/roles/" + projAssignmentId,
                HttpMethod.DELETE,
                new HttpEntity<>(null, bearerHeaders(ownerAToken)),
                Void.class);
        assertThat(projRevokeResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 6. Custom role governance + HTTP enforcement + live Redis invalidation
        ResponseEntity<CustomRoleResponse> customRoleCreate = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/custom-roles", HttpMethod.POST, ownerAToken,
                CreateCustomRoleRequest.builder()
                        .name("WORKSPACE_OPS")
                        .displayName("Workspace Ops")
                        .permissions(Set.of("project.read"))
                        .build(),
                CustomRoleResponse.class);
        assertThat(customRoleCreate.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID customRoleId = customRoleCreate.getBody().getId();

        ResponseEntity<RoleAssignmentResponse> customRoleAssign = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/roles", HttpMethod.POST, ownerAToken,
                GrantRoleRequest.builder().userId(viewerA.getId()).role(customRoleId.toString()).build(),
                RoleAssignmentResponse.class);
        assertThat(customRoleAssign.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID customAssignmentId = customRoleAssign.getBody().getId();

        // Viewer still denied workspace creation before custom role includes member.invite
        assertThat(exchangeJson(
                "/api/v1/organizations/" + orgAId + "/workspaces", HttpMethod.POST, viewerAToken,
                CreateWorkspaceRequest.builder().name("Viewer Blocked WS").build(),
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Owner updates custom role adding member.invite -> evicts live Redis -> Viewer succeeds immediately
        ResponseEntity<CustomRoleResponse> customRoleExpand = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/custom-roles/" + customRoleId, HttpMethod.PUT, ownerAToken,
                UpdateCustomRoleRequest.builder().permissions(Set.of("project.read", "member.invite")).build(),
                CustomRoleResponse.class);
        assertThat(customRoleExpand.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<WorkspaceResponse> wsByViewerAllowed = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/workspaces", HttpMethod.POST, viewerAToken,
                CreateWorkspaceRequest.builder().name("Viewer Permitted WS").build(),
                WorkspaceResponse.class);
        assertThat(wsByViewerAllowed.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Owner removes member.invite -> live Redis evicted -> Viewer immediately denied again
        assertThat(exchangeJson(
                "/api/v1/organizations/" + orgAId + "/custom-roles/" + customRoleId, HttpMethod.PUT, ownerAToken,
                UpdateCustomRoleRequest.builder().permissions(Set.of("project.read")).build(),
                CustomRoleResponse.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(exchangeJson(
                "/api/v1/organizations/" + orgAId + "/workspaces", HttpMethod.POST, viewerAToken,
                CreateWorkspaceRequest.builder().name("Viewer Revoked WS").build(),
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Clean revoke custom role assignment
        assertThat(restTemplate.exchange(
                "/api/v1/organizations/" + orgAId + "/roles/" + customAssignmentId,
                HttpMethod.DELETE,
                new HttpEntity<>(null, bearerHeaders(ownerAToken)),
                Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 7. Cross-tenant anti-enumeration (Owner B probing Org A returns 403 on existing and non-existent resources)
        assertThat(exchangeJson("/api/v1/organizations/" + orgAId, HttpMethod.GET, ownerBToken, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchangeJson("/api/v1/organizations/" + orgAId + "/workspaces/" + workspaceId, HttpMethod.GET, ownerBToken, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchangeJson("/api/v1/organizations/" + orgAId + "/custom-roles/" + customRoleId, HttpMethod.GET, ownerBToken, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchangeJson("/api/v1/organizations/" + orgAId + "/custom-roles/" + UUID.randomUUID(), HttpMethod.GET, ownerBToken, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchangeJson("/api/v1/organizations/" + orgAId + "/security/audit-logs", HttpMethod.GET, ownerBToken, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchangeJson("/api/v1/organizations/" + orgAId + "/security/alerts", HttpMethod.GET, ownerBToken, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 8. Admin escalation & delegation attempts MUST return 403, preserve entity state, AND commit audit + alerts!
        for (int i = 0; i < 2; i++) {
            ResponseEntity<String> escalateResp = exchangeJson(
                    "/api/v1/organizations/" + orgAId + "/roles", HttpMethod.POST, adminAToken,
                    GrantRoleRequest.builder().userId(devA.getId()).role("ORGANIZATION_OWNER").build(),
                    String.class);
            assertThat(escalateResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            ResponseEntity<String> delegateViolationResp = exchangeJson(
                    "/api/v1/organizations/" + orgAId + "/custom-roles", HttpMethod.POST, adminAToken,
                    CreateCustomRoleRequest.builder()
                            .name("ILLEGAL_BILLING_" + i)
                            .displayName("Illegal Billing Role")
                            .permissions(Set.of("billing.manage"))
                            .build(),
                    String.class);
            assertThat(delegateViolationResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        // Update custom role delegation violation must NOT commit partial displayName change when audit persists via noRollbackFor
        ResponseEntity<String> updateViolationResp = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/custom-roles/" + customRoleId, HttpMethod.PUT, adminAToken,
                UpdateCustomRoleRequest.builder()
                        .displayName("HIJACKED_NAME")
                        .permissions(Set.of("billing.manage"))
                        .build(),
                String.class);
        assertThat(updateViolationResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<CustomRoleResponse> unchangedCustomRole = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/custom-roles/" + customRoleId, HttpMethod.GET, ownerAToken,
                null, CustomRoleResponse.class);
        assertThat(unchangedCustomRole.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unchangedCustomRole.getBody().getDisplayName()).isEqualTo("Workspace Ops");

        // 9. Verify security audit persistence, hash-chain integrity, security events, and alert lifecycle over HTTP
        ResponseEntity<JsonNode> privGrantAuditResp = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/security/audit-logs?eventType=PRIVILEGED_ROLE_GRANT_ATTEMPTED",
                HttpMethod.GET, ownerAToken, null, JsonNode.class);
        assertThat(privGrantAuditResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(privGrantAuditResp.getBody().path("content")).hasSize(2);

        ResponseEntity<JsonNode> delegAuditResp = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/security/audit-logs?eventType=PERMISSION_DELEGATION_VIOLATION_ATTEMPTED",
                HttpMethod.GET, ownerAToken, null, JsonNode.class);
        assertThat(delegAuditResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(delegAuditResp.getBody().path("content")).hasSize(3);

        // Ensure zero credentials / tokens leak in audit logs
        String allAuditPayload = privGrantAuditResp.getBody().toString() + delegAuditResp.getBody().toString();
        assertThat(allAuditPayload)
                .doesNotContain(ownerAToken, adminAToken, ownerAAuth.getRefreshToken(), TEST_SECRET_CREDENTIAL);

        ResponseEntity<AuditIntegrityVerificationResponse> integrityResp = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/security/audit-logs/integrity",
                HttpMethod.GET, ownerAToken, null, AuditIntegrityVerificationResponse.class);
        assertThat(integrityResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(integrityResp.getBody().isValid()).isTrue();
        assertThat(integrityResp.getBody().isTampered()).isFalse();
        assertThat(integrityResp.getBody().getTotalRecordsVerified()).isGreaterThan(0L);

        ResponseEntity<JsonNode> eventsResp = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/security/events",
                HttpMethod.GET, ownerAToken, null, JsonNode.class);
        assertThat(eventsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(eventsResp.getBody().path("content").size()).isGreaterThanOrEqualTo(2);

        ResponseEntity<JsonNode> alertsResp = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/security/alerts",
                HttpMethod.GET, ownerAToken, null, JsonNode.class);
        assertThat(alertsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode alertsContent = alertsResp.getBody().path("content");
        assertThat(alertsContent.size()).isGreaterThanOrEqualTo(1);

        String alertId = alertsContent.get(0).path("id").asText();
        assertThat(alertsContent.get(0).path("status").asText()).isEqualTo("OPEN");

        ResponseEntity<SecurityAlertResponse> ackResp = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/security/alerts/" + alertId + "/acknowledge",
                HttpMethod.POST, ownerAToken, null, SecurityAlertResponse.class);
        assertThat(ackResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ackResp.getBody().getStatus()).isEqualTo(SecurityAlertStatus.ACKNOWLEDGED);

        ResponseEntity<SecurityAlertResponse> resolveResp = exchangeJson(
                "/api/v1/organizations/" + orgAId + "/security/alerts/" + alertId + "/resolve",
                HttpMethod.POST, ownerAToken,
                ResolveSecurityAlertRequest.builder()
                        .resolutionReason("Confirmed privilege escalation test alert and closed incident")
                        .build(),
                SecurityAlertResponse.class);
        assertThat(resolveResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolveResp.getBody().getStatus()).isEqualTo(SecurityAlertStatus.RESOLVED);
        assertThat(resolveResp.getBody().getResolutionReason())
                .isEqualTo("Confirmed privilege escalation test alert and closed incident");
    }

    private AuthenticationResponse login(User user) {
        ResponseEntity<AuthenticationResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login",
                LoginRequest.builder().login(user.getEmail()).password(TEST_SECRET_CREDENTIAL).build(),
                AuthenticationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getAccessToken()).isNotBlank();
        return response.getBody();
    }

    private <T> ResponseEntity<T> exchangeJson(
            String path,
            HttpMethod method,
            String bearerToken,
            Object body,
            Class<T> responseType
    ) {
        HttpHeaders headers = bearerHeaders(bearerToken);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), responseType);
    }

    private HttpHeaders bearerHeaders(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        return headers;
    }

    private User createVerifiedUser(String prefix) {
        User user = userRepository.saveAndFlush(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@devflow.io")
                .username((prefix + "_" + UUID.randomUUID().toString().substring(0, 8)).replace('-', '_'))
                .passwordHash(passwordEncoder.encode(TEST_SECRET_CREDENTIAL))
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());
        createdUserIds.add(user.getId());
        return user;
    }

    private void addActiveMembership(Organization org, User user, OrganizationRole role) {
        organizationMemberRepository.saveAndFlush(OrganizationMember.builder()
                .organization(org)
                .user(user)
                .role(role)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());
    }
}
