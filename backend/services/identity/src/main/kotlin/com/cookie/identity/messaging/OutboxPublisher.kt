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
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min

@Component
class OutboxPublisher(
    private val repository: JdbcOutboxRepository,
    private val nats: NatsJetStreamConnection,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val random: SecureRandom,
) {
    @Scheduled(fixedDelayString = "\${cookie.identity.outbox-poll-delay:500}")
    fun publishAvailable() {
        val claimed = repository.claim(BATCH_SIZE, LEASE, UUID.randomUUID())
        publishBatch(claimed)
    }

    private fun publishBatch(records: List<OutboxRecord>) {
        if (records.isEmpty()) return
        val jetStream = try {
            nats.jetStream()
        } catch (exception: Exception) {
            records.forEach { record -> release(record, exception) }
            return
        }
        val pending = records.mapNotNull { record ->
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
            } catch (exception: Exception) {
                release(record, exception)
                null
            }
        }
        val acknowledgementDeadline = System.nanoTime() + ACK_TIMEOUT.toNanos()
        pending.forEach { (record, acknowledgement) ->
            try {
                val remainingNanos = (acknowledgementDeadline - System.nanoTime()).coerceAtLeast(0)
                acknowledgement.get(remainingNanos, TimeUnit.NANOSECONDS)
                if (!repository.markPublished(record.eventId, record.claimId, clock.instant())) {
                    logger.info("Ignored stale outbox acknowledgement eventId={}", record.eventId)
                }
            } catch (exception: Exception) {
                release(record, exception)
            }
        }
    }

    private fun release(record: OutboxRecord, exception: Exception) {
        val retryAt = clock.instant().plus(backoff(record.attemptCount))
        val released = repository.release(
            record.eventId,
            record.claimId,
            retryAt,
            exception.message ?: exception.javaClass.simpleName,
        )
        if (!released) {
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

    private fun backoff(attempt: Int): Duration {
        val exponent = min((attempt - 1).coerceAtLeast(0), 9)
        val baseSeconds = min(300L, 1L shl exponent)
        val jitterMillis = random.nextLong(baseSeconds * 500L + 1L)
        return Duration.ofMillis(min(300_000L, baseSeconds * 1000L + jitterMillis))
    }

    companion object {
        private val LEASE: Duration = Duration.ofSeconds(30)
        private val ACK_TIMEOUT: Duration = Duration.ofSeconds(10)
        private const val BATCH_SIZE = 100
        private val logger = LoggerFactory.getLogger(OutboxPublisher::class.java)
    }
}
