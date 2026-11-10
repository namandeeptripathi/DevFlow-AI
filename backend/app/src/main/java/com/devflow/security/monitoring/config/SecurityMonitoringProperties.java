package com.devflow.security.monitoring.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for deterministic security monitoring rules and alerts.
 */
@Component
@ConfigurationProperties(prefix = "devflow.security.monitoring")
@Data
public class SecurityMonitoringProperties {

    /**
     * Number of denied authorization attempts by a principal to trigger an alert.
     */
    private int repeatedDenialsThreshold = 5;

    /**
     * Sliding window in minutes for repeated authorization denials.
     */
    private int repeatedDenialsWindowMinutes = 5;

    /**
     * Number of cross-tenant boundary violations by a principal to trigger an alert.
     */
    private int crossTenantThreshold = 3;

    /**
     * Sliding window in minutes for cross-tenant boundary violations.
     */
    private int crossTenantWindowMinutes = 10;

    /**
     * Number of project assignment/scope violations to trigger an alert.
     */
    private int projectScopeThreshold = 5;

    /**
     * Sliding window in minutes for project scope violations.
     */
    private int projectScopeWindowMinutes = 10;

    /**
     * Number of unauthorized privileged role grant attempts to trigger an alert.
     */
    private int privilegedGrantThreshold = 2;

    /**
     * Sliding window in minutes for privileged role grant attempts.
     */
    private int privilegedGrantWindowMinutes = 10;

    /**
     * Number of unauthorized permission delegation attempts to trigger an alert.
     */
    private int permissionDelegationThreshold = 2;

    /**
     * Sliding window in minutes for permission delegation attempts.
     */
    private int permissionDelegationWindowMinutes = 10;

    /**
     * Number of administrative role modifications within a window to trigger a bulk modification alert.
     */
    private int bulkRoleModificationThreshold = 10;

    /**
     * Sliding window in minutes for bulk role modifications.
     */
    private int bulkRoleModificationWindowMinutes = 1;

    /**
     * Alert deduplication cooldown window in minutes.
     */
    private int alertCooldownMinutes = 15;
}
