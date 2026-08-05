package com.devflow.security.refresh;

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
 * Spring Data JPA Repository for the {@link RefreshToken} entity.
 *
 * <p>Provides database operations for lookup by token hash, user ID, device ID,
 * and bulk cleanup operations for expired and revoked tokens.
 *
 * @see RefreshToken
 * @see RefreshTokenService
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * Finds all refresh tokens associated with a specific user ID.
     *
     * @param userId the user's UUID
     * @return list of refresh tokens
     */
    List<RefreshToken> findByUserId(UUID userId);

    /**
     * Finds all active (non-revoked) refresh tokens for a user ID.
     *
     * @param userId the user's UUID
     * @return list of active refresh tokens
     */
    List<RefreshToken> findByUserIdAndRevokedFalse(UUID userId);

    /**
     * Finds an unrevoked active session for a specific user and device ID.
     *
     * @param userId the user's UUID
     * @param deviceId the device identifier string
     * @return an {@link Optional} containing the active token if found
     */
    Optional<RefreshToken> findByUserIdAndDeviceIdAndRevokedFalse(UUID userId, String deviceId);

    /**
     * Finds a refresh token by its SHA-256 token hash.
     *
     * @param tokenHash the SHA-256 hashed token string
     * @return an {@link Optional} containing the token entity if found
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Checks if a refresh token entity exists with the specified token hash.
     *
     * @param tokenHash the SHA-256 hashed token string
     * @return {@code true} if exists; {@code false} otherwise
     */
    boolean existsByTokenHash(String tokenHash);

    /**
     * Bulk revokes all tokens for a specified user ID.
     *
     * @param userId the target user's UUID
     * @param revokedAt the timestamp of revocation
     * @return count of updated records
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true, r.revokedAt = :revokedAt WHERE r.user.id = :userId AND r.revoked = false")
    int revokeAllByUserId(@Param("userId") UUID userId, @Param("revokedAt") Instant revokedAt);

    /**
     * Deletes all refresh tokens whose expiration timestamp is before the specified cutoff.
     *
     * @param now the current timestamp
     * @return count of deleted records
     */
    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < :now")
    int deleteExpiredTokens(@Param("now") Instant now);

    /**
     * Deletes all refresh tokens that have been revoked.
     *
     * @return count of deleted records
     */
    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.revoked = true")
    int deleteRevokedTokens();
}
