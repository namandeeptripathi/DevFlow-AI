package com.devflow.role.exception;

/**
 * Thrown when custom role permission configuration violates governance rules (e.g. unknown permissions,
 * platform-scoped permissions, prohibited privileged permissions, or delegation boundary violations) (Stage 7).
 */
public class PermissionGovernanceException extends CustomRoleException {

    public PermissionGovernanceException(String message) {
        super(message);
    }
}
