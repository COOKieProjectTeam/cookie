package com.cookie.identity.persistence

import com.cookie.identity.application.RateLimitWindow
import com.cookie.identity.application.ports.RateLimitRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Duration

@Repository
class JdbcRateLimitRepository(
    private val jdbc: JdbcTemplate,
) : RateLimitRepository {
    override fun consume(scopeKey: String, window: Duration): RateLimitWindow {
        require(scopeKey.isNotBlank() && scopeKey.length <= 255) { "Invalid rate-limit scope key" }
        require(!window.isZero && !window.isNegative) { "Rate-limit window must be positive" }
        val windowMillis = window.toMillis().also { require(it > 0) { "Rate-limit window is too small" } }

        return requireNotNull(
            jdbc.query(
                """
                INSERT INTO rate_limit_buckets(scope_key, window_started_at, attempt_count, expires_at)
                VALUES (?, statement_timestamp(), 1, statement_timestamp() + (? * interval '1 millisecond'))
                ON CONFLICT (scope_key) DO UPDATE SET
                    window_started_at = CASE
                        WHEN rate_limit_buckets.expires_at <= statement_timestamp() THEN statement_timestamp()
                        ELSE rate_limit_buckets.window_started_at
                    END,
                    attempt_count = CASE
                        WHEN rate_limit_buckets.expires_at <= statement_timestamp() THEN 1
                        ELSE rate_limit_buckets.attempt_count + 1
                    END,
                    expires_at = CASE
                        WHEN rate_limit_buckets.expires_at <= statement_timestamp()
                            THEN statement_timestamp() + (? * interval '1 millisecond')
                        ELSE rate_limit_buckets.expires_at
                    END
                RETURNING attempt_count,
                    GREATEST(
                        1,
                        CEIL(EXTRACT(EPOCH FROM (expires_at - statement_timestamp())))::bigint
                    ) AS retry_after_seconds
                """.trimIndent(),
                { result, _ ->
                    RateLimitWindow(
                        attemptCount = result.getInt("attempt_count"),
                        retryAfterSeconds = result.getLong("retry_after_seconds"),
                    )
                },
                scopeKey,
                windowMillis,
                windowMillis,
            ).singleOrNull(),
        )
    }
}
