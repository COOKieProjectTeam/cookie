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

    fun deleteUnusableVerificationChallengesBefore(olderThan: Instant, batchSize: Int): Int = deleteBatch(
        batchSize,
        """
        WITH expired AS (
            SELECT id
            FROM auth_action_tokens
            WHERE purpose = 'EMAIL_VERIFICATION'
              AND (
                  expires_at < ?
                  OR consumed_at < ?
                  OR revoked_at < ?
              )
            ORDER BY expires_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT ?
        )
        DELETE FROM auth_action_tokens t
        USING expired e
        WHERE t.id = e.id
        """.trimIndent(),
        olderThan,
        additionalCutoffParameters = 2,
    )

    /** Returns the number of session rows deleted, not the number of families. */
    fun deleteRefreshFamiliesExpiredBefore(expiredBefore: Instant, familyBatchSize: Int): Int {
        require(familyBatchSize > 0) { "Maintenance batch size must be positive" }
        return jdbc.update(
            """
            WITH expired_families AS (
                SELECT family_id, MAX(family_expires_at) AS expires_at
                FROM refresh_sessions
                GROUP BY family_id
                HAVING MAX(family_expires_at) < ?
                ORDER BY expires_at, family_id
                LIMIT ?
            )
            DELETE FROM refresh_sessions s
            USING expired_families e
            WHERE s.family_id = e.family_id
            """.trimIndent(),
            expiredBefore.asJdbcTimestamp(),
            familyBatchSize,
        )
    }

    private fun deleteBatch(
        batchSize: Int,
        sql: String,
        cutoff: Instant,
        additionalCutoffParameters: Int = 0,
    ): Int {
        require(batchSize > 0) { "Maintenance batch size must be positive" }
        val cutoffParameter = cutoff.asJdbcTimestamp()
        val parameters = buildList<Any> {
            repeat(additionalCutoffParameters + 1) { add(cutoffParameter) }
            add(batchSize)
        }
        return jdbc.update(sql, *parameters.toTypedArray())
    }
}
