package com.devflow.workspace.service;

import com.devflow.organization.domain.Organization;
import com.devflow.organization.exception.OrganizationNotFoundException;
import com.devflow.organization.repository.OrganizationRepository;
import com.devflow.organization.service.OrganizationAuthorizationService;
import com.devflow.workspace.domain.Workspace;
import com.devflow.workspace.domain.WorkspaceVisibility;
import com.devflow.workspace.dto.CreateWorkspaceRequest;
import com.devflow.workspace.dto.UpdateWorkspaceRequest;
import com.devflow.workspace.dto.WorkspaceResponse;
import com.devflow.workspace.exception.WorkspaceAccessDeniedException;
import com.devflow.workspace.exception.WorkspaceAlreadyExistsException;
import com.devflow.workspace.exception.WorkspaceNotFoundException;
import com.devflow.workspace.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain service managing the lifecycle and business rules of DevFlow workspaces.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Create a new workspace within an organization with name-uniqueness enforcement.</li>
 *   <li>Retrieve all workspaces for an organization, filtered by visibility and caller permissions.</li>
 *   <li>Retrieve a single workspace by ID within an organization, enforcing visibility access rules.</li>
 *   <li>Update mutable workspace fields (name, visibility, description) with conflict detection.</li>
 *   <li>Delete a workspace within an organization boundary.</li>
 * </ul>
 *
 * <h2>Architectural & Multi-Tenant Boundaries</h2>
 * <ul>
 *   <li>All operations are strictly tenant-scoped by {@code organizationId}.</li>
 *   <li>Authorization is delegated to {@link OrganizationAuthorizationService}.</li>
 *   <li>Workspace name uniqueness is enforced case-insensitively per organization.</li>
 *   <li>Constructor injection only — no field injection.</li>
 * </ul>
 *
 * @see Workspace
 * @see WorkspaceRepository
 * @see OrganizationAuthorizationService
 * @see CreateWorkspaceRequest
 * @see UpdateWorkspaceRequest
 * @see WorkspaceResponse
 */
