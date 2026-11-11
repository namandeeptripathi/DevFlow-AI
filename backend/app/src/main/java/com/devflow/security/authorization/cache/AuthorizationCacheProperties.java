package com.devflow.security.authorization.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the centralized authorization caching layer (Stage 6).
 */
@Configuration
@ConfigurationProperties(prefix = "devflow.security.authorization.cache")
public class AuthorizationCacheProperties {

    /**
     * Whether authorization caching is enabled. Default {@code true}.
     */
    private boolean enabled = true;

    /**
     * Time-to-live for cached authorization data in seconds. Default 300s (5 minutes).
     */
    private long ttlSeconds = 300;

    /**
     * Prefix prepended to all Redis authorization cache keys. Default {@code "devflow:auth:"}.
     */
    private String keyPrefix = "devflow:auth:";

    /**
     * Maximum entries permitted in the process-local fallback cache when Redis is unavailable.
     * Prevents unbounded JVM heap growth under high tenant/user cardinality or prolonged Redis outages.
     */
    private int maxLocalEntries = 10_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public int getMaxLocalEntries() {
        return maxLocalEntries;
    }

    public void setMaxLocalEntries(int maxLocalEntries) {
        this.maxLocalEntries = maxLocalEntries;
    }
}

