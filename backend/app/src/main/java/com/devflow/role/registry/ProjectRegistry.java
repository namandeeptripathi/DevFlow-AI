package com.devflow.role.registry;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain port and SPI establishing authoritative project-to-organization ownership boundaries.
 *
 * <p>In Stage 2/Stage 4, before dedicated project entity lifecycle management (deferred to
 * the Project Management module), this registry acts as the authoritative anchor for project tenant
 * boundaries. It prevents arbitrary UUID adoption and secures initial project role bindings.
 */
public interface ProjectRegistry {

    /**
     * Checks if a project is authoritatively bound/registered to the specified organization.
     *
     * @param organizationId tenant organization UUID
     * @param projectId project UUID
     * @return {@code true} if the project is registered to this organization; {@code false} otherwise
     */
    boolean isProjectRegistered(UUID organizationId, UUID projectId);

    /**
     * Retrieves the owning organization UUID for the specified project, if registered.
     *
     * @param projectId project UUID
     * @return optional containing the organization UUID, or empty if unregistered
     */
    Optional<UUID> getProjectOrganizationId(UUID projectId);

    /**
     * Authoritatively registers and binds a project to an organization.
     *
     * @param organizationId tenant organization UUID
     * @param projectId project UUID
     * @throws com.devflow.role.exception.InvalidProjectRegistrationException if the project is already registered to a different organization
     */
    void registerProject(UUID organizationId, UUID projectId);

    /**
     * Authoritatively registers and binds a project to an organization with the registering actor.
     *
     * @param organizationId tenant organization UUID
     * @param projectId project UUID
     * @param registeredByUserId optional user ID performing the registration
     * @throws com.devflow.role.exception.InvalidProjectRegistrationException if the project is already registered to a different organization
     */
    default void registerProject(UUID organizationId, UUID projectId, UUID registeredByUserId) {
        registerProject(organizationId, projectId);
    }

    /**
     * Unregisters a project binding.
     *
     * @param projectId project UUID
     */
    void unregisterProject(UUID projectId);

    /**
     * Clears all registered project bindings (used primarily for test isolation and cache resets).
     */
    void clear();
}
