package com.devflow.role.registry;

import com.devflow.config.JpaAuditingConfiguration;
import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.AuthorizationRequest;
import com.devflow.modules.auth.api.DenialReason;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.evaluator.AuthorizationEvaluator;
import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.role.adapter.DatabaseMembershipValidator;
import com.devflow.role.adapter.DatabaseProjectAssignmentValidator;
import com.devflow.role.adapter.DatabaseRoleResolver;
import com.devflow.role.adapter.DatabaseTenantBoundaryValidator;
import com.devflow.role.domain.ProjectRegistration;
import com.devflow.role.domain.ProjectRoleAssignment;
import com.devflow.role.exception.InvalidProjectRegistrationException;
import com.devflow.role.exception.InvalidRoleAssignmentException;
import com.devflow.role.repository.OrganizationRoleAssignmentRepository;
import com.devflow.role.repository.ProjectRegistrationRepository;
import com.devflow.role.repository.ProjectRoleAssignmentRepository;
import com.devflow.role.repository.RoleRepository;
import com.devflow.role.service.RoleAssignmentService;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test suite for the durable relational {@link DatabaseProjectRegistry}.
 *
 * <p>Verifies all core properties required for durable project ownership:
 * <ul>
 *   <li>Relational persistence: Ownership survives registry instance recreation and application restarts.</li>
 *   <li>Multi-instance consistency: Multiple registry instances observe consistent state in PostgreSQL.</li>
 *   <li>Database constraint enforcement: Global primary key uniqueness prevents cross-organization conflicts.</li>
 *   <li>Concurrent conflict rejection: Asynchronous multi-thread races guarantee exactly one winner.</li>
 *   <li>Transaction rollback safety: Aborted operations leave zero stale project registrations.</li>
 *   <li>Fail-closed default deny: Unregistered and arbitrary UUIDs are rejected.</li>
 * </ul>
 */
@DataJpaTest
@Import({
    JpaAuditingConfiguration.class,
    RoleAssignmentService.class,
    DatabaseRoleResolver.class,
    DatabaseMembershipValidator.class,
    DatabaseProjectAssignmentValidator.class,
    DatabaseTenantBoundaryValidator.class,
    DatabaseProjectRegistry.class
})
@ActiveProfiles("test")
@DisplayName("Durable Project Ownership Registry Integration Tests")
class DurableProjectRegistryIntegrationTest {

    @Autowired
    private DatabaseProjectRegistry databaseProjectRegistry;

    @Autowired
    private ProjectRegistrationRepository projectRegistrationRepository;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private DatabaseTenantBoundaryValidator databaseTenantBoundaryValidator;

    @Autowired
    private DatabaseProjectAssignmentValidator databaseProjectAssignmentValidator;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRoleAssignmentRepository projectAssignmentRepository;

    @Autowired
    private DatabaseRoleResolver databaseRoleResolver;

    @Autowired
    private DatabaseMembershipValidator databaseMembershipValidator;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    private User ownerA;
    private User adminA;
    private User devA;
    private User adminB;
    private User outsiderB;
    private Organization orgA;
    private Organization orgB;

