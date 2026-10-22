package com.cookie.identity.messaging

import com.cookie.identity.persistence.JdbcOutboxRepository
import com.cookie.identity.persistence.OutboxRecord
import com.cookie.platform.messaging.EventEnvelope
import com.cookie.platform.messaging.subject
import io.nats.client.impl.Headers
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min

@Component
class OutboxPublisher(
    private val repository: JdbcOutboxRepository,
    private val nats: NatsJetStreamConnection,
    private val objectMapper: ObjectMapper,
    private val random: SecureRandom,
    private val metrics: OutboxMetrics,
) {
    @Scheduled(fixedDelayString = "\${cookie.identity.outbox-poll-delay:500}")
    fun publishAvailable() {
        if (Thread.currentThread().isInterrupted) return
        val claimed = repository.claim(BATCH_SIZE, LEASE, UUID.randomUUID())
        publishBatch(claimed)
    }

    private fun publishBatch(records: List<OutboxRecord>) {
        if (records.isEmpty()) return
        records.forEach { metrics.recordPublishAttempt(it.eventType) }
        val jetStream = try {
            nats.jetStream()
        } catch (interrupted: InterruptedException) {
            try {
                releaseAll(records, interrupted)
            } finally {
                Thread.currentThread().interrupt()
            }
            return
        } catch (exception: Exception) {
            releaseAll(records, exception)
            return
        }
        val pending = records.mapNotNull { record ->
            if (Thread.currentThread().isInterrupted) return
            try {
                val payloadNode = objectMapper.readTree(record.payload)
                val envelope = EventEnvelope(
                    eventId = record.eventId,
                    eventType = record.eventType,
                    eventVersion = record.eventVersion,
                    occurredAt = record.occurredAt,
                    producer = "identity",
                    aggregateType = record.aggregateType,
                    aggregateId = record.aggregateId,
                    payload = payloadNode,
                    correlationId = record.correlationId,
                    causationId = record.causationId,
                    traceId = record.traceId,
                )
                val headers = Headers().add("Nats-Msg-Id", record.eventId.toString())
                val subject = envelope.subject()
                val body = objectMapper.writeValueAsString(envelope).toByteArray(StandardCharsets.UTF_8)
                record to jetStream.publishAsync(subject, headers, body)
            } catch (interrupted: InterruptedException) {
                try { releaseSafely(record, interrupted) } finally { Thread.currentThread().interrupt() }
                return
            } catch (exception: Exception) {
                releaseSafely(record, exception)
                if (Thread.currentThread().isInterrupted) return
                null
            }
        }
        val acknowledgementDeadline = System.nanoTime() + ACK_TIMEOUT.toNanos()
        pending.forEachIndexed { index, (record, acknowledgement) ->
            if (Thread.currentThread().isInterrupted) return
            try {
                val remainingNanos = (acknowledgementDeadline - System.nanoTime()).coerceAtLeast(0)
                acknowledgement.get(remainingNanos, TimeUnit.NANOSECONDS)
                if (!repository.markPublished(record.eventId, record.claimId)) {
                    metrics.recordStaleCompletion(record.eventType)
                    logger.info("Ignored stale outbox acknowledgement eventId={}", record.eventId)
                } else {
                    metrics.recordPublishSuccess(record.eventType)
                }
            } catch (interrupted: InterruptedException) {
                try {
                    releaseAll(pending.subList(index, pending.size).map { it.first }, interrupted)
                } finally {
                    Thread.currentThread().interrupt()
                }
                return
            } catch (exception: Exception) {
                releaseSafely(record, exception)
            }
        }
    }

    private fun releaseAll(records: List<OutboxRecord>, cause: Exception) {
        for (record in records) {
            if (Thread.currentThread().isInterrupted) return
            releaseSafely(record, cause)
        }
    }

    private fun releaseSafely(record: OutboxRecord, cause: Exception) {
        try { release(record, cause) }
        catch (failure: Exception) {
            metrics.recordRecoveryFailure(record.eventType)
            // A failed release leaves the fenced lease recoverable; other records can proceed.
            logger.warn("Outbox recovery failed eventId={} cause={}", record.eventId, failure.javaClass.simpleName)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
        }
    }

    private fun release(record: OutboxRecord, exception: Exception) {
        metrics.recordPublishFailure(record.eventType)
        val retryAt = repository.release(
            record.eventId,
            record.claimId,
            outboxRetryBackoff(record.attemptCount) { bound -> random.nextLong(bound) },
            exception.message ?: exception.javaClass.simpleName,
        )
        if (retryAt == null) {
            logger.info("Ignored stale outbox release eventId={}", record.eventId)
            return
        }
        logger.warn(
            "Outbox publish failed eventId={} attempt={} retryAt={}",
            record.eventId,
            record.attemptCount,
            retryAt,
        )
    }

    companion object {
        private val LEASE: Duration = Duration.ofSeconds(30)
        private val ACK_TIMEOUT: Duration = Duration.ofSeconds(10)
        private const val BATCH_SIZE = 100
        private val logger = LoggerFactory.getLogger(OutboxPublisher::class.java)
    }
}

internal fun outboxRetryBackoff(attempt: Int, nextLong: (Long) -> Long): Duration {
    val exponent = min((attempt - 1).coerceAtLeast(0), MAX_BACKOFF_EXPONENT)
    val exponentialMillis = min(MAX_BACKOFF_MILLIS, MIN_BACKOFF_MILLIS shl exponent)
    if (exponentialMillis == MIN_BACKOFF_MILLIS) return Duration.ofMillis(MIN_BACKOFF_MILLIS)

    // Equal jitter avoids synchronized retries while retaining an exponential lower bound.
    val lowerBoundMillis = exponentialMillis / 2
    val randomRange = exponentialMillis - lowerBoundMillis
    val jitterMillis = nextLong(randomRange + 1)
    require(jitterMillis in 0..randomRange) { "Random jitter must be within the requested bound" }
    return Duration.ofMillis(lowerBoundMillis + jitterMillis)
}

private const val MIN_BACKOFF_MILLIS = 1_000L
private const val MAX_BACKOFF_MILLIS = 300_000L
private const val MAX_BACKOFF_EXPONENT = 9
