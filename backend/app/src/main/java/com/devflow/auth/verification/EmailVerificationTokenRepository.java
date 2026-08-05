package com.devflow.auth.verification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for {@link EmailVerificationToken} entity management.
 *
 * @see EmailVerificationToken
 * @see EmailVerificationService
 */
@Repository
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    /**
     * Finds a verification token by its SHA-256 token hash.
     *
     * @param tokenHash SHA-256 hashed token string
     * @return an {@link Optional} containing the token if found
     */
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /**
     * Checks if a verification token entity exists with the specified token hash.
     *
     * @param tokenHash SHA-256 hashed token string
     * @return {@code true} if exists; {@code false} otherwise
     */
    boolean existsByTokenHash(String tokenHash);

    /**
     * Finds the latest pending (unverified) token for a specified user ID.
     *
     * @param userId the target user's UUID
     * @return an {@link Optional} containing the pending token if present
     */
    @Query("SELECT e FROM EmailVerificationToken e WHERE e.user.id = :userId AND e.verified = false ORDER BY e.createdAt DESC")
    List<EmailVerificationToken> findPendingByUserId(@Param("userId") UUID userId);

    /**
     * Deletes all verification tokens for a specific user ID.
     *
     * @param userId target user UUID
     * @return count of deleted records
     */
    @Modifying
    @Query("DELETE FROM EmailVerificationToken e WHERE e.user.id = :userId")
    int deleteByUserId(@Param("userId") UUID userId);

    /**
     * Deletes all tokens whose expiration timestamp is prior to the specified instant.
     *
     * @param now current timestamp
     * @return count of deleted records
     */
    @Modifying
    @Query("DELETE FROM EmailVerificationToken e WHERE e.expiresAt < :now")
    int deleteExpiredTokens(@Param("now") Instant now);
}
