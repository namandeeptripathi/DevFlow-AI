package com.devflow.role;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.domain.OrganizationMember;
import com.devflow.organization.domain.OrganizationMembershipStatus;
import com.devflow.organization.domain.OrganizationRole;
import com.devflow.organization.repository.OrganizationMemberRepository;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.permission.domain.PermissionEntity;
import com.devflow.permission.repository.PermissionRepository;
import com.devflow.role.domain.CustomRoleAssignment;
import com.devflow.role.domain.CustomRoleEntity;
import com.devflow.role.domain.CustomRolePermissionEntity;
import com.devflow.role.dto.CreateCustomRoleRequest;
import com.devflow.role.dto.UpdateCustomRoleRequest;
import com.devflow.role.exception.CustomRoleAlreadyExistsException;
import com.devflow.role.exception.CustomRoleDeletionException;
import com.devflow.role.repository.CustomRoleAssignmentRepository;
import com.devflow.role.repository.CustomRolePermissionRepository;
import com.devflow.role.repository.CustomRoleRepository;
import com.devflow.role.service.CustomRoleService;
import com.devflow.role.service.RoleAssignmentService;
import com.devflow.user.domain.AccountStatus;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Stage 7: Custom Role Data Model & Persistence Integration Tests")
class CustomRolePersistenceIntegrationTest {

    @Autowired
    private CustomRoleRepository customRoleRepository;

    @Autowired
    private CustomRolePermissionRepository customRolePermissionRepository;

    @Autowired
    private CustomRoleAssignmentRepository customRoleAssignmentRepository;

    @Autowired
    private CustomRoleService customRoleService;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private EntityManager entityManager;

    private Organization orgA;
    private Organization orgB;
    private User ownerA;
    private User adminA;
    private User memberA;
    private User ownerB;

    @BeforeEach
    void setUp() {
        ownerA = createUser("owner-a");
        adminA = createUser("admin-a");
        memberA = createUser("member-a");
        ownerB = createUser("owner-b");

        orgA = organizationRepository.save(Organization.builder()
                .name("Acme Corp")
                .slug("acme-" + UUID.randomUUID())
                .owner(ownerA)
                .build());

        orgB = organizationRepository.save(Organization.builder()
                .name("Globex Corp")
                .slug("globex-" + UUID.randomUUID())
                .owner(ownerB)
                .build());

        addMember(orgA, ownerA, OrganizationRole.OWNER);
        addMember(orgA, adminA, OrganizationRole.ADMIN);
        addMember(orgA, memberA, OrganizationRole.DEVELOPER);

        addMember(orgB, ownerB, OrganizationRole.OWNER);

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("Create custom role with valid permissions, display name, description and audit metadata")
    void createCustomRoleSuccessfully() {
        CreateCustomRoleRequest request = CreateCustomRoleRequest.builder()
                .name("QA_ENGINEER")
                .displayName("Quality Assurance Engineer")
                .description("Responsible for end-to-end testing and QA")
                .permissions(Set.of("project.read", "task.read", "task.write"))
                .build();

        CustomRoleEntity created = customRoleService.createCustomRole(orgA.getId(), ownerA.getId(), request);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getName()).isEqualTo("QA_ENGINEER");
        assertThat(created.getDisplayName()).isEqualTo("Quality Assurance Engineer");
        assertThat(created.getDescription()).isEqualTo("Responsible for end-to-end testing and QA");
        assertThat(created.isActive()).isTrue();
        assertThat(created.getCreatedBy().getId()).isEqualTo(ownerA.getId());
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();
        assertThat(created.getVersion()).isNotNull();
        assertThat(created.getRolePermissions()).hasSize(3);

        entityManager.flush();
        entityManager.clear();

        CustomRoleEntity fetched = customRoleRepository.findByIdAndOrganizationIdWithPermissions(created.getId(), orgA.getId()).orElseThrow();
        assertThat(fetched.getRolePermissions()).hasSize(3);
    }

