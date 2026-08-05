package com.devflow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables Spring Data JPA Auditing for all entities in the application context.
 *
 * <p>Automates population of audit metadata columns annotated with:
 * <ul>
 *   <li>{@link org.springframework.data.annotation.CreatedDate} ({@code created_at})</li>
 *   <li>{@link org.springframework.data.annotation.LastModifiedDate} ({@code updated_at})</li>
 * </ul>
 *
 * <p>Decoupled from main application class to allow test slice isolation where needed.
 *
 * @see <a href="../../../../docs/database/DATABASE_DESIGN.md">Database Architecture Specification §8</a>
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfiguration {
}