    @BeforeEach
    void setUp() {
        databaseProjectRegistry.clear();

        ownerA = userRepository.save(User.builder()
                .email("ownerA-" + UUID.randomUUID() + "@devflow.com")
                .username("ownerA_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        adminA = userRepository.save(User.builder()
                .email("adminA-" + UUID.randomUUID() + "@devflow.com")
                .username("adminA_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        devA = userRepository.save(User.builder()
                .email("devA-" + UUID.randomUUID() + "@devflow.com")
                .username("devA_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        adminB = userRepository.save(User.builder()
                .email("adminB-" + UUID.randomUUID() + "@devflow.com")
                .username("adminB_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        outsiderB = userRepository.save(User.builder()
                .email("outsiderB-" + UUID.randomUUID() + "@devflow.com")
                .username("outB_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("hashed")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());

        orgA = organizationRepository.save(Organization.builder()
                .name("Tenant Alpha")
                .slug("alpha-" + UUID.randomUUID().toString().substring(0, 8))
                .owner(ownerA)
                .build());

        orgB = organizationRepository.save(Organization.builder()
                .name("Tenant Beta")
                .slug("beta-" + UUID.randomUUID().toString().substring(0, 8))
                .owner(adminB)
                .build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA).user(ownerA).role(OrganizationRole.OWNER)
                .status(OrganizationMembershipStatus.ACTIVE).build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA).user(adminA).role(OrganizationRole.ADMIN)
                .status(OrganizationMembershipStatus.ACTIVE).build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgA).user(devA).role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE).build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgB).user(adminB).role(OrganizationRole.OWNER)
                .status(OrganizationMembershipStatus.ACTIVE).build());

        organizationMemberRepository.save(OrganizationMember.builder()
                .organization(orgB).user(outsiderB).role(OrganizationRole.DEVELOPER)
                .status(OrganizationMembershipStatus.ACTIVE).build());

        roleAssignmentService.setTenantBoundaryValidator(databaseTenantBoundaryValidator);
        roleAssignmentService.setProjectRegistry(databaseProjectRegistry);

        entityManager.flush();
        entityManager.clear();
    }

    private AuthorizationEvaluator buildEvaluator() {
        return AuthorizationEvaluator.builder()
                .roleResolver(databaseRoleResolver)
                .membershipValidator(databaseMembershipValidator)
                .projectAssignmentValidator(databaseProjectAssignmentValidator)
                .tenantBoundaryValidator(databaseTenantBoundaryValidator)
                .build();
    }

    @Test
    @DisplayName("Property 1: Ownership survives registry instance and service recreation")
    void ownershipSurvivesRegistryRecreation() {
        UUID projectId = UUID.randomUUID();

        // Register via primary registry instance
        databaseProjectRegistry.registerProject(orgA.getId(), projectId, ownerA.getId());
        entityManager.flush();
        entityManager.clear();

        // Simulate application restart by creating a completely new registry instance
        DatabaseProjectRegistry reloadedRegistry = new DatabaseProjectRegistry(
                projectRegistrationRepository, organizationRepository, userRepository);

        assertThat(reloadedRegistry.isProjectRegistered(orgA.getId(), projectId)).isTrue();
        assertThat(reloadedRegistry.getProjectOrganizationId(projectId)).contains(orgA.getId());

        // Verify entity details persisted in PostgreSQL
        ProjectRegistration record = projectRegistrationRepository.findByProjectId(projectId).orElseThrow();
        assertThat(record.getOrganization().getId()).isEqualTo(orgA.getId());
        assertThat(record.getRegisteredBy().getId()).isEqualTo(ownerA.getId());
        assertThat(record.getRegisteredAt()).isNotNull();
    }

    @Test
    @DisplayName("Property 2: Multiple registry instances observe consistent ownership")
    void multipleRegistryInstancesObserveConsistentOwnership() {
        UUID projectId = UUID.randomUUID();

        DatabaseProjectRegistry instance1 = new DatabaseProjectRegistry(
                projectRegistrationRepository, organizationRepository, userRepository);
        DatabaseProjectRegistry instance2 = new DatabaseProjectRegistry(
                projectRegistrationRepository, organizationRepository, userRepository);

        // Instance 1 registers
        instance1.registerProject(orgA.getId(), projectId, adminA.getId());
        entityManager.flush();
        entityManager.clear();

        // Instance 2 immediately observes consistent state
        assertThat(instance2.isProjectRegistered(orgA.getId(), projectId)).isTrue();
        assertThat(instance2.getProjectOrganizationId(projectId)).contains(orgA.getId());

        // Instance 1 unregisters
        instance1.unregisterProject(projectId);
        entityManager.flush();
        entityManager.clear();

        // Instance 2 observes deletion immediately
        assertThat(instance2.isProjectRegistered(orgA.getId(), projectId)).isFalse();
        assertThat(instance2.getProjectOrganizationId(projectId)).isEmpty();
    }

    @Test
    @DisplayName("Property 3: Cross-organization registration conflicts are rejected at application and database level")
    void crossOrganizationRegistrationConflictsAreRejected() {
        UUID projectId = UUID.randomUUID();

        // Register under Org A
        databaseProjectRegistry.registerProject(orgA.getId(), projectId, ownerA.getId());
        entityManager.flush();

        // Attempting to register under Org B via registry throws InvalidProjectRegistrationException
        assertThatThrownBy(() -> databaseProjectRegistry.registerProject(orgB.getId(), projectId, adminB.getId()))
                .isInstanceOf(InvalidProjectRegistrationException.class)
                .hasMessageContaining("already bound to organization");

        // Attempting direct raw database insertion with same projectId under Org B triggers primary key violation
        ProjectRegistration duplicateDbEntity = ProjectRegistration.builder()
                .projectId(projectId)
                .organization(orgB)
                .registeredBy(adminB)
                .registeredAt(Instant.now())
                .build();

        assertThatThrownBy(() -> {
            projectRegistrationRepository.saveAndFlush(duplicateDbEntity);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Property 4: Concurrent registrations across separate service instances cannot create conflicting ownership")
    void concurrentRegistrationsAcrossSeparateInstancesCannotCreateConflictingOwnership() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Commit organizations in a separate transaction so background async threads can see them in PostgreSQL
        Organization committedOrgA = txTemplate.execute(status -> {
            User owner = userRepository.save(User.builder()
                    .email("raceOwnerA-" + UUID.randomUUID() + "@devflow.com")
                    .username("raceA_" + UUID.randomUUID().toString().substring(0, 8))
                    .passwordHash("hashed")
                    .accountStatus(AccountStatus.ACTIVE)
                    .emailVerified(true)
                    .build());
            return organizationRepository.save(Organization.builder()
                    .name("Race Tenant Alpha")
                    .slug("race-alpha-" + UUID.randomUUID().toString().substring(0, 8))
                    .owner(owner)
                    .build());
        });

        Organization committedOrgB = txTemplate.execute(status -> {
            User owner = userRepository.save(User.builder()
                    .email("raceOwnerB-" + UUID.randomUUID() + "@devflow.com")
                    .username("raceB_" + UUID.randomUUID().toString().substring(0, 8))
                    .passwordHash("hashed")
                    .accountStatus(AccountStatus.ACTIVE)
                    .emailVerified(true)
                    .build());
            return organizationRepository.save(Organization.builder()
                    .name("Race Tenant Beta")
                    .slug("race-beta-" + UUID.randomUUID().toString().substring(0, 8))
                    .owner(owner)
                    .build());
        });

        UUID raceProjectId = UUID.randomUUID();

        DatabaseProjectRegistry instance1 = new DatabaseProjectRegistry(
                projectRegistrationRepository, organizationRepository, userRepository);
        DatabaseProjectRegistry instance2 = new DatabaseProjectRegistry(
                projectRegistrationRepository, organizationRepository, userRepository);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        CompletableFuture<Void> task1 = CompletableFuture.runAsync(() -> {
            try {
                instance1.registerProject(committedOrgA.getId(), raceProjectId, null);
                successCount.incrementAndGet();
            } catch (InvalidProjectRegistrationException e) {
                conflictCount.incrementAndGet();
            }
        });

        CompletableFuture<Void> task2 = CompletableFuture.runAsync(() -> {
            try {
                instance2.registerProject(committedOrgB.getId(), raceProjectId, null);
                successCount.incrementAndGet();
            } catch (InvalidProjectRegistrationException e) {
                conflictCount.incrementAndGet();
            }
        });

        CompletableFuture.allOf(task1, task2).join();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // Project is registered to exactly one organization in PostgreSQL
        Optional<UUID> boundOrg = databaseProjectRegistry.getProjectOrganizationId(raceProjectId);
        assertThat(boundOrg).isPresent();
        assertThat(List.of(committedOrgA.getId(), committedOrgB.getId())).contains(boundOrg.get());
    }

    @Test
    @DisplayName("Property 5: Database rollback does not leave stale project registration")
    void databaseRollbackDoesNotLeaveStaleProjectRegistration() {
        UUID rollbackProjectId = UUID.randomUUID();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        try {
            txTemplate.execute(status -> {
                // Initial binding registers project in PostgreSQL
                roleAssignmentService.bindInitialProjectRole(
                        orgA.getId(), rollbackProjectId, devA.getId(), Role.PROJECT_LEAD, adminA.getId(), null);

                // Simulate transactional business failure after registration
                throw new RuntimeException("Simulated business error forcing rollback");
            });
        } catch (RuntimeException ignored) {
        }

        entityManager.clear();

        // Verify rollback left zero stale registration records in PostgreSQL
        assertThat(projectRegistrationRepository.findByProjectId(rollbackProjectId)).isEmpty();
        assertThat(databaseProjectRegistry.isProjectRegistered(orgA.getId(), rollbackProjectId)).isFalse();
        assertThat(databaseTenantBoundaryValidator.isProjectInOrganization(orgA.getId(), rollbackProjectId)).isFalse();
        assertThat(projectAssignmentRepository.findByProjectId(rollbackProjectId)).isEmpty();
    }

    @Test
    @DisplayName("Property 6: Unregistered project authorization is denied (Fail-closed)")
    void unregisteredProjectAuthorizationIsDenied() {
        UUID unregisteredProjectId = UUID.randomUUID();

        assertThat(databaseTenantBoundaryValidator.isProjectInOrganization(orgA.getId(), unregisteredProjectId)).isFalse();
        assertThat(databaseProjectAssignmentValidator.isAssignedToProject(devA.getId(), orgA.getId(), unregisteredProjectId)).isFalse();

        AuthorizationEvaluator evaluator = buildEvaluator();
        AuthorizationDecision decision = evaluator.authorize(
                AuthorizationRequest.project(devA.getId(), orgA.getId(), unregisteredProjectId, Permission.of("task.read")));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.getDenialReason()).contains(DenialReason.TENANT_MISMATCH);
    }

    @Test
    @DisplayName("Property 7: Existing registered project role assignments continue working seamlessly")
    void existingRegisteredProjectRoleAssignmentsContinueWorking() {
        UUID validProjectId = UUID.randomUUID();

        // 1. Initial binding establishes project in registry and grants lead role
        ProjectRoleAssignment leadAssign = roleAssignmentService.bindInitialProjectRole(
                orgA.getId(), validProjectId, adminA.getId(), Role.PROJECT_LEAD, adminA.getId(), null);
        assertThat(leadAssign).isNotNull();

        // 2. Lead assigns member role
        ProjectRoleAssignment memberAssign = roleAssignmentService.assignProjectRole(
                orgA.getId(), validProjectId, devA.getId(), Role.PROJECT_MEMBER, adminA.getId(), null);
        assertThat(memberAssign).isNotNull();

        // 3. Evaluator permits authorized operations
        AuthorizationEvaluator evaluator = buildEvaluator();
        assertThat(evaluator.authorize(
                AuthorizationRequest.project(adminA.getId(), orgA.getId(), validProjectId, Permission.of("task.assign"))).allowed()).isTrue();
        assertThat(evaluator.authorize(
                AuthorizationRequest.project(devA.getId(), orgA.getId(), validProjectId, Permission.of("task.read"))).allowed()).isTrue();

        // 4. Role revocation
        roleAssignmentService.revokeProjectRole(
                orgA.getId(), validProjectId, devA.getId(), Role.PROJECT_MEMBER, adminA.getId(), "Task completion");
        assertThat(evaluator.authorize(
                AuthorizationRequest.project(devA.getId(), orgA.getId(), validProjectId, Permission.of("task.read"))).allowed()).isFalse();
    }

    @Test
    @DisplayName("Property 8: Arbitrary project UUIDs cannot be registered through role assignment alone")
    void arbitraryProjectUUIDsCannotBeRegisteredThroughRoleAssignmentAlone() {
        UUID arbitraryProjectId = UUID.randomUUID();

        assertThatThrownBy(() -> roleAssignmentService.assignProjectRole(
                orgA.getId(), arbitraryProjectId, devA.getId(), Role.PROJECT_MEMBER, adminA.getId(), null))
                .isInstanceOf(InvalidRoleAssignmentException.class)
                .hasMessageContaining("Tenant boundary violation");

        // Verify no backdoor record was inserted into project_registrations
        assertThat(projectRegistrationRepository.findByProjectId(arbitraryProjectId)).isEmpty();
        assertThat(databaseProjectRegistry.isProjectRegistered(orgA.getId(), arbitraryProjectId)).isFalse();
    }

    @Test
    @DisplayName("Property 9: Missing ownership data defaults to deny")
    void missingOwnershipDataDefaultsToDeny() {
        UUID unknownProjectId = UUID.randomUUID();

        // Null guards
        assertThat(databaseProjectRegistry.isProjectRegistered(null, unknownProjectId)).isFalse();
        assertThat(databaseProjectRegistry.isProjectRegistered(orgA.getId(), null)).isFalse();
        assertThat(databaseProjectRegistry.isProjectRegistered(null, null)).isFalse();
        assertThat(databaseProjectRegistry.getProjectOrganizationId(null)).isEmpty();

        // Validator null guards
        assertThat(databaseTenantBoundaryValidator.isProjectInOrganization(null, unknownProjectId)).isFalse();
        assertThat(databaseTenantBoundaryValidator.isProjectInOrganization(orgA.getId(), null)).isFalse();
        assertThat(databaseTenantBoundaryValidator.isProjectInOrganization(null, null)).isFalse();

        // Non-existent organization
        UUID nonExistentOrg = UUID.randomUUID();
        assertThat(databaseTenantBoundaryValidator.isProjectInOrganization(nonExistentOrg, unknownProjectId)).isFalse();
    }
}
