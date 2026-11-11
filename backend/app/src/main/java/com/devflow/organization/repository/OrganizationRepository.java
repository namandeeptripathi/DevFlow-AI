package com.devflow.organization.repository;

import com.devflow.organization.domain.Organization;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for the {@link Organization} entity.
 *
 * <p>Provides data access operations for organization lookups and
 * slug-based uniqueness verification within the organization domain.
 *
 * @see Organization
 */
@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    /**
     * Finds an organization by its unique, URL-safe slug.
     *
     * @param slug the slug to query (e.g., {@code "acme-corp"})
     * @return an {@link Optional} containing the organization if found, or empty if not
     */
    Optional<Organization> findBySlug(String slug);

    /**
     * Checks if an organization exists with the specified slug.
     *
     * <p>Useful for validating slug uniqueness during organization creation
     * without incurring the cost of loading the full entity graph.
     *
     * @param slug the slug to check
     * @return {@code true} if an organization exists with the slug; {@code false} otherwise
     */
    boolean existsBySlug(String slug);

    /**
     * Acquires a pessimistic row-level write lock ({@code SELECT ... FOR UPDATE}) on the
     * organization row to serialize concurrent last-owner and membership/role guardrails.
     *
     * @param id organization UUID
     * @return optional organization entity if found
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Organization o WHERE o.id = :id")
    Optional<Organization> findByIdForUpdate(@Param("id") UUID id);
}
