package com.cookie.health.messaging

import com.cookie.health.persistence.HealthDatabase
import com.cookie.health.persistence.HealthMeasurements
import com.cookie.platform.messaging.JetStreamPullConsumer
import com.cookie.health.config.HealthProperties
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

class InvalidHealthEvent(val reason: String) : RuntimeException(reason)

@Component
class HealthDeletion(private val database: HealthDatabase, private val mapper: ObjectMapper) {
    private data class Request(val event: UUID, val account: UUID, val deletion: UUID, val requestedAt: Instant)

    fun handle(subject: String, body: ByteArray) {
        val request = try {
            require(subject == SUBJECT && body.size <= 65536)
            val event = mapper.readTree(body)
            require(event.path("event_version").isIntegralNumber && event.path("event_version").asLong() == 1L)
            require(event.path("event_type").asString() == TYPE && event.path("producer").asString() == "identity")
            require(event.path("aggregate_type").asString() == "account")
            Instant.parse(event.path("occurred_at").asString())
            val payload = event.path("payload")
            val account = UUID.fromString(payload.path("accountId").asString())
            require(UUID.fromString(event.path("aggregate_id").asString()) == account)
            Request(UUID.fromString(event.path("event_id").asString()), account,
                UUID.fromString(payload.path("deletionRequestId").asString()),
                Instant.parse(payload.path("requestedAt").asString()))
        } catch (_: Exception) {
            throw InvalidHealthEvent("invalid_deletion_envelope")
        }
        database.transactions.executeWithoutResult {
            database.lock(request.account)
            val inserted = database.jdbc.update(
                "INSERT INTO inbox_events(consumer_name,event_id,event_type) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                CONSUMER, request.event, TYPE,
            )
            if (inserted == 0) return@executeWithoutResult
            database.jdbc.update(
                "INSERT INTO account_tombstones(user_id,deletion_request_id,deleted_at) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                request.account, request.deletion, java.sql.Timestamp.from(request.requestedAt),
            )
            for (table in ERASE_IN_ORDER) database.jdbc.update("DELETE FROM $table WHERE user_id=?", request.account)
        }
    }

    fun activate(body: ByteArray, measurements: HealthMeasurements) {
        val (id, account) = try {
            require(body.size <= 65536)
            val event = mapper.readTree(body)
            require(event.path("event_version").isIntegralNumber && event.path("event_version").asLong() == 1L)
            require(event.path("event_type").asString() == "account.activated" && event.path("producer").asString() == "identity")
            require(event.path("aggregate_type").asString() == "account")
            Instant.parse(event.path("occurred_at").asString())
            val user = UUID.fromString(event.path("payload").path("userId").asString())
            require(UUID.fromString(event.path("aggregate_id").asString()) == user)
            UUID.fromString(event.path("event_id").asString()) to user
        } catch (_: Exception) {
            throw InvalidHealthEvent("invalid_activation_envelope")
        }
        database.transactions.executeWithoutResult {
            database.lock(account)
            val inserted = database.jdbc.update(
                "INSERT INTO inbox_events(consumer_name,event_id,event_type) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                CONSUMER, id, "account.activated",
            )
            if (inserted != 0 && !database.deleted(account)) measurements.connectionSummary(account)
        }
    }

    companion object {
        private val ERASE_IN_ORDER = listOf("outbox_events", "assessment_executions", "command_receipts", "import_batches", "purge_jobs",
            "weight_measurements", "activity_source_days", "activity_day_versions", "primary_connection_periods", "connections", "connection_summaries")
        const val CONSUMER = "health-lifecycle-v1"
        const val TYPE = "account.deletion.requested"
        const val SUBJECT = "cookie.events.account.deletion.requested.v1"
        const val ACTIVATION = "cookie.events.account.activated.v1"
    }
}

@Component
class HealthConsumer(
    broker: HealthBroker,
    private val properties: HealthProperties,
    deletion: HealthDeletion,
    assessments: HealthAssessmentExecution,
    measurements: HealthMeasurements,
    database: HealthDatabase,
    metrics: MeterRegistry,
) {
    private val failures = metrics.counter("cookie.health.inbox.failures")
    private val quarantined = metrics.counter("cookie.health.inbox.quarantined")
    private val runner = JetStreamPullConsumer(
        connection = broker::connection,
        stream = HealthBroker.STREAM,
        durable = HealthDeletion.CONSUMER,
        handle = {
            when (it.subject) {
                HealthAssessmentExecution.SUBJECT -> assessments.handle(it.subject, it.data)
                HealthDeletion.ACTIVATION -> deletion.activate(it.data, measurements)
                else -> deletion.handle(it.subject, it.data)
            }
        },
        invalidReason = { (it as? InvalidHealthEvent)?.reason },
        quarantine = { message, reason ->
            val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(message.data))
            database.jdbc.update(
                "INSERT INTO message_quarantine(message_hash,subject,reason,stream_sequence) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                digest, message.subject.take(255), reason, message.metaData().streamSequence(),
            )
        },
        processed = { metrics.counter("cookie.health.inbox.processed").increment() },
        quarantined = { quarantined.increment() },
        failed = { failures.increment() },
    )

    @Scheduled(fixedDelay = 100)
    fun poll() {
        if (properties.messagingEnabled) runner.poll()
    }
}
