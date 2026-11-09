package com.devflow.config;

import com.devflow.audit.LoggingAuthorizationAuditRecorder;
import com.devflow.modules.auth.api.AuthApi;
import com.devflow.modules.auth.evaluator.AuthorizationEvaluator;
import com.devflow.modules.auth.registry.PermissionRegistry;
import com.devflow.modules.auth.registry.RolePermissionMapping;
import com.devflow.role.adapter.DatabaseCustomRolePermissionResolver;
import com.devflow.role.adapter.DatabaseMembershipValidator;
import com.devflow.role.adapter.DatabaseProjectAssignmentValidator;
import com.devflow.role.adapter.DatabaseResourceOwnershipValidator;
import com.devflow.role.adapter.DatabaseResourceStateValidator;
import com.devflow.role.adapter.DatabaseRoleResolver;
import com.devflow.role.adapter.DatabaseTenantBoundaryValidator;
import com.devflow.role.adapter.DefaultEmergencyAccessValidator;
import com.devflow.role.adapter.DefaultPlatformSupportValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Objects;

/**
 * Spring configuration wiring the centralized authorization architecture (Stage 5).
 *
 * <p>Constructs and exposes the canonical {@link AuthApi} engine configured with:
 * <ul>
 *   <li>Database-backed role resolution ({@link DatabaseRoleResolver}).</li>
 *   <li>Database-backed membership validation ({@link DatabaseMembershipValidator}).</li>
 *   <li>Project assignment validation ({@link DatabaseProjectAssignmentValidator}).</li>
 *   <li>Tenant boundary validation ({@link DatabaseTenantBoundaryValidator}).</li>
 *   <li>Resource ownership validation ({@link DatabaseResourceOwnershipValidator}).</li>
 *   <li>Resource lifecycle state validation ({@link DatabaseResourceStateValidator}).</li>
 *   <li>Platform support and emergency access gates ({@link DefaultPlatformSupportValidator}, {@link DefaultEmergencyAccessValidator}).</li>
 *   <li>Structured audit recording ({@link LoggingAuthorizationAuditRecorder}).</li>
 * </ul>
 *
 * <p>Preserves strict dependency direction: {@code backend/app} &rarr; {@code backend/devflow-auth}.
 * Missing or unverified security checks fail closed.
 *
 * @see AuthApi
 * @see AuthorizationEvaluator
 */
@Configuration
public class AuthorizationConfiguration {

    @Bean
    public PermissionRegistry permissionRegistry() {
        return PermissionRegistry.canonical();
    }

    @Bean
    public RolePermissionMapping rolePermissionMapping() {
        return RolePermissionMapping.canonical();
    }

    @Bean
    public AuthApi authApi(
            PermissionRegistry permissionRegistry,
            RolePermissionMapping rolePermissionMapping,
            DatabaseRoleResolver roleResolver,
            @Autowired(required = false) DatabaseCustomRolePermissionResolver customRolePermissionResolver,
            DatabaseMembershipValidator membershipValidator,
            DatabaseProjectAssignmentValidator projectAssignmentValidator,
            DatabaseTenantBoundaryValidator tenantBoundaryValidator,
            DatabaseResourceOwnershipValidator resourceOwnershipValidator,
            DatabaseResourceStateValidator resourceStateValidator,
            DefaultPlatformSupportValidator platformSupportValidator,
            DefaultEmergencyAccessValidator emergencyAccessValidator,
            LoggingAuthorizationAuditRecorder auditRecorder
    ) {
        Objects.requireNonNull(permissionRegistry, "permissionRegistry must not be null");
        Objects.requireNonNull(rolePermissionMapping, "rolePermissionMapping must not be null");
        Objects.requireNonNull(roleResolver, "roleResolver must not be null");
        Objects.requireNonNull(membershipValidator, "membershipValidator must not be null");
        Objects.requireNonNull(projectAssignmentValidator, "projectAssignmentValidator must not be null");
        Objects.requireNonNull(tenantBoundaryValidator, "tenantBoundaryValidator must not be null");
        Objects.requireNonNull(resourceOwnershipValidator, "resourceOwnershipValidator must not be null");
        Objects.requireNonNull(resourceStateValidator, "resourceStateValidator must not be null");
        Objects.requireNonNull(platformSupportValidator, "platformSupportValidator must not be null");
        Objects.requireNonNull(emergencyAccessValidator, "emergencyAccessValidator must not be null");
        Objects.requireNonNull(auditRecorder, "auditRecorder must not be null");

        // Wire tenant boundary validator into project assignment validator
        projectAssignmentValidator.setTenantBoundaryValidator(tenantBoundaryValidator);

        AuthorizationEvaluator.Builder builder = AuthorizationEvaluator.builder()
                .permissionRegistry(permissionRegistry)
                .rolePermissionMapping(rolePermissionMapping)
                .roleResolver(roleResolver)
                .membershipValidator(membershipValidator)
                .projectAssignmentValidator(projectAssignmentValidator)
                .tenantBoundaryValidator(tenantBoundaryValidator)
                .resourceOwnershipValidator(resourceOwnershipValidator)
                .resourceStateValidator(resourceStateValidator)
                .platformSupportValidator(platformSupportValidator)
                .emergencyAccessValidator(emergencyAccessValidator)
                .auditRecorder(auditRecorder);

        if (customRolePermissionResolver != null) {
            builder.customRolePermissionResolver(customRolePermissionResolver);
        }

        return builder.build();
    }
}
