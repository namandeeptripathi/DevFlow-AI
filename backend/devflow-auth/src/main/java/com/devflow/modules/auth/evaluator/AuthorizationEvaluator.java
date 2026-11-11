package com.devflow.modules.auth.evaluator;

import com.devflow.modules.auth.api.AuthApi;
import com.devflow.modules.auth.api.AuthorizationDecision;
import com.devflow.modules.auth.api.AuthorizationRequest;
import com.devflow.modules.auth.api.CompositeAuthorizationRequest;
import com.devflow.modules.auth.api.DenialReason;
import com.devflow.modules.auth.audit.AuthorizationAuditEvent;
import com.devflow.modules.auth.audit.AuthorizationAuditEventType;
import com.devflow.modules.auth.audit.AuthorizationAuditRecorder;
import com.devflow.modules.auth.context.AuthorizationContext;
import com.devflow.modules.auth.domain.Permission;
import com.devflow.modules.auth.domain.Role;
import com.devflow.modules.auth.domain.RoleScope;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.modules.auth.registry.RolePermissionMapping;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Central authorization policy evaluation engine for DevFlow.
 *
 * <p>Implements {@link AuthApi} as a framework-independent, deterministic evaluation engine
 * operating strictly across hierarchical scopes (Platform, Organization, Project) aligned with
 * {@code docs/security/AUTHORIZATION_MODEL.md} and {@code docs/security/ADR-001-AUTHORIZATION-POLICY-RESOLUTION.md}.
 *
 * <h2>Evaluation Order (§10, ADR-001)</h2>
 * <ol>
 *   <li><strong>Permission Registration Check</strong>: Verifies that the requested permission is defined
 *       in {@link PermissionRegistry}. Unknown permissions fail closed immediately with {@link DenialReason#UNKNOWN_PERMISSION}.</li>
 *   <li><strong>Tenant Boundary & Membership Check</strong>: For organization and project scopes, validates
 *       organization membership and verifies that the project belongs to the tenant.</li>
 *   <li><strong>Platform Security Gates</strong>: For platform requests, enforces independent support-case verification
 *       and emergency-access authorization windows.</li>
 *   <li><strong>Role Resolution, Scope Enforcement & Inheritance</strong>:
 *       <ul>
 *         <li>In organization scope, evaluates organization roles.</li>
 *         <li>In project scope, evaluates parent-to-child inheritance (Owner, Admin, Viewer) and enforces
 *             project assignment for organization members.</li>
 *         <li>Prevents cross-scope privilege escalation.</li>
 *       </ul>
 *   </li>
 *   <li><strong>Ownership Alternatives & Owner Gates</strong>: Evaluates owner-gated operations and ownership
 *       alternatives for users holding base resource access.</li>
 *   <li><strong>Resource State Check</strong>: Validates that the target resource lifecycle state allows
 *       the requested operation.</li>
 *   <li><strong>Audit Recording</strong>: Records structured audit events for both ALLOW and DENY outcomes.</li>
 * </ol>
 *
 * @see AuthApi
 * @see PermissionRegistry
 * @see RolePermissionMapping
 * @see AuthorizationRequest
 * @see CompositeAuthorizationRequest
 * @see <a href="docs/security/AUTHORIZATION_MODEL.md">Authorization Model §2, §4, §10</a>
 * @see <a href="docs/security/ADR-001-AUTHORIZATION-POLICY-RESOLUTION.md">ADR-001</a>
 */
public class AuthorizationEvaluator implements AuthApi {

    private final PermissionRegistry permissionRegistry;
    private final RolePermissionMapping rolePermissionMapping;
    private final RoleResolver roleResolver;
    private final CustomRolePermissionResolver customRolePermissionResolver;
    private final TenantBoundaryValidator tenantBoundaryValidator;
    private final MembershipValidator membershipValidator;
    private final ResourceOwnershipValidator resourceOwnershipValidator;
    private final ResourceStateValidator resourceStateValidator;
    private final PlatformSupportValidator platformSupportValidator;
    private final EmergencyAccessValidator emergencyAccessValidator;
    private final ProjectAssignmentValidator projectAssignmentValidator;
    private final AuthorizationAuditRecorder auditRecorder;

    private AuthorizationEvaluator(Builder builder) {
        this.permissionRegistry = builder.permissionRegistry != null
                ? builder.permissionRegistry
                : PermissionRegistry.canonical();
        this.rolePermissionMapping = builder.rolePermissionMapping != null
                ? builder.rolePermissionMapping
                : RolePermissionMapping.canonical();
        this.roleResolver = builder.roleResolver != null
                ? builder.roleResolver
                : (userId, context) -> Collections.emptySet();
        this.customRolePermissionResolver = builder.customRolePermissionResolver != null
                ? builder.customRolePermissionResolver
                : (userId, context) -> Collections.emptySet();
        this.tenantBoundaryValidator = builder.tenantBoundaryValidator != null
                ? builder.tenantBoundaryValidator
                : (orgId, projId) -> false;
        this.membershipValidator = builder.membershipValidator != null
                ? builder.membershipValidator
                : (userId, orgId) -> false;
        this.resourceOwnershipValidator = builder.resourceOwnershipValidator != null
                ? builder.resourceOwnershipValidator
                : (userId, context, type, id) -> false;
        this.resourceStateValidator = builder.resourceStateValidator != null
                ? builder.resourceStateValidator
                : (context, type, id, perm) -> true;
        this.platformSupportValidator = builder.platformSupportValidator != null
                ? builder.platformSupportValidator
                : (userId, targetOrgId, caseId) -> false;
        this.emergencyAccessValidator = builder.emergencyAccessValidator != null
                ? builder.emergencyAccessValidator
                : (userId, evalTime) -> false;
        this.projectAssignmentValidator = builder.projectAssignmentValidator != null
                ? builder.projectAssignmentValidator
                : (userId, orgId, projId) -> false;
        this.auditRecorder = builder.auditRecorder != null
                ? builder.auditRecorder
                : AuthorizationAuditRecorder.noOp();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public AuthorizationDecision authorize(AuthorizationRequest request) {
        if (request == null) {
            return AuthorizationDecision.deny(DenialReason.GENERAL_DENIED, "Authorization request must not be null");
        }

        UUID userId = request.userId();
        AuthorizationContext context = request.context();
        Permission permission = request.permission();
        String resourceId = request.resourceId();
        RoleScope scope = context.getScope();
        UUID organizationId = context.getOrganizationId().orElse(null);
        UUID projectId = context.getProjectId().orElse(null);
        String resourceType = permission.resource();

        // ── 1. Canonical Permission Check ────────────────────────────────────
        if (!permissionRegistry.isRegistered(permission)) {
            AuthorizationDecision decision = AuthorizationDecision.deny(
                    DenialReason.UNKNOWN_PERMISSION,
                    "Permission '" + permission.value() + "' is not registered in the canonical permission registry"
            );
            recordAudit(AuthorizationAuditEventType.UNKNOWN_PERMISSION_DENIAL,
                    userId, scope, organizationId, projectId, resourceType, resourceId, Set.of(permission), decision, DenialReason.UNKNOWN_PERMISSION);
            return decision;
        }

        // ── 2. Tenant Boundary & Membership Check ────────────────────────────
        if (scope == RoleScope.ORGANIZATION || scope == RoleScope.PROJECT) {
            if (organizationId == null) {
                AuthorizationDecision decision = AuthorizationDecision.deny(
                        DenialReason.TENANT_MISMATCH,
                        "Tenant organization identifier is required"
                );
                recordAudit(AuthorizationAuditEventType.TENANT_BOUNDARY_VIOLATION,
                        userId, scope, null, projectId, resourceType, resourceId, Set.of(permission), decision, DenialReason.TENANT_MISMATCH);
                return decision;
            }

            // Verify active membership BEFORE probing project or resource ownership
            // so outsiders cannot enumerate whether arbitrary projects/resources belong to a tenant.
            if (!membershipValidator.isActiveMember(userId, organizationId)) {
                AuthorizationDecision decision = AuthorizationDecision.deny(
                        DenialReason.MEMBERSHIP_INACTIVE,
                        DenialReason.MEMBERSHIP_INACTIVE.getDefaultMessage()
                );
                recordAudit(AuthorizationAuditEventType.MEMBERSHIP_DENIAL,
                        userId, scope, organizationId, projectId, resourceType, resourceId, Set.of(permission), decision, DenialReason.MEMBERSHIP_INACTIVE);
                return decision;
            }

            if (scope == RoleScope.PROJECT) {
                if (projectId == null) {
                    AuthorizationDecision decision = AuthorizationDecision.deny(
                            DenialReason.GENERAL_DENIED,
                            "Target project identifier is required for project scope"
                    );
                    recordAudit(AuthorizationAuditEventType.AUTHORIZATION_DENIED,
                            userId, scope, organizationId, null, resourceType, resourceId, Set.of(permission), decision, DenialReason.GENERAL_DENIED);
                    return decision;
                }

                // Verify project belongs to organization boundary
                if (!tenantBoundaryValidator.isProjectInOrganization(organizationId, projectId)) {
                    AuthorizationDecision decision = AuthorizationDecision.deny(
                            DenialReason.TENANT_MISMATCH,
                            "Project " + projectId + " does not belong to organization " + organizationId
                    );
                    recordAudit(AuthorizationAuditEventType.TENANT_BOUNDARY_VIOLATION,
                            userId, scope, organizationId, projectId, resourceType, resourceId, Set.of(permission), decision, DenialReason.TENANT_MISMATCH);
                    return decision;
                }
            }

            // Verify sub-resource organization boundary if known
            if (resourceId != null) {
                Optional<UUID> resourceOrgId = tenantBoundaryValidator.getResourceOrganizationId(resourceType, resourceId);
                if (resourceOrgId.isPresent() && !resourceOrgId.get().equals(organizationId)) {
                    AuthorizationDecision decision = AuthorizationDecision.deny(
                            DenialReason.TENANT_MISMATCH,
                            "Resource belongs to organization " + resourceOrgId.get() + ", not active organization " + organizationId
                    );
                    recordAudit(AuthorizationAuditEventType.TENANT_BOUNDARY_VIOLATION,
                            userId, scope, organizationId, projectId, resourceType, resourceId, Set.of(permission), decision, DenialReason.TENANT_MISMATCH);
                    return decision;
                }
            }
        }

        // ── 3. Platform Security Gates ───────────────────────────────────────
        if (scope == RoleScope.PLATFORM) {
            // Platform Support Case Gate
            if (PermissionRegistry.PLATFORM_SUPPORT_CASE_READ.equals(permission)
                    || PermissionRegistry.PLATFORM_SUPPORT_TENANT_READ.equals(permission)) {
                UUID targetOrgId = resolveTargetOrganization(request);
                String supportCaseId = resolveSupportCaseId(request);

                if (targetOrgId == null || supportCaseId == null
                        || !platformSupportValidator.isSupportAccessAuthorized(userId, targetOrgId, supportCaseId)) {
                    AuthorizationDecision decision = AuthorizationDecision.deny(
                            DenialReason.SUPPORT_CASE_UNAUTHORIZED,
                            DenialReason.SUPPORT_CASE_UNAUTHORIZED.getDefaultMessage()
                    );
                    recordAudit(AuthorizationAuditEventType.SUPPORT_ACCESS_DENIED,
                            userId, scope, targetOrgId, projectId, resourceType, resourceId, Set.of(permission), decision, DenialReason.SUPPORT_CASE_UNAUTHORIZED);
                    return decision;
                }
            }

            // Emergency Access Validation Gate
            if (PermissionRegistry.PLATFORM_EMERGENCY_ACCESS_EXECUTE.equals(permission)) {
                if (!emergencyAccessValidator.isEmergencyAccessAuthorized(userId, Instant.now())) {
                    AuthorizationDecision decision = AuthorizationDecision.deny(
                            DenialReason.EMERGENCY_ACCESS_NOT_APPROVED,
                            DenialReason.EMERGENCY_ACCESS_NOT_APPROVED.getDefaultMessage()
                    );
                    recordAudit(AuthorizationAuditEventType.EMERGENCY_ACCESS_DENIED,
                            userId, scope, organizationId, projectId, resourceType, resourceId, Set.of(permission), decision, DenialReason.EMERGENCY_ACCESS_NOT_APPROVED);
                    return decision;
                }
            }
        }

        // ── 4. Role Resolution, Scope Enforcement & Inheritance ──────────────
        Set<Role> resolvedRoles = roleResolver.resolveRoles(userId, context);
        Set<Permission> customPermissions;
        try {
            customPermissions = (scope == RoleScope.ORGANIZATION || scope == RoleScope.PROJECT)
                    ? customRolePermissionResolver.resolveCustomPermissions(userId, context)
                    : Collections.emptySet();
        } catch (Exception e) {
            // Fail-closed on custom permission resolution errors
            customPermissions = Collections.emptySet();
        }
        if (customPermissions == null) {
            customPermissions = Collections.emptySet();
        }

        if ((resolvedRoles == null || resolvedRoles.isEmpty()) && customPermissions.isEmpty()) {
            AuthorizationDecision decision = AuthorizationDecision.deny(
                    DenialReason.INSUFFICIENT_PERMISSIONS,
                    "Principal has no assigned roles for the requested context"
            );
            recordAudit(AuthorizationAuditEventType.AUTHORIZATION_DENIED,
                    userId, scope, organizationId, projectId, resourceType, resourceId, Set.of(permission), decision, DenialReason.INSUFFICIENT_PERMISSIONS);
            return decision;
        }

        Set<Permission> grantedPermissions = new HashSet<>();
        boolean roleScopeMatchFound = false;

        if (scope == RoleScope.PLATFORM) {
            // Only platform roles grant platform permissions
            Set<Role> platformRoles = resolvedRoles != null ? resolvedRoles.stream()
                    .filter(Role::isPlatformRole)
                    .collect(Collectors.toUnmodifiableSet()) : Collections.emptySet();

            if (!platformRoles.isEmpty()) {
                roleScopeMatchFound = true;
                grantedPermissions.addAll(rolePermissionMapping.getPermissionsForRoles(platformRoles, RoleScope.PLATFORM));
            }
        } else if (scope == RoleScope.ORGANIZATION) {
            // Only organization roles grant organization permissions (project roles cannot grant org permissions)
            Set<Role> orgRoles = resolvedRoles != null ? resolvedRoles.stream()
                    .filter(Role::isOrganizationRole)
                    .collect(Collectors.toUnmodifiableSet()) : Collections.emptySet();

            if (!orgRoles.isEmpty()) {
                roleScopeMatchFound = true;
                grantedPermissions.addAll(rolePermissionMapping.getPermissionsForRoles(orgRoles, RoleScope.ORGANIZATION));
            }

            if (!customPermissions.isEmpty()) {
                roleScopeMatchFound = true;
                grantedPermissions.addAll(customPermissions);
            }
        } else if (scope == RoleScope.PROJECT) {
            // Project scope evaluation with hierarchical parent-to-child inheritance (§4.2, ADR-001)
            Set<Role> projectRoles = resolvedRoles != null ? resolvedRoles.stream()
                    .filter(Role::isProjectRole)
                    .collect(Collectors.toSet()) : new HashSet<>();

            Set<Role> orgRoles = resolvedRoles != null ? resolvedRoles.stream()
                    .filter(Role::isOrganizationRole)
                    .collect(Collectors.toSet()) : new HashSet<>();

            // Check organization-level inheritance into projects
            boolean hasOrgAdminOrOwner = orgRoles.contains(Role.ORGANIZATION_OWNER) || orgRoles.contains(Role.ORGANIZATION_ADMIN);
            boolean hasOrgViewer = orgRoles.contains(Role.ORGANIZATION_VIEWER);
            boolean hasOrgMember = orgRoles.contains(Role.ORGANIZATION_MEMBER);

            if (hasOrgAdminOrOwner) {
                roleScopeMatchFound = true;
                for (Role orgRole : orgRoles) {
                    grantedPermissions.addAll(rolePermissionMapping.getInheritedProjectPermissions(orgRole));
                }
            } else if (hasOrgViewer) {
                roleScopeMatchFound = true;
                grantedPermissions.addAll(rolePermissionMapping.getInheritedProjectPermissions(Role.ORGANIZATION_VIEWER));
            }

            // For organization members, project-scoped access requires a verified project role or assignment
            if (hasOrgMember && !hasOrgAdminOrOwner && !hasOrgViewer) {
                boolean isAssigned = !projectRoles.isEmpty()
                        || projectAssignmentValidator.isAssignedToProject(userId, organizationId, projectId);

                if (!isAssigned) {
                    AuthorizationDecision decision = AuthorizationDecision.deny(
                            DenialReason.PROJECT_ASSIGNMENT_REQUIRED,
                            DenialReason.PROJECT_ASSIGNMENT_REQUIRED.getDefaultMessage()
                    );
                    recordAudit(AuthorizationAuditEventType.PROJECT_ASSIGNMENT_DENIAL,
                            userId, scope, organizationId, projectId, resourceType, resourceId, Set.of(permission), decision, DenialReason.PROJECT_ASSIGNMENT_REQUIRED);
                    return decision;
                }

                // If assigned but no explicit project role returned, default to PROJECT_MEMBER (§5.4)
                if (projectRoles.isEmpty()) {
                    projectRoles.add(Role.PROJECT_MEMBER);
                }
            }

            // Add permissions from project roles
            if (!projectRoles.isEmpty()) {
                roleScopeMatchFound = true;
                grantedPermissions.addAll(rolePermissionMapping.getPermissionsForRoles(projectRoles, RoleScope.PROJECT));
            }

            // Add permissions from custom roles (organization-scoped custom roles grant their permissions in the tenant)
            if (!customPermissions.isEmpty()) {
                roleScopeMatchFound = true;
                grantedPermissions.addAll(customPermissions);
            }
        }

        if (!roleScopeMatchFound) {
            AuthorizationDecision decision = AuthorizationDecision.deny(
                    DenialReason.SCOPE_MISMATCH,
                    "Assigned roles do not match the required authorization scope: " + scope
            );
            recordAudit(AuthorizationAuditEventType.SCOPE_MISMATCH_DENIAL,
                    userId, scope, organizationId, projectId, resourceType, resourceId, Set.of(permission), decision, DenialReason.SCOPE_MISMATCH);
            return decision;
        }

        // ── 5. Ownership Alternatives & Owner-Gated Verification ─────────────
        boolean hasDirectGrant = grantedPermissions.contains(permission);
        boolean ownershipVerified = false;

        if (hasDirectGrant) {
            // If the permission is owner-gated for the caller's specific roles, verify ownership
            if (rolePermissionMapping.isOwnerGated(resolvedRoles, permission)) {
                boolean isOwner = resourceOwnershipValidator.isOwner(userId, context, resourceType, resourceId);
                if (!isOwner) {
                    AuthorizationDecision decision = AuthorizationDecision.deny(
                            DenialReason.OWNERSHIP_REQUIRED,
                            DenialReason.OWNERSHIP_REQUIRED.getDefaultMessage()
                    );
                    recordAudit(AuthorizationAuditEventType.OWNERSHIP_DENIAL,
                            userId, scope, organizationId, projectId, resourceType, resourceId, Set.of(permission), decision, DenialReason.OWNERSHIP_REQUIRED);
                    return decision;
                }
                ownershipVerified = true;
            }
        } else {
            // Check ownership alternative: if permission is ownership-eligible and caller has base resource access
            if (rolePermissionMapping.isOwnershipEligible(permission)
                    && rolePermissionMapping.hasBaseResourceAccess(resolvedRoles, permission)) {
                boolean isOwner = resourceOwnershipValidator.isOwner(userId, context, resourceType, resourceId);
                if (!isOwner) {
                    AuthorizationDecision decision = AuthorizationDecision.deny(
                            DenialReason.OWNERSHIP_REQUIRED,
                            DenialReason.OWNERSHIP_REQUIRED.getDefaultMessage()
                    );
                    recordAudit(AuthorizationAuditEventType.OWNERSHIP_DENIAL,
                            userId, scope, organizationId, projectId, resourceType, resourceId, Set.of(permission), decision, DenialReason.OWNERSHIP_REQUIRED);
                    return decision;
                }
                ownershipVerified = true;
            } else {
                AuthorizationDecision decision = AuthorizationDecision.deny(
                        DenialReason.INSUFFICIENT_PERMISSIONS,
                        DenialReason.INSUFFICIENT_PERMISSIONS.getDefaultMessage()
                );
                recordAudit(AuthorizationAuditEventType.AUTHORIZATION_DENIED,
                        userId, scope, organizationId, projectId, resourceType, resourceId, Set.of(permission), decision, DenialReason.INSUFFICIENT_PERMISSIONS);
                return decision;
            }
        }

        // ── 6. Resource State Check ──────────────────────────────────────────
        if (!resourceStateValidator.isOperationPermitted(context, resourceType, resourceId, permission)) {
            AuthorizationDecision decision = AuthorizationDecision.deny(
                    DenialReason.RESOURCE_STATE_CONFLICT,
                    DenialReason.RESOURCE_STATE_CONFLICT.getDefaultMessage()
            );
            recordAudit(AuthorizationAuditEventType.RESOURCE_STATE_DENIAL,
                    userId, scope, organizationId, projectId, resourceType, resourceId, Set.of(permission), decision, DenialReason.RESOURCE_STATE_CONFLICT);
            return decision;
        }

        // ── 7. Access Granted ────────────────────────────────────────────────
        AuthorizationDecision allowDecision = AuthorizationDecision.allow();
        AuthorizationAuditEventType auditType = AuthorizationAuditEventType.AUTHORIZATION_ALLOWED;
        if (PermissionRegistry.PLATFORM_EMERGENCY_ACCESS_EXECUTE.equals(permission)) {
            auditType = AuthorizationAuditEventType.EMERGENCY_ACCESS_ALLOWED;
        } else if (PermissionRegistry.PLATFORM_SUPPORT_CASE_READ.equals(permission)
                || PermissionRegistry.PLATFORM_SUPPORT_TENANT_READ.equals(permission)) {
            auditType = AuthorizationAuditEventType.SUPPORT_ACCESS_ALLOWED;
        }

        recordAudit(auditType, userId, scope, organizationId, projectId, resourceType, resourceId, Set.of(permission), allowDecision, null);
        return allowDecision;
    }

    @Override
    public AuthorizationDecision authorizeAll(
            UUID userId,
            AuthorizationContext context,
            Collection<Permission> permissions,
            String resourceId,
            Map<String, Object> attributes
    ) {
        if (permissions == null || permissions.isEmpty()) {
            return AuthorizationDecision.deny(DenialReason.GENERAL_DENIED, "No permissions specified for composite check");
        }

        for (Permission permission : permissions) {
            AuthorizationRequest req = new AuthorizationRequest(userId, context, permission, resourceId, attributes);
            AuthorizationDecision decision = authorize(req);
            if (!decision.allowed()) {
                return decision;
            }
        }

        return AuthorizationDecision.allow();
    }

    private UUID resolveTargetOrganization(AuthorizationRequest request) {
        if (request.context().getOrganizationId().isPresent()) {
            return request.context().getOrganizationId().get();
        }
        Object orgAttr = request.attributes().get("targetOrganizationId");
        if (orgAttr instanceof UUID uuid) {
            return uuid;
        }
        if (orgAttr instanceof String str && !str.isBlank()) {
            try {
                return UUID.fromString(str);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private String resolveSupportCaseId(AuthorizationRequest request) {
        if (request.resourceId() != null && !request.resourceId().isBlank()) {
            return request.resourceId();
        }
        Object caseAttr = request.attributes().get("supportCaseId");
        if (caseAttr instanceof String str && !str.isBlank()) {
            return str;
        }
        return null;
    }

    private void recordAudit(
            AuthorizationAuditEventType eventType,
            UUID userId,
            RoleScope scope,
            UUID organizationId,
            UUID projectId,
            String resourceType,
            String resourceId,
            Set<Permission> permissions,
            AuthorizationDecision decision,
            DenialReason denialReason
    ) {
        recordAudit(eventType, userId, scope, organizationId, projectId, resourceType, resourceId, permissions, decision, denialReason, null);
    }

    private void recordAudit(
            AuthorizationAuditEventType eventType,
            UUID userId,
            RoleScope scope,
            UUID organizationId,
            UUID projectId,
            String resourceType,
            String resourceId,
            Set<Permission> permissions,
            AuthorizationDecision decision,
            DenialReason denialReason,
            String correlationId
    ) {
        try {
            AuthorizationAuditEvent event = new AuthorizationAuditEvent(
                    UUID.randomUUID(),
                    Instant.now(),
                    eventType,
                    userId,
                    scope,
                    organizationId,
                    projectId,
                    resourceType,
                    resourceId,
                    permissions,
                    decision,
                    denialReason,
                    correlationId,
                    null,
                    null,
                    null,
                    null,
                    null
            );
            auditRecorder.record(event);
        } catch (Exception ignored) {
            // Evaluator audit logging failure must not alter authorization decisions or leak credentials
        }
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    public static class Builder {
        private PermissionRegistry permissionRegistry;
        private RolePermissionMapping rolePermissionMapping;
        private RoleResolver roleResolver;
        private CustomRolePermissionResolver customRolePermissionResolver;
        private TenantBoundaryValidator tenantBoundaryValidator;
        private MembershipValidator membershipValidator;
        private ResourceOwnershipValidator resourceOwnershipValidator;
        private ResourceStateValidator resourceStateValidator;
        private PlatformSupportValidator platformSupportValidator;
        private EmergencyAccessValidator emergencyAccessValidator;
        private ProjectAssignmentValidator projectAssignmentValidator;
        private AuthorizationAuditRecorder auditRecorder;

        public Builder permissionRegistry(PermissionRegistry permissionRegistry) {
            this.permissionRegistry = permissionRegistry;
            return this;
        }

        public Builder rolePermissionMapping(RolePermissionMapping rolePermissionMapping) {
            this.rolePermissionMapping = rolePermissionMapping;
            return this;
        }

        public Builder roleResolver(RoleResolver roleResolver) {
            this.roleResolver = roleResolver;
            return this;
        }

        public Builder customRolePermissionResolver(CustomRolePermissionResolver customRolePermissionResolver) {
            this.customRolePermissionResolver = customRolePermissionResolver;
            return this;
        }

        public Builder tenantBoundaryValidator(TenantBoundaryValidator tenantBoundaryValidator) {
            this.tenantBoundaryValidator = tenantBoundaryValidator;
            return this;
        }

        public Builder membershipValidator(MembershipValidator membershipValidator) {
            this.membershipValidator = membershipValidator;
            return this;
        }

        public Builder resourceOwnershipValidator(ResourceOwnershipValidator resourceOwnershipValidator) {
            this.resourceOwnershipValidator = resourceOwnershipValidator;
            return this;
        }

        public Builder resourceStateValidator(ResourceStateValidator resourceStateValidator) {
            this.resourceStateValidator = resourceStateValidator;
            return this;
        }

        public Builder platformSupportValidator(PlatformSupportValidator platformSupportValidator) {
            this.platformSupportValidator = platformSupportValidator;
            return this;
        }

        public Builder emergencyAccessValidator(EmergencyAccessValidator emergencyAccessValidator) {
            this.emergencyAccessValidator = emergencyAccessValidator;
            return this;
        }

        public Builder projectAssignmentValidator(ProjectAssignmentValidator projectAssignmentValidator) {
            this.projectAssignmentValidator = projectAssignmentValidator;
            return this;
        }

        public Builder auditRecorder(AuthorizationAuditRecorder auditRecorder) {
            this.auditRecorder = auditRecorder;
            return this;
        }

        public AuthorizationEvaluator build() {
            return new AuthorizationEvaluator(this);
        }
    }
}
