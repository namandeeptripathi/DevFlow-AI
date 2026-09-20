package com.devflow.workspace;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.security.user.DevFlowUserDetails;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import com.devflow.workspace.domain.Workspace;
import com.devflow.workspace.domain.WorkspaceVisibility;
import com.devflow.workspace.dto.CreateWorkspaceRequest;
import com.devflow.workspace.dto.UpdateWorkspaceRequest;
import com.devflow.workspace.dto.WorkspaceResponse;
import com.devflow.workspace.exception.WorkspaceAccessDeniedException;
import com.devflow.workspace.exception.WorkspaceAlreadyExistsException;
import com.devflow.workspace.exception.WorkspaceNotFoundException;
import com.devflow.workspace.repository.WorkspaceRepository;
import com.devflow.workspace.service.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-End integration tests for the Workspace module.
 *
 * <p>Exercises the full stack against the live PostgreSQL test database:
 * <ul>
 *   <li>Flyway schema migration execution (V1 - V11)</li>
 *   <li>JPA entity mapping, constraints, auditing, and optimistic locking</li>
 *   <li>{@link WorkspaceRepository} query execution</li>
 *   <li>{@link WorkspaceService} business logic and multi-tenant isolation</li>
 *   <li>{@link com.devflow.workspace.controller.WorkspaceController} REST API and security principal resolution</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:5432/devflow_test",
    "spring.datasource.username=devflow",
    "spring.datasource.password=devflow_secret",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "devflow.security.cors.allowed-origins[0]=http://localhost:3000"
})
@Transactional
@DisplayName("Workspace End-to-End Integration Tests")
class WorkspaceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private EntityManager entityManager;

    private User ownerUser;
    private User adminUser;
    private User devUser;
    private Organization orgA;
    private Organization orgB;

    @BeforeEach
    void setUp() {
        // Create test users
        ownerUser = userRepository.save(User.builder()
                .email("owner-" + UUID.randomUUID() + "@devflow.com")
                .username("owner_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed_pw")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        adminUser = userRepository.save(User.builder()
                .email("admin-" + UUID.randomUUID() + "@devflow.com")
                .username("admin_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed_pw")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        devUser = userRepository.save(User.builder()
                .email("dev-" + UUID.randomUUID() + "@devflow.com")
                .username("dev_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed_pw")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        // Create test organizations
        orgA = organizationRepository.save(Organization.builder()
                .name("Organization Alpha")
                .slug("org-alpha-" + UUID.randomUUID().toString().substring(0, 8))
                .owner(ownerUser)
                .build());

        orgB = organizationRepository.save(Organization.builder()
                .name("Organization Beta")
                .slug("org-beta-" + UUID.randomUUID().toString().substring(0, 8))
                .owner(ownerUser)
                .build());

        // Assign memberships in orgA
        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA)
                .user(ownerUser)
                .role(OrganizationRole.OWNER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA)
                .user(adminUser)
                .role(OrganizationRole.ADMIN)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA)
                .user(devUser)
                .role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        // Assign membership in orgB for ownerUser only
        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgB)
                .user(ownerUser)
                .role(OrganizationRole.OWNER)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());

        entityManager.flush();
    }

    // ── 1. REPOSITORY & PERSISTENCE INTEGRATION ───────────────────────────────

    @Nested
    @DisplayName("Repository & Entity Persistence")
    class RepositoryPersistenceTests {

        @Test
        @DisplayName("Workspace entity persists with auditing timestamps and version in PostgreSQL")
        void save_persistsEntityWithAuditingAndVersion() {
            Workspace ws = Workspace.builder()
                    .name("Infrastructure")
                    .organization(orgA)
                    .visibility(WorkspaceVisibility.PUBLIC)
                    .description("Core infra team")
                    .build();

            Workspace saved = workspaceRepository.save(ws);
            entityManager.flush();
            entityManager.clear();

            Workspace reloaded = workspaceRepository.findById(saved.getId()).orElseThrow();
            assertThat(reloaded.getId()).isNotNull();
            assertThat(reloaded.getName()).isEqualTo("Infrastructure");
            assertThat(reloaded.getVisibility()).isEqualTo(WorkspaceVisibility.PUBLIC);
            assertThat(reloaded.getDescription()).isEqualTo("Core infra team");
            assertThat(reloaded.getCreatedAt()).isNotNull();
            assertThat(reloaded.getUpdatedAt()).isNotNull();
            assertThat(reloaded.getVersion()).isEqualTo(0L);
            assertThat(reloaded.getOrganization().getId()).isEqualTo(orgA.getId());
        }

        @Test
        @DisplayName("findByOrganizationId returns only workspaces belonging to target organization")
        void findByOrganizationId_scopesToOrganization() {
            Workspace wsA = workspaceRepository.save(Workspace.builder()
                    .name("Alpha WS")
                    .organization(orgA)
                    .visibility(WorkspaceVisibility.PUBLIC)
                    .build());

            Workspace wsB = workspaceRepository.save(Workspace.builder()
                    .name("Beta WS")
                    .organization(orgB)
                    .visibility(WorkspaceVisibility.PUBLIC)
                    .build());

            entityManager.flush();
            entityManager.clear();

            List<Workspace> listA = workspaceRepository.findByOrganizationId(orgA.getId());
            assertThat(listA).extracting(Workspace::getName).contains("Alpha WS");
            assertThat(listA).extracting(Workspace::getName).doesNotContain("Beta WS");
        }

        @Test
        @DisplayName("findByIdAndOrganizationId returns empty when organization ID mismatch")
        void findByIdAndOrganizationId_mismatchedOrg_returnsEmpty() {
            Workspace wsA = workspaceRepository.save(Workspace.builder()
                    .name("Target WS")
                    .organization(orgA)
                    .visibility(WorkspaceVisibility.PUBLIC)
                    .build());

            entityManager.flush();
            entityManager.clear();

            Optional<Workspace> found = workspaceRepository.findByIdAndOrganizationId(wsA.getId(), orgB.getId());
            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("findByOrganizationIdAndVisibility filters by visibility correctly")
        void findByOrganizationIdAndVisibility_filtersCorrectly() {
            workspaceRepository.save(Workspace.builder()
                    .name("Public One")
                    .organization(orgA)
                    .visibility(WorkspaceVisibility.PUBLIC)
                    .build());

            workspaceRepository.save(Workspace.builder()
                    .name("Private One")
                    .organization(orgA)
                    .visibility(WorkspaceVisibility.PRIVATE)
                    .build());

            entityManager.flush();
            entityManager.clear();

            List<Workspace> publicOnly = workspaceRepository.findByOrganizationIdAndVisibility(
                    orgA.getId(), WorkspaceVisibility.PUBLIC);

            assertThat(publicOnly).hasSize(1);
            assertThat(publicOnly.get(0).getName()).isEqualTo("Public One");
        }

        @Test
        @DisplayName("database unique constraint uk_workspaces_org_name rejects exact duplicate in same org")
        void databaseUniqueConstraint_rejectsDuplicateInSameOrg() {
            workspaceRepository.save(Workspace.builder()
                    .name("Platform")
                    .organization(orgA)
                    .visibility(WorkspaceVisibility.PUBLIC)
                    .build());
            entityManager.flush();

            Workspace duplicate = Workspace.builder()
                    .name("Platform")
                    .organization(orgA)
                    .visibility(WorkspaceVisibility.PUBLIC)
                    .build();

            assertThatThrownBy(() -> {
                workspaceRepository.save(duplicate);
                entityManager.flush();
            }).hasMessageContaining("uk_workspaces_org_name");
        }
    }

    // ── 2. SERVICE + REPOSITORY INTEGRATION ────────────────────────────────────

    @Nested
    @DisplayName("Service + Repository Integration")
    class ServiceIntegrationTests {

        @Test
        @DisplayName("createWorkspace trims name, defaults to PUBLIC, and persists to database")
        void createWorkspace_trimsAndDefaultsToPublic() {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("   DevOps Team   ")
                    .visibility(null)
                    .description("   Cloud infrastructure   ")
                    .build();

            WorkspaceResponse response = workspaceService.createWorkspace(orgA.getId(), request, adminUser.getId());
            entityManager.flush();
            entityManager.clear();

            Workspace persisted = workspaceRepository.findById(response.getId()).orElseThrow();
            assertThat(persisted.getName()).isEqualTo("DevOps Team");
            assertThat(persisted.getVisibility()).isEqualTo(WorkspaceVisibility.PUBLIC);
            assertThat(persisted.getDescription()).isEqualTo("Cloud infrastructure");
        }

        @Test
        @DisplayName("createWorkspace enforces case-insensitive name uniqueness in same organization")
        void createWorkspace_enforcesCaseInsensitiveUniqueness() {
            CreateWorkspaceRequest req1 = CreateWorkspaceRequest.builder()
                    .name("Product Design")
                    .build();
            workspaceService.createWorkspace(orgA.getId(), req1, adminUser.getId());

            CreateWorkspaceRequest req2 = CreateWorkspaceRequest.builder()
                    .name("product design")
                    .build();

            assertThatThrownBy(() -> workspaceService.createWorkspace(orgA.getId(), req2, adminUser.getId()))
                    .isInstanceOf(WorkspaceAlreadyExistsException.class)
                    .hasMessageContaining("product design");
        }

        @Test
        @DisplayName("same workspace name is allowed in different organizations")
        void createWorkspace_sameNameAllowedInDifferentOrganizations() {
            CreateWorkspaceRequest req = CreateWorkspaceRequest.builder()
                    .name("Mobile")
                    .build();

            WorkspaceResponse resA = workspaceService.createWorkspace(orgA.getId(), req, ownerUser.getId());
            WorkspaceResponse resB = workspaceService.createWorkspace(orgB.getId(), req, ownerUser.getId());

            assertThat(resA.getName()).isEqualTo("Mobile");
            assertThat(resB.getName()).isEqualTo("Mobile");
            assertThat(resA.getOrganizationId()).isEqualTo(orgA.getId());
            assertThat(resB.getOrganizationId()).isEqualTo(orgB.getId());
        }

        @Test
        @DisplayName("updateWorkspace modifies fields and increments version")
        void updateWorkspace_modifiesAndIncrementsVersion() {
            WorkspaceResponse created = workspaceService.createWorkspace(
                    orgA.getId(),
                    CreateWorkspaceRequest.builder().name("Backend").description("Initial").build(),
                    adminUser.getId()
            );
            entityManager.flush();
            entityManager.clear();

            UpdateWorkspaceRequest updateReq = UpdateWorkspaceRequest.builder()
                    .name("Backend Core")
                    .visibility(WorkspaceVisibility.PRIVATE)
                    .description("Updated description")
                    .build();

            WorkspaceResponse updated = workspaceService.updateWorkspace(
                    orgA.getId(), created.getId(), updateReq, adminUser.getId());
            entityManager.flush();
            entityManager.clear();

            Workspace reloaded = workspaceRepository.findById(created.getId()).orElseThrow();
            assertThat(reloaded.getName()).isEqualTo("Backend Core");
            assertThat(reloaded.getVisibility()).isEqualTo(WorkspaceVisibility.PRIVATE);
            assertThat(reloaded.getDescription()).isEqualTo("Updated description");
            assertThat(reloaded.getVersion()).isGreaterThan(0L);
        }

        @Test
        @DisplayName("deleteWorkspace removes workspace from database")
        void deleteWorkspace_removesFromDatabase() {
            WorkspaceResponse created = workspaceService.createWorkspace(
                    orgA.getId(),
                    CreateWorkspaceRequest.builder().name("Temporary").build(),
                    adminUser.getId()
            );
            entityManager.flush();

            workspaceService.deleteWorkspace(orgA.getId(), created.getId(), adminUser.getId());
            entityManager.flush();

            assertThat(workspaceRepository.findById(created.getId())).isEmpty();
        }
    }

    // ── 3. CONTROLLER + SERVICE + DATABASE (REST E2E) ──────────────────────────

    @Nested
    @DisplayName("REST Controller Full Integration")
    class ControllerRestIntegrationTests {

        @Test
        @DisplayName("POST /api/v1/organizations/{orgId}/workspaces creates workspace in database (201)")
        void postWorkspace_createsInDatabase() throws Exception {
            CreateWorkspaceRequest request = CreateWorkspaceRequest.builder()
                    .name("QA Engineering")
                    .visibility(WorkspaceVisibility.PUBLIC)
                    .description("Quality Assurance")
                    .build();

            DevFlowUserDetails adminPrincipal = new DevFlowUserDetails(adminUser);

            mockMvc.perform(post("/api/v1/organizations/{organizationId}/workspaces", orgA.getId())
                            .with(user(adminPrincipal))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.name").value("QA Engineering"))
                    .andExpect(jsonPath("$.organizationId").value(orgA.getId().toString()));

            entityManager.flush();
            assertThat(workspaceRepository.existsByOrganizationIdAndNameIgnoreCase(orgA.getId(), "QA Engineering"))
                    .isTrue();
        }

        @Test
        @DisplayName("GET /api/v1/organizations/{orgId}/workspaces returns persisted workspaces (200)")
        void getWorkspaces_returnsPersistedWorkspaces() throws Exception {
            workspaceService.createWorkspace(orgA.getId(),
                    CreateWorkspaceRequest.builder().name("Design System").build(), adminUser.getId());
            entityManager.flush();

            DevFlowUserDetails adminPrincipal = new DevFlowUserDetails(adminUser);

            mockMvc.perform(get("/api/v1/organizations/{organizationId}/workspaces", orgA.getId())
                            .with(user(adminPrincipal)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.name == 'Design System')]").exists());
        }

        @Test
        @DisplayName("PATCH /api/v1/organizations/{orgId}/workspaces/{id} updates database state (200)")
        void patchWorkspace_updatesDatabaseState() throws Exception {
            WorkspaceResponse ws = workspaceService.createWorkspace(orgA.getId(),
                    CreateWorkspaceRequest.builder().name("Old Name").build(), adminUser.getId());
            entityManager.flush();

            UpdateWorkspaceRequest patchRequest = UpdateWorkspaceRequest.builder()
                    .name("New Patched Name")
                    .build();

            DevFlowUserDetails adminPrincipal = new DevFlowUserDetails(adminUser);

            mockMvc.perform(patch("/api/v1/organizations/{organizationId}/workspaces/{workspaceId}",
                            orgA.getId(), ws.getId())
                            .with(user(adminPrincipal))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(patchRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("New Patched Name"));

            entityManager.flush();
            Workspace reloaded = workspaceRepository.findById(ws.getId()).orElseThrow();
            assertThat(reloaded.getName()).isEqualTo("New Patched Name");
        }

        @Test
        @DisplayName("DELETE /api/v1/organizations/{orgId}/workspaces/{id} deletes from database (204)")
        void deleteWorkspace_removesFromDatabaseViaHttp() throws Exception {
            WorkspaceResponse ws = workspaceService.createWorkspace(orgA.getId(),
                    CreateWorkspaceRequest.builder().name("To Delete").build(), adminUser.getId());
            entityManager.flush();

            DevFlowUserDetails adminPrincipal = new DevFlowUserDetails(adminUser);

            mockMvc.perform(delete("/api/v1/organizations/{organizationId}/workspaces/{workspaceId}",
                            orgA.getId(), ws.getId())
                            .with(user(adminPrincipal)))
                    .andExpect(status().isNoContent());

            entityManager.flush();
            assertThat(workspaceRepository.findById(ws.getId())).isEmpty();
        }
    }

    // ── 4. TENANT ISOLATION INTEGRATION ───────────────────────────────────────

    @Nested
    @DisplayName("Tenant Isolation Verification")
    class TenantIsolationTests {

        @Test
        @DisplayName("cross-tenant GET returns 404 Not Found")
        void crossTenantGet_returns404() throws Exception {
            WorkspaceResponse wsA = workspaceService.createWorkspace(orgA.getId(),
                    CreateWorkspaceRequest.builder().name("Alpha Secret").build(), ownerUser.getId());
            entityManager.flush();

            DevFlowUserDetails ownerPrincipal = new DevFlowUserDetails(ownerUser);

            // Attempting to access Workspace A using Org B's URL path
            mockMvc.perform(get("/api/v1/organizations/{organizationId}/workspaces/{workspaceId}",
                            orgB.getId(), wsA.getId())
                            .with(user(ownerPrincipal)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("cross-tenant PATCH returns 404 Not Found")
        void crossTenantPatch_returns404() throws Exception {
            WorkspaceResponse wsA = workspaceService.createWorkspace(orgA.getId(),
                    CreateWorkspaceRequest.builder().name("Alpha Project").build(), ownerUser.getId());
            entityManager.flush();

            UpdateWorkspaceRequest request = UpdateWorkspaceRequest.builder()
                    .name("Compromised Name")
                    .build();

            DevFlowUserDetails ownerPrincipal = new DevFlowUserDetails(ownerUser);

            mockMvc.perform(patch("/api/v1/organizations/{organizationId}/workspaces/{workspaceId}",
                            orgB.getId(), wsA.getId())
                            .with(user(ownerPrincipal))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());

            entityManager.flush();
            Workspace reloaded = workspaceRepository.findById(wsA.getId()).orElseThrow();
            assertThat(reloaded.getName()).isEqualTo("Alpha Project");
        }

        @Test
        @DisplayName("cross-tenant DELETE returns 404 Not Found")
        void crossTenantDelete_returns404() throws Exception {
            WorkspaceResponse wsA = workspaceService.createWorkspace(orgA.getId(),
                    CreateWorkspaceRequest.builder().name("Alpha Critical").build(), ownerUser.getId());
            entityManager.flush();

            DevFlowUserDetails ownerPrincipal = new DevFlowUserDetails(ownerUser);

            mockMvc.perform(delete("/api/v1/organizations/{organizationId}/workspaces/{workspaceId}",
                            orgB.getId(), wsA.getId())
                            .with(user(ownerPrincipal)))
                    .andExpect(status().isNotFound());

            entityManager.flush();
            assertThat(workspaceRepository.findById(wsA.getId())).isPresent();
        }

        @Test
        @DisplayName("listing workspaces for Org B never returns Org A workspaces")
        void listWorkspaces_strictlyScopedToOrganization() {
            workspaceService.createWorkspace(orgA.getId(),
                    CreateWorkspaceRequest.builder().name("A-1").build(), ownerUser.getId());
            workspaceService.createWorkspace(orgB.getId(),
                    CreateWorkspaceRequest.builder().name("B-1").build(), ownerUser.getId());

            List<WorkspaceResponse> listB = workspaceService.getWorkspaces(orgB.getId(), ownerUser.getId());
            assertThat(listB).extracting(WorkspaceResponse::getName).containsExactly("B-1");
        }
    }

    // ── 5. VISIBILITY INTEGRATION ─────────────────────────────────────────────

    @Nested
    @DisplayName("Visibility Authorization Rules")
    class VisibilityIntegrationTests {

        private WorkspaceResponse publicWs;
        private WorkspaceResponse privateWs;

        @BeforeEach
        void setupWorkspaces() {
            publicWs = workspaceService.createWorkspace(orgA.getId(),
                    CreateWorkspaceRequest.builder()
                            .name("Public Commons")
                            .visibility(WorkspaceVisibility.PUBLIC)
                            .build(),
                    adminUser.getId());

            privateWs = workspaceService.createWorkspace(orgA.getId(),
                    CreateWorkspaceRequest.builder()
                            .name("Secret Projects")
                            .visibility(WorkspaceVisibility.PRIVATE)
                            .build(),
                    adminUser.getId());
            entityManager.flush();
        }

        @Test
        @DisplayName("ADMIN sees both PUBLIC and PRIVATE workspaces in listing")
        void admin_listsPublicAndPrivate() {
            List<WorkspaceResponse> list = workspaceService.getWorkspaces(orgA.getId(), adminUser.getId());
            assertThat(list).extracting(WorkspaceResponse::getName)
                    .contains("Public Commons", "Secret Projects");
        }

        @Test
        @DisplayName("DEVELOPER sees only PUBLIC workspace in listing")
        void developer_listsPublicOnly() {
            List<WorkspaceResponse> list = workspaceService.getWorkspaces(orgA.getId(), devUser.getId());
            assertThat(list).extracting(WorkspaceResponse::getName)
                    .contains("Public Commons")
                    .doesNotContain("Secret Projects");
        }

        @Test
        @DisplayName("DEVELOPER cannot retrieve PRIVATE workspace by ID (403 Forbidden)")
        void developer_cannotGetPrivateWorkspace_throws403() throws Exception {
            DevFlowUserDetails devPrincipal = new DevFlowUserDetails(devUser);

            mockMvc.perform(get("/api/v1/organizations/{organizationId}/workspaces/{workspaceId}",
                            orgA.getId(), privateWs.getId())
                            .with(user(devPrincipal)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("DEVELOPER can retrieve PUBLIC workspace by ID (200 OK)")
        void developer_canGetPublicWorkspace() throws Exception {
            DevFlowUserDetails devPrincipal = new DevFlowUserDetails(devUser);

            mockMvc.perform(get("/api/v1/organizations/{organizationId}/workspaces/{workspaceId}",
                            orgA.getId(), publicWs.getId())
                            .with(user(devPrincipal)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Public Commons"));
        }
    }
}