@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final WorkspaceRepository workspaceRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationAuthorizationService authorizationService;

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            OrganizationRepository organizationRepository,
            OrganizationAuthorizationService authorizationService
    ) {
        this.workspaceRepository = Objects.requireNonNull(
                workspaceRepository, "workspaceRepository must not be null");
        this.organizationRepository = Objects.requireNonNull(
                organizationRepository, "organizationRepository must not be null");
        this.authorizationService = Objects.requireNonNull(
                authorizationService, "authorizationService must not be null");
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    /**
     * Creates a new workspace within the specified organization.
     *
     * <p>Requires the caller to hold {@code ADMIN} or {@code OWNER} role in the organization.
     *
     * @param organizationId the UUID of the target organization
     * @param request        the creation payload
     * @param callerUserId   the UUID of the authenticated caller
     * @return the created {@link WorkspaceResponse}
     * @throws OrganizationNotFoundException      if the parent organization does not exist
     * @throws WorkspaceAlreadyExistsException   if a workspace with the same name already exists in the organization
     * @throws IllegalArgumentException          if the workspace name is blank
     */
    @Transactional
    public WorkspaceResponse createWorkspace(UUID organizationId, CreateWorkspaceRequest request, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        authorizationService.requireAdminOrOwner(organizationId, callerUserId);

        String rawName = request.getName();
        if (rawName == null || rawName.trim().isEmpty()) {
            throw new IllegalArgumentException("Workspace name must not be blank");
        }
        String normalizedName = rawName.trim();

        if (workspaceRepository.existsByOrganizationIdAndNameIgnoreCase(organizationId, normalizedName)) {
            log.warn("Workspace creation rejected: name [{}] already exists in organization [{}]",
                    normalizedName, organizationId);
            throw new WorkspaceAlreadyExistsException(
                    "Workspace already exists with name: " + normalizedName + " in organization: " + organizationId);
        }

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> {
                    log.warn("Workspace creation rejected: organization [{}] not found", organizationId);
                    return new OrganizationNotFoundException(
                            "Organization not found: " + organizationId);
                });

        WorkspaceVisibility visibility = request.getVisibility() != null
                ? request.getVisibility()
                : WorkspaceVisibility.PUBLIC;

        String description = normalise(request.getDescription());

        Workspace workspace = Workspace.builder()
                .name(normalizedName)
                .organization(organization)
                .visibility(visibility)
                .description(description)
                .build();

        Workspace saved = workspaceRepository.save(workspace);
        log.info("Created workspace [{}] with name [{}] in organization [{}] by user [{}]",
                saved.getId(), saved.getName(), organizationId, callerUserId);

        return WorkspaceResponse.from(saved);
    }

    /**
     * Updates mutable attributes of an existing workspace within an organization.
     *
     * <p>Requires the caller to hold {@code ADMIN} or {@code OWNER} role in the organization.
     *
     * @param organizationId the UUID of the owning organization
     * @param workspaceId    the UUID of the workspace to update
     * @param request        the partial update payload
     * @param callerUserId   the UUID of the authenticated caller
     * @return the updated {@link WorkspaceResponse}
     * @throws WorkspaceNotFoundException        if the workspace is not found within the organization
     * @throws WorkspaceAlreadyExistsException   if renaming conflicts with an existing workspace name in the organization
     * @throws IllegalArgumentException          if the supplied name is empty/blank
     */
    @Transactional
    public WorkspaceResponse updateWorkspace(
            UUID organizationId,
            UUID workspaceId,
            UpdateWorkspaceRequest request,
            UUID callerUserId
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        authorizationService.requireAdminOrOwner(organizationId, callerUserId);

        Workspace workspace = workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId)
                .orElseThrow(() -> {
                    log.warn("Workspace update rejected: workspace [{}] not found in organization [{}]",
                            workspaceId, organizationId);
                    return new WorkspaceNotFoundException(
                            "Workspace not found: " + workspaceId + " in organization: " + organizationId);
                });

        if (request.getName() != null) {
            String trimmedName = request.getName().trim();
            if (trimmedName.isEmpty()) {
                throw new IllegalArgumentException("Workspace name must not be blank");
            }
            if (!trimmedName.equalsIgnoreCase(workspace.getName())) {
                if (workspaceRepository.existsByOrganizationIdAndNameIgnoreCase(organizationId, trimmedName)) {
                    log.warn("Workspace update rejected: name [{}] already in use in organization [{}]",
                            trimmedName, organizationId);
                    throw new WorkspaceAlreadyExistsException(
                            "Workspace already exists with name: " + trimmedName + " in organization: " + organizationId);
                }
            }
            workspace.setName(trimmedName);
        }

        if (request.getVisibility() != null) {
            workspace.setVisibility(request.getVisibility());
        }

        if (request.getDescription() != null) {
            workspace.setDescription(normalise(request.getDescription()));
        }

        Workspace saved = workspaceRepository.save(workspace);
        log.info("Updated workspace [{}] in organization [{}] by user [{}]",
                saved.getId(), organizationId, callerUserId);

        return WorkspaceResponse.from(saved);
    }

    /**
     * Deletes a workspace within an organization boundary.
     *
     * <p>Requires the caller to hold {@code ADMIN} or {@code OWNER} role in the organization.
     *
     * @param organizationId the UUID of the owning organization
     * @param workspaceId    the UUID of the workspace to delete
     * @param callerUserId   the UUID of the authenticated caller
     * @throws WorkspaceNotFoundException if the workspace is not found within the organization
     */
    @Transactional
    public void deleteWorkspace(UUID organizationId, UUID workspaceId, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        authorizationService.requireAdminOrOwner(organizationId, callerUserId);

        Workspace workspace = workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId)
                .orElseThrow(() -> {
                    log.warn("Workspace deletion rejected: workspace [{}] not found in organization [{}]",
                            workspaceId, organizationId);
                    return new WorkspaceNotFoundException(
                            "Workspace not found: " + workspaceId + " in organization: " + organizationId);
                });

        workspaceRepository.delete(workspace);
        log.info("Deleted workspace [{}] from organization [{}] by user [{}]",
                workspaceId, organizationId, callerUserId);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Retrieves all workspaces within an organization accessible to the caller.
     *
     * <p>Admins and owners see all workspaces (both {@code PUBLIC} and {@code PRIVATE}).
     * Regular members (developers, viewers) see only {@code PUBLIC} workspaces.
     *
     * @param organizationId the UUID of the organization
     * @param callerUserId   the UUID of the authenticated caller
     * @return a list of accessible {@link WorkspaceResponse} DTOs
     */
    @Transactional(readOnly = true)
    public List<WorkspaceResponse> getWorkspaces(UUID organizationId, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        authorizationService.requireMember(organizationId, callerUserId);

        boolean isAdminOrOwner = authorizationService.isAdminOrOwner(organizationId, callerUserId);

        List<Workspace> workspaces;
        if (isAdminOrOwner) {
            workspaces = workspaceRepository.findByOrganizationId(organizationId);
        } else {
            workspaces = workspaceRepository.findByOrganizationIdAndVisibility(
                    organizationId, WorkspaceVisibility.PUBLIC);
        }

        log.debug("Found [{}] workspaces for user [{}] in organization [{}] (admin/owner={})",
                workspaces.size(), callerUserId, organizationId, isAdminOrOwner);

        return workspaces.stream()
                .map(WorkspaceResponse::from)
                .toList();
    }

    /**
     * Retrieves a single workspace by ID within an organization boundary.
     *
     * <p>Requires the caller to be an active member of the organization.
     * If the workspace is {@code PRIVATE}, the caller must hold {@code ADMIN} or {@code OWNER} role.
     *
     * @param organizationId the UUID of the owning organization
     * @param workspaceId    the UUID of the workspace
     * @param callerUserId   the UUID of the authenticated caller
     * @return the {@link WorkspaceResponse} DTO
     * @throws WorkspaceNotFoundException     if the workspace is not found within the organization
     * @throws WorkspaceAccessDeniedException if the workspace is private and the caller lacks admin/owner role
     */
    @Transactional(readOnly = true)
    public WorkspaceResponse getWorkspace(UUID organizationId, UUID workspaceId, UUID callerUserId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(callerUserId, "callerUserId must not be null");

        authorizationService.requireMember(organizationId, callerUserId);

        Workspace workspace = workspaceRepository.findByIdAndOrganizationId(workspaceId, organizationId)
                .orElseThrow(() -> {
                    log.warn("Workspace [{}] not found in organization [{}]", workspaceId, organizationId);
                    return new WorkspaceNotFoundException(
                            "Workspace not found: " + workspaceId + " in organization: " + organizationId);
                });

        if (workspace.getVisibility() == WorkspaceVisibility.PRIVATE
                && !authorizationService.isAdminOrOwner(organizationId, callerUserId)) {
            log.warn("Security rejection: user [{}] denied access to private workspace [{}] in organization [{}]",
                    callerUserId, workspaceId, organizationId);
            throw new WorkspaceAccessDeniedException(
                    "Access denied: ADMIN or OWNER role required to access private workspace [" + workspaceId + "]");
        }

        log.debug("Fetched workspace [{}] in organization [{}] for user [{}]",
                workspaceId, organizationId, callerUserId);

        return WorkspaceResponse.from(workspace);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Trims whitespace from a string and returns {@code null} if empty.
     *
     * @param value the raw string
     * @return trimmed string or {@code null} if blank
     */
    private String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
