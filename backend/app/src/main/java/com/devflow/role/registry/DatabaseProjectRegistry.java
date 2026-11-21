package com.devflow.role.registry;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.exception.OrganizationNotFoundException;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.role.domain.ProjectRegistration;
import com.devflow.role.exception.InvalidProjectRegistrationException;
import com.devflow.role.repository.ProjectRegistrationRepository;
import com.devflow.user.domain.User;
import com.devflow.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL-backed durable implementation of {@link ProjectRegistry}.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Authoritative persistence: Project tenant ownership is durably stored in the {@code project_registrations} table.</li>
 *   <li>Global uniqueness: Database primary key constraint prevents any project from belonging to multiple organizations.</li>
 *   <li>Multi-instance consistency: Multiple application instances observe the exact same state through PostgreSQL.</li>
 *   <li>Transactional consistency: Shares ambient transaction boundaries with initial role assignment.</li>
 * </ul>
 */
@Component
@Primary
public class DatabaseProjectRegistry implements ProjectRegistry {

    private static final Logger log = LoggerFactory.getLogger(DatabaseProjectRegistry.class);

    private final ProjectRegistrationRepository projectRegistrationRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;

    @Autowired
    public DatabaseProjectRegistry(
            ProjectRegistrationRepository projectRegistrationRepository,
            OrganizationRepository organizationRepository,
            @Autowired(required = false) UserRepository userRepository
    ) {
        this.projectRegistrationRepository = Objects.requireNonNull(
                projectRegistrationRepository, "projectRegistrationRepository must not be null");
        this.organizationRepository = Objects.requireNonNull(
                organizationRepository, "organizationRepository must not be null");
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isProjectRegistered(UUID organizationId, UUID projectId) {
        if (organizationId == null || projectId == null) {
            return false;
        }
        return projectRegistrationRepository.existsByProjectIdAndOrganizationId(projectId, organizationId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> getProjectOrganizationId(UUID projectId) {
        if (projectId == null) {
            return Optional.empty();
        }
        return projectRegistrationRepository.findOrganizationIdByProjectId(projectId);
    }

    @Override
    @Transactional
    public void registerProject(UUID organizationId, UUID projectId) {
        registerProject(organizationId, projectId, null);
    }

    @Override
    @Transactional
    public void registerProject(UUID organizationId, UUID projectId, UUID registeredByUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");

        // Fast-path: Check existing registration
        Optional<UUID> existingOrgId = projectRegistrationRepository.findOrganizationIdByProjectId(projectId);
        if (existingOrgId.isPresent()) {
            if (existingOrgId.get().equals(organizationId)) {
                // Idempotent re-registration under same tenant
                return;
            } else {
                throw new InvalidProjectRegistrationException(
                        "Project [" + projectId + "] is already bound to organization [" + existingOrgId.get() +
                        "] and cannot be bound to organization [" + organizationId + "]");
            }
        }

        Organization org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new OrganizationNotFoundException("Organization not found: " + organizationId));

        User registeredBy = null;
        if (registeredByUserId != null && userRepository != null) {
            registeredBy = userRepository.findById(registeredByUserId).orElse(null);
        }

        ProjectRegistration registration = ProjectRegistration.builder()
                .projectId(projectId)
                .organization(org)
                .registeredBy(registeredBy)
                .registeredAt(Instant.now())
                .build();

        try {
            projectRegistrationRepository.saveAndFlush(registration);
            log.info("Registered project [{}] to organization [{}] by user [{}]", projectId, organizationId, registeredByUserId);
        } catch (DataIntegrityViolationException e) {
            // Concurrent race condition across threads or application instances:
            // The primary key constraint pk_project_registrations guarantees global project uniqueness.
            throw new InvalidProjectRegistrationException(
                    "Project [" + projectId + "] is already bound to another organization", e);
        }
    }

    @Override
    @Transactional
    public void unregisterProject(UUID projectId) {
        if (projectId != null) {
            projectRegistrationRepository.deleteByProjectId(projectId);
        }
    }

    @Override
    @Transactional
    public void clear() {
        projectRegistrationRepository.deleteAllInBatch();
    }
}
