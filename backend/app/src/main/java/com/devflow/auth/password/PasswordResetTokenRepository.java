package com.devflow.auth.password;

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
 * Spring Data JPA Repository for {@link PasswordResetToken} entity management.
 *
 * @see PasswordResetToken
 * @see PasswordResetService
 */
@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    /**
     * Finds a password reset token by its SHA-256 token hash.
     *
     * @param tokenHash SHA-256 hashed token string
     * @return an {@link Optional} containing the token entity if found
     */
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Finds all pending (unused) password reset tokens for a specified user ID.
     *
     * @param userId target user's UUID
     * @return list of pending reset tokens ordered by creation date descending
     */
    @Query("SELECT p FROM PasswordResetToken p WHERE p.user.id = :userId AND p.used = false ORDER BY p.createdAt DESC")
    List<PasswordResetToken> findPendingByUserId(@Param("userId") UUID userId);

    /**
     * Checks if a password reset token exists with the specified token hash.
     *
     * @param tokenHash SHA-256 hashed token string
     * @return {@code true} if exists; {@code false} otherwise
     */
    boolean existsByTokenHash(String tokenHash);

    /**
     * Deletes all reset tokens for a specific user ID.
     *
     * @param userId target user UUID
     * @return count of deleted records
     */
    @Modifying
    @Query("DELETE FROM PasswordResetToken p WHERE p.user.id = :userId")
    int deleteByUserId(@Param("userId") UUID userId);

    /**
     * Deletes all reset tokens whose expiration timestamp is prior to the specified instant.
     *
     * @param now current timestamp
     * @return count of deleted records
     */
    @Modifying
    @Query("DELETE FROM PasswordResetToken p WHERE p.expiresAt < :now")
    int deleteExpiredTokens(@Param("now") Instant now);

    /**
     * Deletes all reset tokens that have already been used.
     *
     * @return count of deleted records
     */
    @Modifying
    @Query("DELETE FROM PasswordResetToken p WHERE p.used = true")
    int deleteUsedTokens();
}
