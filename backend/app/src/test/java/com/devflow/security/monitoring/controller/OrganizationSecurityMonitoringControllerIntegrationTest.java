package com.devflow.security.monitoring.controller;

import com.devflow.audit.domain.AuthorizationAuditEventEntity;
import com.devflow.audit.repository.AuthorizationAuditEventRepository;
import com.devflow.audit.service.AuditIntegrityService;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.security.monitoring.domain.SecurityAlertEntity;
import com.devflow.security.monitoring.domain.SecurityAlertStatus;
import com.devflow.security.monitoring.domain.SecurityEventEntity;
import com.devflow.security.monitoring.domain.SecuritySeverity;
import com.devflow.security.monitoring.dto.ResolveSecurityAlertRequest;
import com.devflow.security.monitoring.repository.SecurityAlertRepository;
import com.devflow.security.monitoring.repository.SecurityEventRepository;
import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Stage 8: Security Monitoring & Audit REST API Integration Tests")
class OrganizationSecurityMonitoringControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthorizationAuditEventRepository auditEventRepository;

    @Autowired
    private AuditIntegrityService integrityService;

    @Autowired
    private SecurityEventRepository eventRepository;

    @Autowired
    private SecurityAlertRepository alertRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private User ownerA;
    private User adminA;
    private User devA;
    private User ownerB;

    private Organization orgA;
    private Organization orgB;

    @BeforeEach
    void setUp() {
        ownerA = createUser("sec-owner-a");
        adminA = createUser("sec-admin-a");
        devA = createUser("sec-dev-a");
        ownerB = createUser("sec-owner-b");

        orgA = organizationRepository.save(Organization.builder()
                .name("Sec Corp A")
                .slug("sec-a-" + UUID.randomUUID())
                .owner(ownerA)
                .build());

        orgB = organizationRepository.save(Organization.builder()
                .name("Sec Corp B")
                .slug("sec-b-" + UUID.randomUUID())
                .owner(ownerB)
                .build());

        createOrgMember(orgA, ownerA, OrganizationRole.OWNER);
        createOrgMember(orgA, adminA, OrganizationRole.ADMIN);
        createOrgMember(orgA, devA, OrganizationRole.DEVELOPER);

        createOrgMember(orgB, ownerB, OrganizationRole.OWNER);

        entityManager.flush();
        entityManager.clear();
    }

    private DevFlowUserDetails asUserDetails(User u) {
        return new DevFlowUserDetails(u);
    }

    @Nested
    @DisplayName("Audit Log Endpoints")
    class AuditLogTests {

        @Test
        @DisplayName("Organization Owner can list and filter audit logs with pagination")
        void ownerCanListAuditLogs() throws Exception {
            persistTestAuditEvent(orgA.getId(), ownerA.getId(), "AUTHORIZATION_ALLOWED", "ALLOW", 1L);
            persistTestAuditEvent(orgA.getId(), devA.getId(), "AUTHORIZATION_DENIED", "DENY", 2L);

            mockMvc.perform(get("/api/v1/organizations/{organizationId}/security/audit-logs", orgA.getId())
                            .with(user(asUserDetails(ownerA)))
                            .param("page", "0")
                            .param("size", "10")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.totalElements").value(2));
        }

        @Test
        @DisplayName("Filter audit logs by decision and eventType")
        void filterAuditLogsByDecisionAndEventType() throws Exception {
            persistTestAuditEvent(orgA.getId(), ownerA.getId(), "AUTHORIZATION_ALLOWED", "ALLOW", 1L);
            persistTestAuditEvent(orgA.getId(), devA.getId(), "AUTHORIZATION_DENIED", "DENY", 2L);

            mockMvc.perform(get("/api/v1/organizations/{organizationId}/security/audit-logs", orgA.getId())
                            .with(user(asUserDetails(adminA)))
                            .param("decision", "DENY")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].decision").value("DENY"));
        }

        @Test
        @DisplayName("Retrieve individual audit event by ID")
        void retrieveAuditEventById() throws Exception {
            AuthorizationAuditEventEntity event = persistTestAuditEvent(
                    orgA.getId(), ownerA.getId(), "ROLE_GRANTED", "ALLOW", 1L);

            mockMvc.perform(get("/api/v1/organizations/{organizationId}/security/audit-logs/{eventId}",
                            orgA.getId(), event.getEventId())
                            .with(user(asUserDetails(ownerA))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eventId").value(event.getEventId()))
                    .andExpect(jsonPath("$.eventType").value("ROLE_GRANTED"));
        }

        @Test
        @DisplayName("Non-existent event ID returns 404 Not Found")
        void nonExistentEventReturnsNotFound() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/{organizationId}/security/audit-logs/{eventId}",
                            orgA.getId(), UUID.randomUUID().toString())
                            .with(user(asUserDetails(ownerA))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Cryptographic audit integrity verification endpoint returns valid response")
        void verifyAuditIntegrityEndpoint() throws Exception {
            persistTestAuditEvent(orgA.getId(), ownerA.getId(), "AUTHORIZATION_ALLOWED", "ALLOW", 1L);

            mockMvc.perform(get("/api/v1/organizations/{organizationId}/security/audit-logs/integrity", orgA.getId())
                            .with(user(asUserDetails(ownerA))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(true))
                    .andExpect(jsonPath("$.tampered").value(false))
                    .andExpect(jsonPath("$.totalRecordsVerified").isNumber());
        }

        @Test
        @DisplayName("Regular non-admin member is forbidden from accessing audit logs")
        void memberForbiddenFromAuditLogs() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/{organizationId}/security/audit-logs", orgA.getId())
                            .with(user(asUserDetails(devA))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Audit endpoints reject modification: POST, PUT, DELETE return 405 Method Not Allowed")
        void auditEndpointsRejectModification() throws Exception {
            mockMvc.perform(post("/api/v1/organizations/{organizationId}/security/audit-logs", orgA.getId())
                            .with(user(asUserDetails(ownerA))))
                    .andExpect(status().isMethodNotAllowed());

            mockMvc.perform(put("/api/v1/organizations/{organizationId}/security/audit-logs/some-id", orgA.getId())
                            .with(user(asUserDetails(ownerA))))
                    .andExpect(status().isMethodNotAllowed());

            mockMvc.perform(delete("/api/v1/organizations/{organizationId}/security/audit-logs/some-id", orgA.getId())
                            .with(user(asUserDetails(ownerA))))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

    @Nested
    @DisplayName("Security Event Endpoints")
    class SecurityEventTests {

        @Test
        @DisplayName("List and retrieve security events within organization")
        void listAndRetrieveSecurityEvents() throws Exception {
            SecurityEventEntity event = eventRepository.save(SecurityEventEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .organizationId(orgA.getId().toString())
                    .ruleId("AUTHZ_REPEATED_DENIALS")
                    .ruleName("Repeated Denials")
                    .severity(SecuritySeverity.MEDIUM)
                    .title("Repeated Denials Detected")
                    .description("Test description")
                    .actorId(devA.getId().toString())
                    .detectedAt(Instant.now())
                    .build());

            mockMvc.perform(get("/api/v1/organizations/{organizationId}/security/events", orgA.getId())
                            .with(user(asUserDetails(ownerA))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].ruleId").value("AUTHZ_REPEATED_DENIALS"));

            mockMvc.perform(get("/api/v1/organizations/{organizationId}/security/events/{eventId}",
                            orgA.getId(), event.getId())
                            .with(user(asUserDetails(adminA))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(event.getId()))
                    .andExpect(jsonPath("$.title").value("Repeated Denials Detected"));
        }
    }

    @Nested
    @DisplayName("Security Alert Management Endpoints")
    class SecurityAlertTests {

        @Test
        @DisplayName("Acknowledge open security alert")
        void acknowledgeSecurityAlert() throws Exception {
            SecurityAlertEntity alert = alertRepository.save(SecurityAlertEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .organizationId(orgA.getId().toString())
                    .ruleId("AUTHZ_CROSS_TENANT_VIOLATION")
                    .severity(SecuritySeverity.HIGH)
                    .status(SecurityAlertStatus.OPEN)
                    .title("Cross-tenant attempt")
                    .description("Repeated cross-tenant violation")
                    .actorId(devA.getId().toString())
                    .firstSeenAt(Instant.now().minusSeconds(300))
                    .lastSeenAt(Instant.now())
                    .occurrenceCount(3)
                    .latestEventId("evt-1")
                    .build());

            mockMvc.perform(post("/api/v1/organizations/{organizationId}/security/alerts/{alertId}/acknowledge",
                            orgA.getId(), alert.getId())
                            .with(user(asUserDetails(adminA))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                    .andExpect(jsonPath("$.acknowledgedBy").value(adminA.getId().toString()));
        }

        @Test
        @DisplayName("Resolve security alert with explicit resolution reason")
        void resolveSecurityAlert() throws Exception {
            SecurityAlertEntity alert = alertRepository.save(SecurityAlertEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .organizationId(orgA.getId().toString())
                    .ruleId("AUTHZ_CROSS_TENANT_VIOLATION")
                    .severity(SecuritySeverity.HIGH)
                    .status(SecurityAlertStatus.ACKNOWLEDGED)
                    .title("Cross-tenant attempt")
                    .description("Repeated cross-tenant violation")
                    .actorId(devA.getId().toString())
                    .firstSeenAt(Instant.now().minusSeconds(300))
                    .lastSeenAt(Instant.now())
                    .occurrenceCount(3)
                    .latestEventId("evt-1")
                    .build());

            ResolveSecurityAlertRequest request = ResolveSecurityAlertRequest.builder()
                    .resolutionReason("Investigated: developer misconfigured project test script")
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{organizationId}/security/alerts/{alertId}/resolve",
                            orgA.getId(), alert.getId())
                            .with(user(asUserDetails(ownerA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RESOLVED"))
                    .andExpect(jsonPath("$.resolvedBy").value(ownerA.getId().toString()))
                    .andExpect(jsonPath("$.resolutionReason").value("Investigated: developer misconfigured project test script"));
        }

        @Test
        @DisplayName("Resolve security alert with blank reason returns 400 Bad Request")
        void resolveAlertWithBlankReasonReturnsBadRequest() throws Exception {
            UUID alertId = UUID.randomUUID();
            ResolveSecurityAlertRequest request = ResolveSecurityAlertRequest.builder()
                    .resolutionReason("   ")
                    .build();

            mockMvc.perform(post("/api/v1/organizations/{organizationId}/security/alerts/{alertId}/resolve",
                            orgA.getId(), alertId)
                            .with(user(asUserDetails(ownerA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Tenant Isolation & Access Control")
    class TenantIsolationTests {

        @Test
        @DisplayName("User from Org B cannot access Org A security monitoring endpoints")
        void crossTenantAccessRejectedWithForbidden() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/{organizationId}/security/audit-logs", orgA.getId())
                            .with(user(asUserDetails(ownerB))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(get("/api/v1/organizations/{organizationId}/security/events", orgA.getId())
                            .with(user(asUserDetails(ownerB))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(get("/api/v1/organizations/{organizationId}/security/alerts", orgA.getId())
                            .with(user(asUserDetails(ownerB))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Unauthenticated request is rejected")
        void unauthenticatedRequestRejected() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/{organizationId}/security/audit-logs", orgA.getId()))
                    .andExpect(status().isUnauthorized());
        }
    }

    private AuthorizationAuditEventEntity persistTestAuditEvent(
            UUID organizationId, UUID userId, String eventType, String decision, long sequence) {
        Instant time = Instant.now();
        String eventId = UUID.randomUUID().toString();
        String prevHash = AuditIntegrityService.GENESIS_HASH;
        String hash = integrityService.computeRecordHash(
                sequence, eventId, time, eventType, userId.toString(),
                "ORGANIZATION", organizationId.toString(), null, "project", "p-1",
                decision, null, null, null, null, null, null, prevHash
        );

        AuthorizationAuditEventEntity entity = AuthorizationAuditEventEntity.builder()
                .id(UUID.randomUUID().toString())
                .sequenceNumber(sequence)
                .eventId(eventId)
                .timestamp(time)
                .eventType(eventType)
                .userId(userId.toString())
                .scope("ORGANIZATION")
                .organizationId(organizationId.toString())
                .resourceType("project")
                .resourceId("p-1")
                .decision(decision)
                .recordHash(hash)
                .previousRecordHash(prevHash)
                .build();

        return auditEventRepository.save(entity);
    }

    private User createUser(String prefix) {
        return userRepository.save(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@devflow.io")
                .username(prefix + "_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());
    }

    private OrganizationMember createOrgMember(Organization org, User user, OrganizationRole role) {
        return organizationMemberRepository.save(OrganizationMember.builder()
                .organization(org)
                .user(user)
                .role(role)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());
    }
}