    @Test
    @DisplayName("Enforce organization-level unique role name constraint")
    void enforceOrganizationLevelUniqueNameConstraint() {
        CreateCustomRoleRequest request1 = CreateCustomRoleRequest.builder()
                .name("RELEASE_MANAGER")
                .displayName("Release Manager")
                .permissions(Set.of("project.read"))
                .build();

        customRoleService.createCustomRole(orgA.getId(), ownerA.getId(), request1);

        CreateCustomRoleRequest requestDuplicate = CreateCustomRoleRequest.builder()
                .name("release_manager") // Case-insensitive normalized to RELEASE_MANAGER
                .displayName("Another Release Manager")
                .permissions(Set.of("project.read"))
                .build();

        assertThatThrownBy(() -> customRoleService.createCustomRole(orgA.getId(), ownerA.getId(), requestDuplicate))
                .isInstanceOf(CustomRoleAlreadyExistsException.class)
                .hasMessageContaining("already exists in organization");
    }

    @Test
    @DisplayName("Allow identical custom role name in a different organization tenant")
    void allowSameRoleNameInDifferentOrganizations() {
        CreateCustomRoleRequest requestA = CreateCustomRoleRequest.builder()
                .name("SECURITY_REVIEWER")
                .displayName("Security Reviewer")
                .permissions(Set.of("project.read"))
                .build();

        CreateCustomRoleRequest requestB = CreateCustomRoleRequest.builder()
                .name("SECURITY_REVIEWER")
                .displayName("Security Reviewer")
                .permissions(Set.of("project.read"))
                .build();

        CustomRoleEntity roleA = customRoleService.createCustomRole(orgA.getId(), ownerA.getId(), requestA);
        CustomRoleEntity roleB = customRoleService.createCustomRole(orgB.getId(), ownerB.getId(), requestB);

        assertThat(roleA.getId()).isNotEqualTo(roleB.getId());
        assertThat(roleA.getOrganization().getId()).isEqualTo(orgA.getId());
        assertThat(roleB.getOrganization().getId()).isEqualTo(orgB.getId());
        assertThat(roleA.getName()).isEqualTo(roleB.getName());
    }

