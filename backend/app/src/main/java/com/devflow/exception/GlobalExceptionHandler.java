package com.devflow.exception;

import com.devflow.auth.exception.AccountLockedException;
import com.devflow.auth.exception.AuthenticationDomainException;
import com.devflow.auth.exception.EmailNotVerifiedException;
import com.devflow.auth.exception.InvalidCredentialsException;
import com.devflow.auth.exception.UserAlreadyExistsException;
import com.devflow.organization.exception.LastOrganizationOwnerRemovalException;
import com.devflow.organization.exception.OrganizationAccessDeniedException;
import com.devflow.organization.exception.OrganizationAlreadyExistsException;
import com.devflow.organization.exception.OrganizationDomainException;
import com.devflow.organization.exception.OrganizationInvitationAlreadyExistsException;
import com.devflow.organization.exception.OrganizationInvitationAlreadyProcessedException;
import com.devflow.organization.exception.OrganizationInvitationExpiredException;
import com.devflow.organization.exception.OrganizationInvitationNotFoundException;
import com.devflow.organization.exception.OrganizationMemberAlreadyExistsException;
import com.devflow.organization.exception.OrganizationMemberNotFoundException;
import com.devflow.organization.exception.OrganizationMembershipRequiredException;
import com.devflow.organization.exception.OrganizationNotFoundException;
import com.devflow.role.exception.CustomRoleAccessDeniedException;
import com.devflow.role.exception.CustomRoleAlreadyExistsException;
import com.devflow.role.exception.CustomRoleDeletionException;
import com.devflow.role.exception.CustomRoleException;
import com.devflow.role.exception.CustomRoleNotFoundException;
import com.devflow.role.exception.InvalidCustomRoleException;
import com.devflow.role.exception.InvalidRoleAssignmentException;
import com.devflow.role.exception.PermissionGovernanceException;
import com.devflow.role.exception.RoleAssignmentAccessDeniedException;
import com.devflow.role.exception.RoleAssignmentAlreadyExistsException;
import com.devflow.role.exception.RoleAssignmentException;
import com.devflow.role.exception.RoleAssignmentNotFoundException;
import com.devflow.security.monitoring.exception.AuditLogNotFoundException;
import com.devflow.security.monitoring.exception.InvalidSecurityOperationException;
import com.devflow.security.monitoring.exception.SecurityAlertNotFoundException;
import com.devflow.security.monitoring.exception.SecurityEventNotFoundException;
import com.devflow.security.monitoring.exception.SecurityMonitoringAccessDeniedException;
import com.devflow.team.exception.InvalidTeamOperationException;
import com.devflow.team.exception.TeamAccessDeniedException;
import com.devflow.team.exception.TeamAlreadyExistsException;
import com.devflow.team.exception.TeamDomainException;
import com.devflow.team.exception.TeamMemberAlreadyExistsException;
import com.devflow.team.exception.TeamMemberNotFoundException;
import com.devflow.team.exception.TeamNotFoundException;
import com.devflow.user.exception.AvatarStorageException;
import com.devflow.user.exception.InvalidAvatarException;
import com.devflow.user.exception.InvalidPreferencesException;
import com.devflow.user.exception.InvalidSearchQueryException;
import com.devflow.user.exception.UserDomainException;
import com.devflow.user.exception.UserProfileNotFoundException;
import com.devflow.user.exception.UserPreferencesNotFoundException;
import com.devflow.workspace.exception.WorkspaceAccessDeniedException;
import com.devflow.workspace.exception.WorkspaceAlreadyExistsException;
import com.devflow.workspace.exception.WorkspaceDomainException;
import com.devflow.workspace.exception.WorkspaceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global REST Controller Advice providing uniform exception handling across all API endpoints.
 *
 * <p>Translates domain exceptions and request validation failures into standard JSON error responses.
 *
 * @see <a href="../../../../docs/api/ERROR_RESPONSE_FORMAT.md">Error Response Format Standard</a>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCredentials(
            InvalidCredentialsException ex,
            HttpServletRequest request
    ) {
        log.warn("Invalid credentials: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, "Unauthorized", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<Map<String, Object>> handleAccountLocked(
            AccountLockedException ex,
            HttpServletRequest request
    ) {
        log.warn("Account locked access attempt: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<Map<String, Object>> handleEmailNotVerified(
            EmailNotVerifiedException ex,
            HttpServletRequest request
    ) {
        log.warn("Unverified email access attempt: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleUserAlreadyExists(
            UserAlreadyExistsException ex,
            HttpServletRequest request
    ) {
        log.warn("Registration conflict: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));

        log.warn("Validation failure on [{}]: {}", request.getRequestURI(), details);
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", details, request.getRequestURI());
    }

    @ExceptionHandler(AuthenticationDomainException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationDomainException(
            AuthenticationDomainException ex,
            HttpServletRequest request
    ) {
        log.warn("Authentication domain error: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(UserProfileNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUserProfileNotFound(
            UserProfileNotFoundException ex,
            HttpServletRequest request
    ) {
        log.warn("User profile not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(UserPreferencesNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUserPreferencesNotFound(
            UserPreferencesNotFoundException ex,
            HttpServletRequest request
    ) {
        log.warn("User preferences not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidPreferencesException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidPreferences(
            InvalidPreferencesException ex,
            HttpServletRequest request
    ) {
        log.warn("Invalid preferences update: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidSearchQueryException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidSearchQuery(
            InvalidSearchQueryException ex,
            HttpServletRequest request
    ) {
        log.warn("Invalid search query: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(UserDomainException.class)
    public ResponseEntity<Map<String, Object>> handleUserDomainException(
            UserDomainException ex,
            HttpServletRequest request
    ) {
        log.warn("User domain error: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidAvatarException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidAvatar(
            InvalidAvatarException ex,
            HttpServletRequest request
    ) {
        log.warn("Invalid avatar upload: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(AvatarStorageException.class)
    public ResponseEntity<Map<String, Object>> handleAvatarStorageException(
            AvatarStorageException ex,
            HttpServletRequest request
    ) {
        log.error("Avatar storage failure: {}", ex.getMessage(), ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "Avatar storage operation failed. Please try again later.", request.getRequestURI());
    }

    // ── Organization domain ───────────────────────────────────────────────────

    @ExceptionHandler(OrganizationNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleOrganizationNotFound(
            OrganizationNotFoundException ex,
            HttpServletRequest request
    ) {
        log.warn("Organization not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(OrganizationAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleOrganizationAlreadyExists(
            OrganizationAlreadyExistsException ex,
            HttpServletRequest request
    ) {
        log.warn("Organization conflict: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(OrganizationDomainException.class)
    public ResponseEntity<Map<String, Object>> handleOrganizationDomainException(
            OrganizationDomainException ex,
            HttpServletRequest request
    ) {
        log.warn("Organization domain error: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(OrganizationMemberNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleOrganizationMemberNotFound(
            OrganizationMemberNotFoundException ex,
            HttpServletRequest request
    ) {
        log.warn("Organization member not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(OrganizationMemberAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleOrganizationMemberAlreadyExists(
            OrganizationMemberAlreadyExistsException ex,
            HttpServletRequest request
    ) {
        log.warn("Organization member conflict: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(LastOrganizationOwnerRemovalException.class)
    public ResponseEntity<Map<String, Object>> handleLastOrganizationOwnerRemoval(
            LastOrganizationOwnerRemovalException ex,
            HttpServletRequest request
    ) {
        log.warn("Last organization owner removal rejected: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(OrganizationAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleOrganizationAccessDenied(
            OrganizationAccessDeniedException ex,
            HttpServletRequest request
    ) {
        log.warn("Organization access denied: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleSpringSecurityAccessDenied(
            org.springframework.security.access.AccessDeniedException ex,
            HttpServletRequest request
    ) {
        log.warn("Spring Security access denied on [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Forbidden", "Access is denied: " + ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(OrganizationMembershipRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleOrganizationMembershipRequired(
            OrganizationMembershipRequiredException ex,
            HttpServletRequest request
    ) {
        log.warn("Organization membership required: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(RoleAssignmentAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleRoleAssignmentAccessDenied(
            RoleAssignmentAccessDeniedException ex,
            HttpServletRequest request
    ) {
        log.warn("Role assignment access denied on [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(RoleAssignmentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleRoleAssignmentNotFound(
            RoleAssignmentNotFoundException ex,
            HttpServletRequest request
    ) {
        log.warn("Role assignment not found on [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(RoleAssignmentAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleRoleAssignmentAlreadyExists(
            RoleAssignmentAlreadyExistsException ex,
            HttpServletRequest request
    ) {
        log.warn("Role assignment conflict on [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidRoleAssignmentException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRoleAssignment(
            InvalidRoleAssignmentException ex,
            HttpServletRequest request
    ) {
        log.warn("Invalid role assignment on [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(RoleAssignmentException.class)
    public ResponseEntity<Map<String, Object>> handleRoleAssignmentException(
            RoleAssignmentException ex,
            HttpServletRequest request
    ) {
        log.warn("Role assignment error on [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    // ── Custom Role domain (Stage 7) ──────────────────────────────────────────

    @ExceptionHandler(CustomRoleNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleCustomRoleNotFound(
            CustomRoleNotFoundException ex,
            HttpServletRequest request
    ) {
        log.warn("Custom role not found on [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(CustomRoleAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleCustomRoleAlreadyExists(
            CustomRoleAlreadyExistsException ex,
            HttpServletRequest request
    ) {
        log.warn("Custom role conflict on [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(CustomRoleAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleCustomRoleAccessDenied(
            CustomRoleAccessDeniedException ex,
            HttpServletRequest request
    ) {
        log.warn("Custom role access denied on [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(CustomRoleDeletionException.class)
    public ResponseEntity<Map<String, Object>> handleCustomRoleDeletion(
            CustomRoleDeletionException ex,
            HttpServletRequest request
    ) {
        log.warn("Custom role deletion error on [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidCustomRoleException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCustomRole(
            InvalidCustomRoleException ex,
            HttpServletRequest request
    ) {
        log.warn("Invalid custom role error on [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(PermissionGovernanceException.class)
    public ResponseEntity<Map<String, Object>> handlePermissionGovernance(
            PermissionGovernanceException ex,
            HttpServletRequest request
    ) {
        log.warn("Permission governance violation on [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(CustomRoleException.class)
    public ResponseEntity<Map<String, Object>> handleCustomRoleException(
            CustomRoleException ex,
            HttpServletRequest request
    ) {
        log.warn("Custom role domain error on [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(OrganizationInvitationNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleOrganizationInvitationNotFound(
            OrganizationInvitationNotFoundException ex,
            HttpServletRequest request
    ) {
        log.warn("Organization invitation not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(OrganizationInvitationAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleOrganizationInvitationAlreadyExists(
            OrganizationInvitationAlreadyExistsException ex,
            HttpServletRequest request
    ) {
        log.warn("Organization invitation conflict: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(OrganizationInvitationExpiredException.class)
    public ResponseEntity<Map<String, Object>> handleOrganizationInvitationExpired(
            OrganizationInvitationExpiredException ex,
            HttpServletRequest request
    ) {
        log.warn("Organization invitation expired: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(OrganizationInvitationAlreadyProcessedException.class)
    public ResponseEntity<Map<String, Object>> handleOrganizationInvitationAlreadyProcessed(
            OrganizationInvitationAlreadyProcessedException ex,
            HttpServletRequest request
    ) {
        log.warn("Organization invitation already processed: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    // ── Workspace domain ──────────────────────────────────────────────────────

    @ExceptionHandler(WorkspaceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleWorkspaceNotFound(
            WorkspaceNotFoundException ex,
            HttpServletRequest request
    ) {
        log.warn("Workspace not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(WorkspaceAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleWorkspaceAlreadyExists(
            WorkspaceAlreadyExistsException ex,
            HttpServletRequest request
    ) {
        log.warn("Workspace conflict: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(WorkspaceAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleWorkspaceAccessDenied(
            WorkspaceAccessDeniedException ex,
            HttpServletRequest request
    ) {
        log.warn("Workspace access denied: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(WorkspaceDomainException.class)
    public ResponseEntity<Map<String, Object>> handleWorkspaceDomainException(
            WorkspaceDomainException ex,
            HttpServletRequest request
    ) {
        log.warn("Workspace domain error: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    // ── Team domain ───────────────────────────────────────────────────────────

    @ExceptionHandler(TeamNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTeamNotFound(
            TeamNotFoundException ex,
            HttpServletRequest request
    ) {
        log.warn("Team not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(TeamMemberNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTeamMemberNotFound(
            TeamMemberNotFoundException ex,
            HttpServletRequest request
    ) {
        log.warn("Team member not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(TeamAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleTeamAlreadyExists(
            TeamAlreadyExistsException ex,
            HttpServletRequest request
    ) {
        log.warn("Team conflict: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(TeamMemberAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleTeamMemberAlreadyExists(
            TeamMemberAlreadyExistsException ex,
            HttpServletRequest request
    ) {
        log.warn("Team member conflict: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidTeamOperationException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidTeamOperation(
            InvalidTeamOperationException ex,
            HttpServletRequest request
    ) {
        log.warn("Invalid team operation: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(TeamAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleTeamAccessDenied(
            TeamAccessDeniedException ex,
            HttpServletRequest request
    ) {
        log.warn("Team access denied: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(TeamDomainException.class)
    public ResponseEntity<Map<String, Object>> handleTeamDomainException(
            TeamDomainException ex,
            HttpServletRequest request
    ) {
        log.warn("Team domain error: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler({
            AuditLogNotFoundException.class,
            SecurityAlertNotFoundException.class,
            SecurityEventNotFoundException.class
    })
    public ResponseEntity<Map<String, Object>> handleSecurityResourceNotFound(
            RuntimeException ex,
            HttpServletRequest request
    ) {
        log.warn("Security resource not found on [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(SecurityMonitoringAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleSecurityMonitoringAccessDenied(
            SecurityMonitoringAccessDeniedException ex,
            HttpServletRequest request
    ) {
        log.warn("Security monitoring access denied on [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidSecurityOperationException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidSecurityOperation(
            InvalidSecurityOperationException ex,
            HttpServletRequest request
    ) {
        log.warn("Invalid security operation on [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {
        log.warn("Illegal argument on [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status,
            String error,
            String message,
            String path
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        body.put("path", path);

        return ResponseEntity.status(status).body(body);
    }
}
