package com.cookie.identity.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class IdentityRepository(private val jdbc: JdbcTemplate) {
    fun advisoryLock(value: String) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", { _: ResultSet -> }, value)
    }

    fun findCredentialByEmail(email: String, forUpdate: Boolean = false): CredentialRecord? {
        val lock = if (forUpdate) " FOR UPDATE OF ec" else ""
        return jdbc.query(
            """
            SELECT a.id, a.status, ec.email, ec.password_hash, ec.email_verified_at,
                   ec.failed_login_count, ec.locked_until, a.created_at
            FROM email_credentials ec
            JOIN accounts a ON a.id = ec.account_id
            WHERE ec.email = ?$lock
            """.trimIndent(),
            { rs, _ -> credential(rs) },
            email,
        ).firstOrNull()
    }

    fun insertAccount(accountId: UUID, email: String, passwordHash: String, now: Instant) {
        jdbc.update(
            "INSERT INTO accounts(id, status, created_at) VALUES (?, 'PENDING_VERIFICATION', ?)",
            accountId,
            now.asJdbcTimestamp(),
        )
        jdbc.update(
            """
            INSERT INTO email_credentials(
                account_id, email, password_hash, failed_login_count, created_at, updated_at
            ) VALUES (?, ?, ?, 0, ?, ?)
            """.trimIndent(),
            accountId,
            email,
            passwordHash,
            now.asJdbcTimestamp(),
            now.asJdbcTimestamp(),
        )
    }

    fun insertActionToken(
        id: UUID,
        accountId: UUID,
        tokenHash: String,
        expiresAt: Instant,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO auth_action_tokens(
                id, account_id, purpose, token_hash, expires_at, created_at
            ) VALUES (?, ?, 'EMAIL_VERIFICATION', ?, ?, ?)
            """.trimIndent(),
            id,
            accountId,
            tokenHash,
            expiresAt.asJdbcTimestamp(),
            now.asJdbcTimestamp(),
        )
    }

    fun findActionTokenForUpdate(id: UUID): ActionTokenRecord? = jdbc.query(
        """
        SELECT id, account_id, token_hash, expires_at, consumed_at, revoked_at, created_at
        FROM auth_action_tokens
        WHERE id = ? AND purpose = 'EMAIL_VERIFICATION'
        FOR UPDATE
        """.trimIndent(),
        { rs, _ -> actionToken(rs) },
        id,
    ).firstOrNull()

    fun latestActionToken(accountId: UUID): ActionTokenRecord? = jdbc.query(
        """
        SELECT id, account_id, token_hash, expires_at, consumed_at, revoked_at, created_at
        FROM auth_action_tokens
        WHERE account_id = ? AND purpose = 'EMAIL_VERIFICATION'
        ORDER BY created_at DESC
        LIMIT 1
        """.trimIndent(),
        { rs, _ -> actionToken(rs) },
        accountId,
    ).firstOrNull()

    fun revokeActiveActionTokens(accountId: UUID, now: Instant) {
        jdbc.update(
            """
            UPDATE auth_action_tokens
            SET revoked_at = ?
            WHERE account_id = ? AND purpose = 'EMAIL_VERIFICATION'
              AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > ?
            """.trimIndent(),
            now.asJdbcTimestamp(),
            accountId,
            now.asJdbcTimestamp(),
        )
    }

    fun consumeActionToken(id: UUID, now: Instant) {
        jdbc.update("UPDATE auth_action_tokens SET consumed_at = ? WHERE id = ?", now.asJdbcTimestamp(), id)
    }

    fun activateAccount(accountId: UUID, now: Instant): Instant? {
        val createdAt = jdbc.query(
            """
            UPDATE accounts
            SET status = 'ACTIVE', activated_at = ?
            WHERE id = ? AND status = 'PENDING_VERIFICATION'
            RETURNING created_at
            """.trimIndent(),
            { rs, _ -> rs.getTimestamp("created_at").toInstant() },
            now.asJdbcTimestamp(),
            accountId,
        ).singleOrNull()
        if (createdAt != null) {
            jdbc.update(
                "UPDATE email_credentials SET email_verified_at = ?, updated_at = ? WHERE account_id = ?",
                now.asJdbcTimestamp(),
                now.asJdbcTimestamp(),
                accountId,
            )
        }
        return createdAt
    }

    fun recordLoginFailure(accountId: UUID, newCount: Int, lockedUntil: Instant?, now: Instant) {
        jdbc.update(
            """
            UPDATE email_credentials
            SET failed_login_count = ?, locked_until = ?, updated_at = ?
            WHERE account_id = ?
            """.trimIndent(),
            newCount,
            lockedUntil?.asJdbcTimestamp(),
            now.asJdbcTimestamp(),
            accountId,
        )
    }

    fun resetLoginFailures(accountId: UUID, now: Instant) {
        jdbc.update(
            """
            UPDATE email_credentials
            SET failed_login_count = 0, locked_until = NULL, updated_at = ?
            WHERE account_id = ?
            """.trimIndent(),
            now.asJdbcTimestamp(),
            accountId,
        )
    }

    fun insertRefreshSession(
        id: UUID,
        accountId: UUID,
        familyId: UUID,
        tokenHash: String,
        deviceId: String?,
        familyExpiresAt: Instant,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO refresh_sessions(
                id, account_id, family_id, token_hash, status, device_id,
                family_expires_at, created_at
            ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
            """.trimIndent(),
            id,
            accountId,
            familyId,
            tokenHash,
            deviceId,
            familyExpiresAt.asJdbcTimestamp(),
            now.asJdbcTimestamp(),
        )
    }

    fun findRefreshSessionForUpdate(id: UUID): RefreshSessionRecord? = jdbc.query(
        """
        SELECT id, account_id, family_id, token_hash, status, device_id, family_expires_at
        FROM refresh_sessions
        WHERE id = ?
        FOR UPDATE
        """.trimIndent(),
        { rs, _ -> refreshSession(rs) },
        id,
    ).firstOrNull()

    fun rotateRefreshSession(id: UUID, replacementId: UUID, now: Instant) {
        jdbc.update(
            """
            UPDATE refresh_sessions
            SET status = 'ROTATED', replaced_by_session_id = ?, last_used_at = ?,
                revoked_at = ?, revoke_reason = 'ROTATED'
            WHERE id = ? AND status = 'ACTIVE'
            """.trimIndent(),
            replacementId,
            now.asJdbcTimestamp(),
            now.asJdbcTimestamp(),
            id,
        )
    }

    fun revokeSession(id: UUID, reason: String, now: Instant) {
        jdbc.update(
            """
            UPDATE refresh_sessions
            SET status = 'REVOKED', revoked_at = COALESCE(revoked_at, ?),
                revoke_reason = COALESCE(revoke_reason, ?)
            WHERE id = ? AND status = 'ACTIVE'
            """.trimIndent(),
            now.asJdbcTimestamp(),
            reason,
            id,
        )
    }

    fun revokeRefreshFamily(familyId: UUID, now: Instant) {
        jdbc.update(
            """
            UPDATE refresh_sessions
            SET status = 'REVOKED', revoked_at = COALESCE(revoked_at, ?),
                revoke_reason = 'REPLAY_DETECTED', reuse_detected_at = ?
            WHERE family_id = ?
            """.trimIndent(),
            now.asJdbcTimestamp(),
            now.asJdbcTimestamp(),
            familyId,
        )
    }

    fun consumeRateLimit(scopeKey: String, window: Duration): RateLimitResult = requireNotNull(
        jdbc.query(
            """
            INSERT INTO rate_limit_buckets(scope_key, window_started_at, attempt_count, expires_at)
            VALUES (?, clock_timestamp(), 1, clock_timestamp() + (? * interval '1 second'))
            ON CONFLICT (scope_key) DO UPDATE SET
                window_started_at = CASE
                    WHEN rate_limit_buckets.expires_at <= clock_timestamp() THEN clock_timestamp()
                    ELSE rate_limit_buckets.window_started_at
                END,
                attempt_count = CASE
                    WHEN rate_limit_buckets.expires_at <= clock_timestamp() THEN 1
                    ELSE rate_limit_buckets.attempt_count + 1
                END,
                expires_at = CASE
                    WHEN rate_limit_buckets.expires_at <= clock_timestamp()
                        THEN clock_timestamp() + (? * interval '1 second')
                    ELSE rate_limit_buckets.expires_at
                END
            RETURNING attempt_count, expires_at
            """.trimIndent(),
            { rs, _ -> RateLimitResult(rs.getInt("attempt_count"), rs.getTimestamp("expires_at").toInstant()) },
            scopeKey,
            window.seconds,
            window.seconds,
        ).singleOrNull(),
    )

    fun insertOutbox(
        eventId: UUID,
        eventType: String,
        eventVersion: Int,
        aggregateType: String,
        aggregateId: String,
        payloadJson: String,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO outbox_events(
                event_id, event_type, event_version, aggregate_type, aggregate_id,
                payload, occurred_at, available_at
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """.trimIndent(),
            eventId,
            eventType,
            eventVersion,
            aggregateType,
            aggregateId,
            payloadJson,
            now.asJdbcTimestamp(),
            now.asJdbcTimestamp(),
        )
    }

    fun claimOutbox(batchSize: Int, lease: Duration): List<OutboxRecord> = jdbc.query(
        """
        WITH candidates AS (
            SELECT event_id
            FROM outbox_events
            WHERE published_at IS NULL
              AND available_at <= clock_timestamp()
              AND (claimed_until IS NULL OR claimed_until <= clock_timestamp())
            ORDER BY occurred_at
            FOR UPDATE SKIP LOCKED
            LIMIT ?
        )
        UPDATE outbox_events o
        SET claimed_until = clock_timestamp() + (? * interval '1 second'),
            attempt_count = attempt_count + 1
        FROM candidates c
        WHERE o.event_id = c.event_id
        RETURNING o.event_id, o.event_type, o.event_version, o.aggregate_type,
                  o.aggregate_id, o.payload::text, o.occurred_at, o.attempt_count
        """.trimIndent(),
        { rs, _ ->
            OutboxRecord(
                eventId = rs.getObject("event_id", UUID::class.java),
                eventType = rs.getString("event_type"),
                eventVersion = rs.getInt("event_version"),
                aggregateType = rs.getString("aggregate_type"),
                aggregateId = rs.getString("aggregate_id"),
                payload = rs.getString("payload"),
                occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                attemptCount = rs.getInt("attempt_count"),
            )
        },
        batchSize,
        lease.seconds,
    )

    fun markOutboxPublished(eventId: UUID, now: Instant) {
        jdbc.update(
            """
            UPDATE outbox_events
            SET published_at = ?, claimed_until = NULL, last_error = NULL
            WHERE event_id = ? AND published_at IS NULL
            """.trimIndent(),
            now.asJdbcTimestamp(),
            eventId,
        )
    }

    fun releaseOutbox(eventId: UUID, availableAt: Instant, error: String) {
        jdbc.update(
            """
            UPDATE outbox_events
            SET available_at = ?, claimed_until = NULL, last_error = ?
            WHERE event_id = ? AND published_at IS NULL
            """.trimIndent(),
            availableAt.asJdbcTimestamp(),
            error.take(1000),
            eventId,
        )
    }

    fun insertInbox(consumer: String, eventId: UUID, eventType: String, now: Instant): Boolean = jdbc.update(
        """
        INSERT INTO inbox_events(consumer, event_id, event_type, processed_at)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (consumer, event_id) DO NOTHING
        """.trimIndent(),
        consumer,
        eventId,
        eventType,
        now.asJdbcTimestamp(),
    ) == 1

    private fun credential(rs: ResultSet) = CredentialRecord(
        accountId = rs.getObject("id", UUID::class.java),
        accountStatus = rs.getString("status"),
        email = rs.getString("email"),
        passwordHash = rs.getString("password_hash"),
        emailVerifiedAt = rs.getTimestamp("email_verified_at")?.toInstant(),
        failedLoginCount = rs.getInt("failed_login_count"),
        lockedUntil = rs.getTimestamp("locked_until")?.toInstant(),
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )

    private fun actionToken(rs: ResultSet) = ActionTokenRecord(
        id = rs.getObject("id", UUID::class.java),
        accountId = rs.getObject("account_id", UUID::class.java),
        tokenHash = rs.getString("token_hash"),
        expiresAt = rs.getTimestamp("expires_at").toInstant(),
        consumedAt = rs.getTimestamp("consumed_at")?.toInstant(),
        revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )

    private fun refreshSession(rs: ResultSet) = RefreshSessionRecord(
        id = rs.getObject("id", UUID::class.java),
        accountId = rs.getObject("account_id", UUID::class.java),
        familyId = rs.getObject("family_id", UUID::class.java),
        tokenHash = rs.getString("token_hash"),
        status = rs.getString("status"),
        deviceId = rs.getString("device_id"),
        familyExpiresAt = rs.getTimestamp("family_expires_at").toInstant(),
    )
}

private fun Instant.asJdbcTimestamp() = atOffset(ZoneOffset.UTC)
