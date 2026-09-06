package com.cookie.identity.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class JdbcMaintenanceRepository(
    private val jdbc: JdbcTemplate,
) {
    fun deleteExpiredRateLimitBuckets(expiredBefore: Instant, batchSize: Int): Int = deleteBatch(
        batchSize,
        """
        WITH expired AS (
            SELECT scope_key
            FROM rate_limit_buckets
            WHERE expires_at < ?
            ORDER BY expires_at, scope_key
            FOR UPDATE SKIP LOCKED
            LIMIT ?
        )
        DELETE FROM rate_limit_buckets b
        USING expired e
        WHERE b.scope_key = e.scope_key
        """.trimIndent(),
        expiredBefore,
    )

    /**
     * Scrubs expired active attempts without deleting their idempotency keys.
     *
     * The row lock makes expiry race safely with confirmation/resend. Child token
     * verifier hashes are intentionally retained with the bounded tombstone.
     */
    fun abandonExpiredRegistrationAttempts(now: Instant, batchSize: Int): Int {
        require(batchSize > 0) { "Maintenance batch size must be positive" }
        return jdbc.update(
            """
            WITH expired AS (
                SELECT id
                FROM registration_attempts
                WHERE completed_at IS NULL
                  AND abandoned_at IS NULL
                  AND expires_at <= ?
                ORDER BY expires_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE registration_attempts a
            SET locale = NULL,
                pending_password_hash = NULL,
                abandoned_at = ?
            FROM expired e
            WHERE a.id = e.id
              AND a.completed_at IS NULL
              AND a.abandoned_at IS NULL
            """.trimIndent(),
            now.asJdbcTimestamp(),
            batchSize,
            now.asJdbcTimestamp(),
        )
    }

    /** Deletes only bounded terminal evidence; active rows are never removed here. */
    fun deleteRegistrationAttemptTombstones(
        abandonedBefore: Instant,
        completedBefore: Instant,
        batchSize: Int,
    ): Int {
        require(batchSize > 0) { "Maintenance batch size must be positive" }
        return jdbc.update(
            """
            WITH removable AS (
                SELECT id
                FROM registration_attempts
                WHERE (abandoned_at IS NOT NULL AND abandoned_at < ?)
                   OR (completed_at IS NOT NULL AND completed_at < ?)
                ORDER BY COALESCE(completed_at, abandoned_at), id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            DELETE FROM registration_attempts a
            USING removable r
            WHERE a.id = r.id
            """.trimIndent(),
            abandonedBefore.asJdbcTimestamp(),
            completedBefore.asJdbcTimestamp(),
            batchSize,
        )
    }

    /** Returns the number of deleted family roots; credentials are removed by cascade. */
    fun deleteRefreshFamiliesExpiredBefore(expiredBefore: Instant, familyBatchSize: Int): Int {
        require(familyBatchSize > 0) { "Maintenance batch size must be positive" }
        return jdbc.update(
            """
            WITH expired_families AS (
                SELECT id
                FROM refresh_families
                WHERE expires_at < ?
                ORDER BY expires_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            DELETE FROM refresh_families f
            USING expired_families e
            WHERE f.id = e.id
            """.trimIndent(),
            expiredBefore.asJdbcTimestamp(),
            familyBatchSize,
        )
    }

    private fun deleteBatch(
        batchSize: Int,
        sql: String,
        cutoff: Instant,
    ): Int {
        require(batchSize > 0) { "Maintenance batch size must be positive" }
        return jdbc.update(sql, cutoff.asJdbcTimestamp(), batchSize)
    }
}