    @Test
    @DisplayName("Update custom role display name, description, active status and replace permissions")
    void updateCustomRoleSuccessfully() {
        CreateCustomRoleRequest createRequest = CreateCustomRoleRequest.builder()
                .name("DEV_COORDINATOR")
                .displayName("Dev Coordinator")
                .permissions(Set.of("project.read", "task.read"))
                .build();

        CustomRoleEntity role = customRoleService.createCustomRole(orgA.getId(), ownerA.getId(), createRequest);

        UpdateCustomRoleRequest updateRequest = UpdateCustomRoleRequest.builder()
                .displayName("Senior Project Coordinator")
                .description("Updated duties")
                .permissions(Set.of("project.read", "project.write", "member.invite"))
                .active(false)
                .build();

        CustomRoleEntity updated = customRoleService.updateCustomRole(orgA.getId(), role.getId(), ownerA.getId(), updateRequest);

        assertThat(updated.getDisplayName()).isEqualTo("Senior Project Coordinator");
        assertThat(updated.getDescription()).isEqualTo("Updated duties");
        assertThat(updated.isActive()).isFalse();

        entityManager.flush();
        entityManager.clear();

        CustomRoleEntity reloaded = customRoleRepository.findByIdAndOrganizationIdWithPermissions(role.getId(), orgA.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isFalse();
        assertThat(reloaded.getRolePermissions()).hasSize(3);
    }

    @Test
    @DisplayName("Prevent deletion of custom role while active assignments depend on it")
    void preventDeletionWithActiveAssignments() {
        CreateCustomRoleRequest createRequest = CreateCustomRoleRequest.builder()
                .name("ANALYST")
                .displayName("Read-only Analyst")
                .permissions(Set.of("project.read", "analytics.view"))
                .build();

        CustomRoleEntity role = customRoleService.createCustomRole(orgA.getId(), ownerA.getId(), createRequest);

        // Assign role to memberA
        roleAssignmentService.assignCustomRole(orgA.getId(), memberA.getId(), role.getId(), ownerA.getId(), null);

        entityManager.flush();
        entityManager.clear();

        // Attempt deletion should throw CustomRoleDeletionException
        assertThatThrownBy(() -> customRoleService.deleteCustomRole(orgA.getId(), role.getId(), ownerA.getId()))
                .isInstanceOf(CustomRoleDeletionException.class)
                .hasMessageContaining("active assignment(s) still depend on it");

        // Verify role still exists and is active
        CustomRoleEntity stillExists = customRoleRepository.findById(role.getId()).orElseThrow();
        assertThat(stillExists.isActive()).isTrue();
    }

    @Test
    @DisplayName("Safely deactivates custom role when only historical (revoked) assignments exist")
    void safelyDeactivateRoleWhenHistoricalAssignmentsExist() {
        CreateCustomRoleRequest createRequest = CreateCustomRoleRequest.builder()
                .name("TEMP_SPECIALIST")
                .displayName("Temporary Specialist")
                .permissions(Set.of("project.read"))
                .build();

        CustomRoleEntity role = customRoleService.createCustomRole(orgA.getId(), ownerA.getId(), createRequest);

        // Assign role to memberA
        CustomRoleAssignment assignment = roleAssignmentService.assignCustomRole(
                orgA.getId(), memberA.getId(), role.getId(), ownerA.getId(), null
        );

        // Revoke assignment
        roleAssignmentService.revokeCustomRoleAssignment(orgA.getId(), assignment.getId(), ownerA.getId(), "Contract completed");

        entityManager.flush();
        entityManager.clear();

        // Now deleteCustomRole should safely deactivate instead of hard delete
        customRoleService.deleteCustomRole(orgA.getId(), role.getId(), ownerA.getId());

        entityManager.flush();
        entityManager.clear();

        CustomRoleEntity archivedRole = customRoleRepository.findById(role.getId()).orElseThrow();
        assertThat(archivedRole.isActive()).isFalse();

        // Historical assignment is preserved
        CustomRoleAssignment historical = customRoleAssignmentRepository.findById(assignment.getId()).orElseThrow();
        assertThat(historical.isRevoked()).isTrue();
        assertThat(historical.getRevocationReason()).isEqualTo("Contract completed");
    }

    @Test
    @DisplayName("Hard deletes custom role when no historical assignments have ever been created")
    void hardDeleteUnusedCustomRole() {
        CreateCustomRoleRequest createRequest = CreateCustomRoleRequest.builder()
                .name("UNUSED_ROLE")
                .displayName("Unused Role")
                .permissions(Set.of("project.read"))
                .build();

        CustomRoleEntity role = customRoleService.createCustomRole(orgA.getId(), ownerA.getId(), createRequest);

        entityManager.flush();
        entityManager.clear();

        customRoleService.deleteCustomRole(orgA.getId(), role.getId(), ownerA.getId());

        entityManager.flush();
        entityManager.clear();

        assertThat(customRoleRepository.findById(role.getId())).isEmpty();
    }

    @Test
    @DisplayName("Enforce single active assignment per custom role and user")
    void enforceSingleActiveAssignmentPerRoleAndUser() {
        CreateCustomRoleRequest createRequest = CreateCustomRoleRequest.builder()
                .name("AUDITOR")
                .displayName("Auditor")
                .permissions(Set.of("project.read"))
                .build();

        CustomRoleEntity role = customRoleService.createCustomRole(orgA.getId(), ownerA.getId(), createRequest);

        roleAssignmentService.assignCustomRole(orgA.getId(), memberA.getId(), role.getId(), ownerA.getId(), null);

        entityManager.flush();
        entityManager.clear();

        // Attempting duplicate active assignment should be rejected
        assertThatThrownBy(() -> roleAssignmentService.assignCustomRole(
                orgA.getId(), memberA.getId(), role.getId(), ownerA.getId(), null
        )).isInstanceOf(Exception.class);
    }

    private User createUser(String username) {
        return userRepository.save(User.builder()
                .username(username + "-" + UUID.randomUUID().toString().substring(0, 8))
                .email(username + "@devflow.io")
                .passwordHash("$2a$10$hashedpassword")
                .accountStatus(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build());
    }

    private OrganizationMember addMember(Organization org, User user, OrganizationRole role) {
        return organizationMemberRepository.save(OrganizationMember.builder()
                .organization(org)
                .user(user)
                .role(role)
                .status(OrganizationMembershipStatus.ACTIVE)
                .build());
    }
}
