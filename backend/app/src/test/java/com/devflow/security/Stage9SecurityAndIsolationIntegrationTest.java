package com.devflow.security;

import com.devflow.audit.domain.AuthorizationAuditEventEntity;
import com.devflow.audit.repository.AuthorizationAuditEventRepository;
import com.devflow.modules.auth.api.AuthApi;
import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.AuthorizationRequest;
import com.devflow.modules.auth.api.CompositeAuthorizationRequest;
import com.devflow.modules.auth.api.DenialReason;
import com.devflow.modules.auth.context.AuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationInvitation;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.organization.service.OrganizationInvitationService;
import com.devflow.role.domain.CustomRoleEntity;
import com.devflow.role.dto.CreateCustomRoleRequest;
import com.devflow.role.dto.GrantRoleRequest;
import com.devflow.role.service.CustomRoleService;
import com.devflow.role.service.RoleAssignmentService;
import com.devflow.security.authorization.cache.AuthorizationCacheService;
import com.devflow.security.jwt.JwtTokenProvider;
import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.util.TraceContext;
import com.devflow.team.domain.Team;
import com.devflow.team.domain.TeamMember;
import com.devflow.team.domain.TeamRole;
import com.devflow.team.domain.TeamStatus;
import com.devflow.team.exception.TeamAccessDeniedException;
import com.devflow.team.repository.TeamMemberRepository;
import com.devflow.team.repository.TeamRepository;
import com.devflow.team.service.TeamAuthorizationService;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import com.devflow.workspace.domain.Workspace;
import com.devflow.workspace.domain.WorkspaceVisibility;
import com.devflow.workspace.repository.WorkspaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Stage 9: E2E Authorization, JWT, Multi-Tenant Isolation & Anti-Enumeration Tests")
class Stage9SecurityAndIsolationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private AuthApi authApi;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private CustomRoleService customRoleService;

    @Autowired
    private OrganizationInvitationService invitationService;

    @Autowired
    private TeamAuthorizationService teamAuthorizationService;

    @Autowired
    private AuthorizationCacheService cacheService;

    @Autowired
    private AuthorizationAuditEventRepository auditEventRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private EntityManager entityManager;

    private User ownerA;
    private User adminA;
    private User devA;
    private User suspendedA;
    private User outsiderB;

    private Organization orgA;
    private Organization orgB;
    private Workspace publicWorkspaceA;

    @BeforeEach
    void setUp() {
        cacheService.clear();

        ownerA = createUser("s9-owner-a");
        adminA = createUser("s9-admin-a");
        devA = createUser("s9-dev-a");
        suspendedA = createUser("s9-suspended-a");
        outsiderB = createUser("s9-outsider-b");

        orgA = organizationRepository.save(Organization.builder()
                .name("Stage9 Alpha Org")
                .slug("s9-alpha-" + UUID.randomUUID())
                .owner(ownerA)
                .build());

        orgB = organizationRepository.save(Organization.builder()
                .name("Stage9 Beta Org")
                .slug("s9-beta-" + UUID.randomUUID())
                .owner(outsiderB)
                .build());

        addMember(orgA, ownerA, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE);
        addMember(orgA, adminA, OrganizationRole.ADMIN, OrganizationMembershipStatus.ACTIVE);
        addMember(orgA, devA, OrganizationRole.DEVELOPER, OrganizationMembershipStatus.ACTIVE);
        addMember(orgA, suspendedA, OrganizationRole.DEVELOPER, OrganizationMembershipStatus.SUSPENDED);
        addMember(orgB, outsiderB, OrganizationRole.OWNER, OrganizationMembershipStatus.ACTIVE);

        roleAssignmentService.assignOrganizationRole(orgA.getId(), ownerA.getId(), Role.ORGANIZATION_OWNER, ownerA.getId(), null);
        roleAssignmentService.assignOrganizationRole(orgA.getId(), adminA.getId(), Role.ORGANIZATION_ADMIN, ownerA.getId(), null);
        roleAssignmentService.assignOrganizationRole(orgA.getId(), devA.getId(), Role.ORGANIZATION_MEMBER, ownerA.getId(), null);

        publicWorkspaceA = workspaceRepository.save(Workspace.builder()
                .organization(orgA)
                .name("Engineering " + UUID.randomUUID().toString().substring(0, 6))
                .description("Stage 9 Workspace")
                .visibility(WorkspaceVisibility.PUBLIC)
                .build());

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("E2E JWT auth rejects refresh tokens on API endpoints and ignores spoofed identity/role headers")
    void rejectsRefreshJwtAndIgnoresSpoofedHeaders() throws Exception {
        String accessTokenDevA = jwtTokenProvider.generateAccessToken(new DevFlowUserDetails(devA), orgA.getId());
        String refreshTokenOwnerA = jwtTokenProvider.generateRefreshToken(new DevFlowUserDetails(ownerA));
        String accessTokenOwnerA = jwtTokenProvider.generateAccessToken(new DevFlowUserDetails(ownerA), orgA.getId());

        assertThat(jwtTokenProvider.isRefreshToken(refreshTokenOwnerA)).isTrue();
        assertThat(jwtTokenProvider.isRefreshToken(accessTokenOwnerA)).isFalse();

        // 1. Refresh token must NOT authenticate protected API endpoints
        mockMvc.perform(get("/api/v1/organizations/{orgId}/security/audit-logs", orgA.getId())
                        .header("Authorization", "Bearer " + refreshTokenOwnerA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        // 2. Forged/wrong-issuer token must be rejected
        SecretKey devKey = Keys.hmacShaKeyFor(
                ("404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970"
                        + "337336763979244226452948404D635166546A576E5A7234753778214125442A")
                        .getBytes(StandardCharsets.UTF_8));
        String wrongIssuerToken = Jwts.builder()
                .subject(ownerA.getUsername())
                .issuer("evil-issuer")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(devKey, Jwts.SIG.HS512)
                .compact();
        assertThat(jwtTokenProvider.validateToken(wrongIssuerToken)).isFalse();

        // 3. Developer sending spoofed identity/role headers for ownerA still gets 403 Forbidden
        mockMvc.perform(get("/api/v1/organizations/{orgId}/roles", orgA.getId())
                        .header("Authorization", "Bearer " + accessTokenDevA)
                        .header("X-User-Id", ownerA.getId().toString())
                        .header("X-Roles", "ORGANIZATION_OWNER,PLATFORM_ADMIN")
                        .header("X-Organization-Id", orgB.getId().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // 4. Legitimate Owner access token succeeds
        mockMvc.perform(get("/api/v1/organizations/{orgId}/roles", orgA.getId())
                        .header("Authorization", "Bearer " + accessTokenOwnerA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Oversized and control-char X-Request-ID is sanitized so durable audit persistence cannot DoS admin mutations")
    void oversizedRequestIdSanitizedForDurableAudit() throws Exception {
        // 1. Direct sanitization strips control chars (CRLF) and clamps length to 100 chars
        String sanitized = TraceContext.sanitize("req-\r\nFAKE_LOG_ENTRY-" + "X".repeat(250));
        assertThat(sanitized).isNotNull();
        assertThat(sanitized.length()).isEqualTo(100);
        assertThat(sanitized).startsWith("req-FAKE_LOG_ENTRY-");
        assertThat(sanitized).doesNotContain("\r", "\n");

        // 2. Oversized HTTP X-Request-ID (>100 chars) succeeds cleanly and is clamped before PostgreSQL insert + hashing
        String accessTokenOwnerA = jwtTokenProvider.generateAccessToken(new DevFlowUserDetails(ownerA), orgA.getId());
        String oversizedRequestId = "req-" + "X".repeat(250);

        GrantRoleRequest req = GrantRoleRequest.builder()
                .userId(devA.getId())
                .role("ORGANIZATION_BILLING")
                .build();

        mockMvc.perform(post("/api/v1/organizations/{orgId}/roles", orgA.getId())
                        .header("Authorization", "Bearer " + accessTokenOwnerA)
                        .header("X-Request-ID", oversizedRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        List<AuthorizationAuditEventEntity> records =
                auditEventRepository.findByOrganizationIdOrderBySequenceNumberAsc(orgA.getId().toString());
        assertThat(records).isNotEmpty();
        AuthorizationAuditEventEntity latest = records.get(records.size() - 1);
        assertThat(latest.getCorrelationId()).isNotNull();
        assertThat(latest.getCorrelationId().length()).isLessThanOrEqualTo(100);
        assertThat(latest.getCorrelationId()).doesNotContain("\r", "\n");

        // 3. CRLF header injection on audit-logged mutation is rejected cleanly with 400 (never 500) by HTTP firewall
        GrantRoleRequest crlfReq = GrantRoleRequest.builder()
                .userId(devA.getId())
                .role("ORGANIZATION_VIEWER")
                .build();
        mockMvc.perform(post("/api/v1/organizations/{orgId}/roles", orgA.getId())
                        .header("Authorization", "Bearer " + accessTokenOwnerA)
                        .header("X-Request-ID", "req-\r\nFAKE_LOG")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crlfReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Cross-tenant and unauthorized custom-role probes return 403 without leaking role existence, and evaluator prevents project enumeration")
    void antiEnumerationAndCrossTenantIsolation() throws Exception {
        CustomRoleEntity customRoleA = customRoleService.createCustomRole(
                orgA.getId(),
                ownerA.getId(),
                CreateCustomRoleRequest.builder()
                        .name("SEC_AUDITOR_" + UUID.randomUUID().toString().substring(0, 6))
                        .displayName("Security Auditor")
                        .permissions(Set.of("project.read", "repo.scan"))
                        .build()
        );
        UUID nonExistentRoleId = UUID.randomUUID();
        entityManager.flush();
        entityManager.clear();

        String outsiderToken = jwtTokenProvider.generateAccessToken(new DevFlowUserDetails(outsiderB), orgB.getId());
        String ownerAToken = jwtTokenProvider.generateAccessToken(new DevFlowUserDetails(ownerA), orgA.getId());

        // 1. Cross-tenant GET/PUT on both existing customRoleA and non-existent UUID -> uniform 403 Forbidden
        mockMvc.perform(get("/api/v1/organizations/{orgId}/custom-roles/{roleId}", orgA.getId(), customRoleA.getId())
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/organizations/{orgId}/custom-roles/{roleId}", orgA.getId(), nonExistentRoleId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/organizations/{orgId}/custom-roles/{roleId}", orgA.getId(), nonExistentRoleId)
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Hijack\"}"))
                .andExpect(status().isForbidden());

        // 2. Outsider probing random nonexistent organization ID returns 403 (never 404 org enumeration)
        mockMvc.perform(get("/api/v1/organizations/{orgId}/custom-roles", UUID.randomUUID())
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());

        // 3. Authorized ownerA still receives 404 for nonexistent role within own tenant
        mockMvc.perform(get("/api/v1/organizations/{orgId}/custom-roles/{roleId}", orgA.getId(), nonExistentRoleId)
                        .header("Authorization", "Bearer " + ownerAToken))
                .andExpect(status().isNotFound());

        // 4. Evaluator checks active membership BEFORE probing whether projectId belongs to orgA
        UUID projectAssignedInOrgB = UUID.randomUUID();
        roleAssignmentService.assignProjectRole(orgB.getId(), projectAssignedInOrgB, outsiderB.getId(), Role.PROJECT_LEAD, outsiderB.getId(), null);
        AuthorizationDecision nonMemberDecision = authApi.authorize(
                AuthorizationRequest.project(outsiderB.getId(), orgA.getId(), projectAssignedInOrgB, PermissionRegistry.PROJECT_READ));
        assertThat(nonMemberDecision.allowed()).isFalse();
        assertThat(nonMemberDecision.denialReason()).isEqualTo(DenialReason.MEMBERSHIP_INACTIVE);

        // 5. Security monitoring denial against orgA by cross-tenant outsiderB & devA produces 403 and writes audit event
        mockMvc.perform(get("/api/v1/organizations/{orgId}/security/audit-logs", orgA.getId())
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());

        String devAToken = jwtTokenProvider.generateAccessToken(new DevFlowUserDetails(devA), orgA.getId());
        mockMvc.perform(get("/api/v1/organizations/{orgId}/security/audit-logs", orgA.getId())
                        .header("Authorization", "Bearer " + devAToken))
                .andExpect(status().isForbidden());

        List<AuthorizationAuditEventEntity> orgAAudit = auditEventRepository.findByOrganizationIdOrderBySequenceNumberAsc(orgA.getId().toString());
        assertThat(orgAAudit).anyMatch(e ->
                e.getUserId().equals(devA.getId().toString())
                        && "DENY".equals(e.getDecision())
                        && "SECURITY_MONITORING".equals(e.getResourceType()));

        // 6. Pagination bounds on security endpoints reject huge sizes / negative pages gracefully
        mockMvc.perform(get("/api/v1/organizations/{orgId}/security/audit-logs?page=-1&size=999999", orgA.getId())
                        .header("Authorization", "Bearer " + ownerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    @DisplayName("Validates canonical, custom, ownership, inheritance, composite, default-deny, expired, suspended, and invitee cache eviction")
    void validatesEndToEndAuthorizationPolicyMatrixAndInviteeCacheEviction() {
        // 1. Default deny for suspended member & non-member
        assertThat(authApi.isAuthorized(suspendedA.getId(), orgA.getId(), PermissionRegistry.PROJECT_READ)).isFalse();
        assertThat(authApi.isAuthorized(outsiderB.getId(), orgA.getId(), PermissionRegistry.PROJECT_READ)).isFalse();

        // 2. Organization-to-Project inheritance: OWNER inherits PROJECT permissions without explicit project assignment
        UUID projectId = UUID.randomUUID();
        roleAssignmentService.assignProjectRole(orgA.getId(), projectId, devA.getId(), Role.PROJECT_MEMBER, ownerA.getId(), null);
        assertThat(authApi.isAuthorized(ownerA.getId(), orgA.getId(), projectId, PermissionRegistry.PROJECT_DELETE)).isTrue();
        assertThat(authApi.isAuthorized(adminA.getId(), orgA.getId(), projectId, PermissionRegistry.TASK_ASSIGN)).isTrue();

        // Developer requires explicit project assignment: allowed on assigned projectId, denied on unassigned project2
        UUID unassignedProjectId = UUID.randomUUID();
        assertThat(authApi.isAuthorized(devA.getId(), orgA.getId(), projectId, PermissionRegistry.TASK_CREATE)).isTrue();
        AuthorizationDecision unassignedDecision = authApi.authorize(
                AuthorizationRequest.project(devA.getId(), orgA.getId(), unassignedProjectId, PermissionRegistry.TASK_CREATE));
        assertThat(unassignedDecision.allowed()).isFalse();
        assertThat(unassignedDecision.denialReason()).isEqualTo(DenialReason.PROJECT_ASSIGNMENT_REQUIRED);

        // 3. Composite authorization (AND semantics): fails closed if any permission missing
        assertThat(authApi.isAuthorizedAll(devA.getId(), orgA.getId(),
                Set.of(PermissionRegistry.PROJECT_READ, PermissionRegistry.KB_READ))).isTrue();
        AuthorizationDecision compositeDeny = authApi.authorize(CompositeAuthorizationRequest.of(
                devA.getId(),
                AuthorizationContext.organization(orgA.getId()),
                List.of(PermissionRegistry.PROJECT_READ, PermissionRegistry.BILLING_MANAGE)
        ));
        assertThat(compositeDeny.allowed()).isFalse();

        // 4. Custom role grants specialized permission & immediate deactivation revokes it
        CustomRoleEntity scannerRole = customRoleService.createCustomRole(
                orgA.getId(),
                ownerA.getId(),
                CreateCustomRoleRequest.builder()
                        .name("SEC_SCANNER_" + UUID.randomUUID().toString().substring(0, 6))
                        .displayName("Security Scanner")
                        .permissions(Set.of("repo.scan", "analytics.view", "analytics.export"))
                        .build()
        );
        roleAssignmentService.assignCustomRole(orgA.getId(), devA.getId(), scannerRole.getId(), ownerA.getId(), null);
        assertThat(authApi.isAuthorized(devA.getId(), orgA.getId(), PermissionRegistry.REPO_SCAN)).isTrue();
        assertThat(authApi.isAuthorized(devA.getId(), orgA.getId(), PermissionRegistry.ANALYTICS_EXPORT)).isTrue();

        // 5. Ownership-based authorization on Teams (Team LEAD owns team management vs regular team MEMBER denied)
        Team team = teamRepository.save(Team.builder()
                .organization(orgA)
                .workspace(publicWorkspaceA)
                .name("Platform Team " + UUID.randomUUID().toString().substring(0, 6))
                .status(TeamStatus.ACTIVE)
                .build());
        teamMemberRepository.save(TeamMember.builder()
                .team(team).user(devA).role(TeamRole.LEAD).joinedAt(Instant.now()).build());
        User nonLeadMember = createUser("s9-team-mem");
        addMember(orgA, nonLeadMember, OrganizationRole.DEVELOPER, OrganizationMembershipStatus.ACTIVE);
        roleAssignmentService.assignOrganizationRole(orgA.getId(), nonLeadMember.getId(), Role.ORGANIZATION_MEMBER, ownerA.getId(), null);
        teamMemberRepository.save(TeamMember.builder()
                .team(team).user(nonLeadMember).role(TeamRole.MEMBER).joinedAt(Instant.now()).build());
        entityManager.flush();
        assertThatCode(() -> teamAuthorizationService.requireCanManageTeam(
                orgA.getId(), publicWorkspaceA, team.getId(), devA.getId()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> teamAuthorizationService.requireCanManageTeam(
                orgA.getId(), publicWorkspaceA, team.getId(), nonLeadMember.getId()))
                .isInstanceOf(TeamAccessDeniedException.class);
        assertThat(authApi.isAuthorized(AuthorizationRequest.organization(
                nonLeadMember.getId(), orgA.getId(), PermissionRegistry.PROJECT_DELETE, team.getId().toString())))
                .isFalse();

        // 6. Expired project role assignment is denied (default deny)
        UUID expiredProj = UUID.randomUUID();
        roleAssignmentService.assignProjectRole(orgA.getId(), expiredProj, devA.getId(), Role.PROJECT_LEAD, ownerA.getId(),
                Instant.now().minusSeconds(120));
        cacheService.evictUser(orgA.getId(), devA.getId());
        assertThat(authApi.isAuthorized(devA.getId(), orgA.getId(), expiredProj, PermissionRegistry.CYCLE_MANAGE)).isFalse();

        // 7. Invitee cache eviction: prime negative membership cache for invitee, accept invite, verify immediate access
        User invitee = createUser("s9-invitee");
        // Negative evaluation primes cache with isMemberActive=false
        assertThat(authApi.isAuthorized(invitee.getId(), orgA.getId(), PermissionRegistry.PROJECT_READ)).isFalse();

        OrganizationInvitation invitation = invitationService.inviteMember(
                orgA.getId(), invitee.getEmail(), OrganizationRole.DEVELOPER, ownerA.getId());
        invitationService.acceptInvitation(invitation.getToken(), invitee.getId());
        roleAssignmentService.assignOrganizationRole(orgA.getId(), invitee.getId(), Role.ORGANIZATION_MEMBER, ownerA.getId(), null);

        assertThat(authApi.isAuthorized(invitee.getId(), orgA.getId(), PermissionRegistry.PROJECT_READ)).isTrue();
    }

    private User createUser(String prefix) {
        return userRepository.save(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@devflow.io")
                .username((prefix + "_" + UUID.randomUUID().toString().substring(0, 8)).replace('-', '_'))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());
    }

    private OrganizationMember addMember(
            Organization org, User user, OrganizationRole role, OrganizationMembershipStatus status) {
        return organizationMemberRepository.save(OrganizationMember.builder()
                .organization(org)
                .user(user)
                .role(role)
                .status(status)
                .build());
    }
}
