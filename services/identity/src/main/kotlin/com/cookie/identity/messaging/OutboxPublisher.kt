package com.cookie.identity.messaging

import com.cookie.identity.config.IdentityProperties
import com.cookie.identity.persistence.IdentityRepository
import com.cookie.identity.persistence.OutboxRecord
import com.cookie.platform.postgres.Transactions
import io.nats.client.impl.Headers
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import kotlin.math.min

@Component
class OutboxPublisher(
    private val properties: IdentityProperties,
    private val repository: IdentityRepository,
    private val transactions: Transactions,
    private val nats: NatsJetStreamConnection,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val random: SecureRandom,
) {
    @Scheduled(fixedDelayString = "\${cookie.identity.outbox-poll-delay:500}")
    fun publishAvailable() {
        if (!properties.outboxEnabled) return
        val claimed = transactions.required { repository.claimOutbox(BATCH_SIZE, LEASE) }
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
                val envelope = objectMapper.createObjectNode()
                    .put("event_id", record.eventId.toString())
                    .put("event_type", record.eventType)
                    .put("event_version", record.eventVersion)
                    .put("occurred_at", record.occurredAt.toString())
                    .put("producer", "identity")
                    .put("aggregate_type", record.aggregateType)
                    .put("aggregate_id", record.aggregateId)
                    .set("payload", payloadNode)
                val headers = Headers().add("Nats-Msg-Id", record.eventId.toString())
                val subject = "cookie.events.${record.eventType}.v${record.eventVersion}"
                val body = objectMapper.writeValueAsString(envelope).toByteArray(StandardCharsets.UTF_8)
                record to jetStream.publishAsync(subject, headers, body)
            } catch (exception: Exception) {
                release(record, exception)
                null
            }
        }
        pending.forEach { (record, acknowledgement) ->
            try {
                acknowledgement.join()
                repository.markOutboxPublished(record.eventId, clock.instant())
            } catch (exception: Exception) {
                release(record, exception)
            }
        }
    }

    private fun release(record: OutboxRecord, exception: Exception) {
        val retryAt = clock.instant().plus(backoff(record.attemptCount))
        repository.releaseOutbox(record.eventId, retryAt, exception.message ?: exception.javaClass.simpleName)
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
        private const val BATCH_SIZE = 100
        private val logger = LoggerFactory.getLogger(OutboxPublisher::class.java)
    }
}
