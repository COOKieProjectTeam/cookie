package com.cookie.identity.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Repository
class JdbcOutboxRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(
        eventId: UUID,
        eventType: String,
        eventVersion: Int,
        aggregateType: String,
        aggregateId: String,
        payloadJson: String,
        occurredAt: Instant,
        correlationId: UUID?,
        causationId: UUID?,
        traceId: String?,
    ) {
        requireActiveTransaction("Insert transactional outbox event")
        requireSingleRow(
            "Insert outbox event",
            jdbc.update(
                """
                INSERT INTO outbox_events(
                    event_id, event_type, event_version, aggregate_type, aggregate_id,
                    payload, occurred_at, available_at, correlation_id,
                    causation_id, trace_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """.trimIndent(),
                eventId,
                eventType,
                eventVersion,
                aggregateType,
                aggregateId,
                payloadJson,
                occurredAt.asJdbcTimestamp(),
                occurredAt.asJdbcTimestamp(),
                correlationId,
                causationId,
                traceId,
            ),
        )
    }

    fun claim(batchSize: Int, lease: Duration, claimId: UUID): List<OutboxRecord> {
        require(batchSize > 0) { "Outbox batch size must be positive" }
        require(!lease.isZero && !lease.isNegative) { "Outbox lease must be positive" }
        val leaseMillis = lease.toMillis().also { require(it > 0) { "Outbox lease is too small" } }

        return jdbc.query(
            """
            WITH candidates AS (
                SELECT event_id
                FROM outbox_events
                WHERE published_at IS NULL
                  AND available_at <= statement_timestamp()
                  AND (claimed_until IS NULL OR claimed_until <= statement_timestamp())
                ORDER BY occurred_at, event_id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE outbox_events o
            SET claim_id = ?,
                claimed_until = statement_timestamp() + (? * interval '1 millisecond'),
                attempt_count = attempt_count + 1
            FROM candidates c
            WHERE o.event_id = c.event_id
            RETURNING o.event_id, o.claim_id, o.event_type, o.event_version,
                      o.aggregate_type, o.aggregate_id, o.payload::text,
                      o.occurred_at, o.attempt_count, o.correlation_id,
                      o.causation_id, o.trace_id
            """.trimIndent(),
            { result, _ ->
                OutboxRecord(
                    eventId = result.getObject("event_id", UUID::class.java),
                    claimId = result.getObject("claim_id", UUID::class.java),
                    eventType = result.getString("event_type"),
                    eventVersion = result.getInt("event_version"),
                    aggregateType = result.getString("aggregate_type"),
                    aggregateId = result.getString("aggregate_id"),
                    payload = result.getString("payload"),
                    occurredAt = result.getTimestamp("occurred_at").toInstant(),
                    attemptCount = result.getInt("attempt_count"),
                    correlationId = result.getObject("correlation_id", UUID::class.java),
                    causationId = result.getObject("causation_id", UUID::class.java),
                    traceId = result.getString("trace_id"),
                )
            },
            batchSize,
            claimId,
            leaseMillis,
        )
    }

    fun markPublished(eventId: UUID, claimId: UUID): Boolean = jdbc.update(
        """
        UPDATE outbox_events
        SET published_at = GREATEST(statement_timestamp(), occurred_at),
            claim_id = NULL, claimed_until = NULL, last_error = NULL
        WHERE event_id = ? AND claim_id = ? AND published_at IS NULL
        """.trimIndent(),
        eventId,
        claimId,
    ) == 1

    fun release(eventId: UUID, claimId: UUID, delay: Duration, error: String): Instant? {
        require(!delay.isNegative) { "Outbox retry delay must not be negative" }
        val delayMillis = delay.toMillis()
        return jdbc.query(
            """
            UPDATE outbox_events
            SET available_at = statement_timestamp() + (? * interval '1 millisecond'),
                claim_id = NULL, claimed_until = NULL, last_error = ?
            WHERE event_id = ? AND claim_id = ? AND published_at IS NULL
            RETURNING available_at
            """.trimIndent(),
            { result, _ -> result.getTimestamp("available_at").toInstant() },
            delayMillis,
            error.take(MAX_ERROR_LENGTH),
            eventId,
            claimId,
        ).singleOrNull()
    }

    fun deletePublishedBefore(olderThan: Instant, batchSize: Int): Int {
        require(batchSize > 0) { "Outbox cleanup batch size must be positive" }
        return jdbc.update(
            """
            WITH expired AS (
                SELECT event_id
                FROM outbox_events
                WHERE published_at < ?
                ORDER BY published_at, event_id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            DELETE FROM outbox_events o
            USING expired e
            WHERE o.event_id = e.event_id
            """.trimIndent(),
            olderThan.asJdbcTimestamp(),
            batchSize,
        )
    }

    fun backlogSnapshot(): OutboxBacklogSnapshot = requireNotNull(
        jdbc.query(
            """
            SELECT count(*)::bigint AS pending_count,
                   COALESCE(
                       GREATEST(0, EXTRACT(EPOCH FROM (statement_timestamp() - min(occurred_at)))),
                       0
                   )::double precision AS oldest_pending_age_seconds
            FROM outbox_events
            WHERE published_at IS NULL
            """.trimIndent(),
            { result, _ ->
                OutboxBacklogSnapshot(
                    pendingCount = result.getLong("pending_count"),
                    oldestPendingAgeSeconds = result.getDouble("oldest_pending_age_seconds"),
                )
            },
        ).singleOrNull(),
    )

    private companion object {
        const val MAX_ERROR_LENGTH = 1000
    }
}
