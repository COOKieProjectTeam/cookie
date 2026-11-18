package com.cookie.progress

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.Duration

@Component
internal class ProgressEvidenceGate(private val store: ProgressStore) {
    data class BrokerState(
        val streamCreated: Instant, val consumerCreated: Instant, val firstAvailable: Long, val ackFloor: Long,
        val ackDeliveryFloor: Long, val pending: Long = 0, val configurationMatches: Boolean = true,
    )

    private data class Checkpoint(
        val stream: Instant, val consumer: Instant?, val floor: Long, val deliveryFloor: Long, val blocked: Boolean,
    )

    private val log = LoggerFactory.getLogger(javaClass)
    @Volatile private var intakeValidated = false
    @Volatile private var lastValidatedNanos = 0L

    fun ready(): Boolean = intakeValidated && System.nanoTime() - lastValidatedNanos < CHECK_TTL_NANOS && store.jdbc.queryForObject(
        "SELECT NOT reconciliation_required FROM progress_broker_checkpoint WHERE singleton", Boolean::class.java,
    ) == true

    fun requireReadable() {
        if (!ready()) throw ProgressFailure(503, "PROJECTION_RECONCILIATION_REQUIRED")
    }

    fun inspect(observe: () -> BrokerState) {
        intakeValidated = store.transactions.execute {
            // Take the lock before sampling NATS so concurrent replicas cannot pin observations in reverse order.
            store.jdbc.query("SELECT pg_advisory_xact_lock(20261118, 1)", { _, _ -> Unit })
            val state = observe()
            require(state.firstAvailable >= 0 && state.ackFloor >= 0 && state.ackDeliveryFloor >= 0)
            val stream = state.streamCreated.micros()
            val consumer = state.consumerCreated.micros()
            store.jdbc.update(
                """INSERT INTO progress_broker_checkpoint(stream_created_at,consumer_created_at,ack_floor)
                    VALUES (?,?,0) ON CONFLICT DO NOTHING""", stream.sqlTime(), consumer.sqlTime(),
            )
            val checkpoint = store.jdbc.queryForObject(
                "SELECT * FROM progress_broker_checkpoint WHERE singleton FOR UPDATE",
                { row, _ -> Checkpoint(checkNotNull(row.instant("stream_created_at")), row.instant("consumer_created_at"),
                    row.getLong("ack_floor"), row.getLong("ack_delivery_floor"), row.getBoolean("reconciliation_required")) },
            )
            // The stream floor can span unrelated subjects. Use the durable delivery floor to find a receipt.
            val missingReceipt = state.ackDeliveryFloor > checkpoint.deliveryFloor && !hasReceipt(state)
            val error = when {
                checkpoint.blocked -> return@execute false
                checkpoint.stream != stream || checkpoint.consumer?.let { it != consumer } == true -> "broker_epoch_changed"
                !state.configurationMatches -> "unexpected_durable_configuration"
                state.ackFloor < checkpoint.floor || state.ackDeliveryFloor < checkpoint.deliveryFloor -> "ack_floor_rewound"
                state.firstAvailable - 1 > checkpoint.floor -> "missing_stream_prefix"
                missingReceipt && state.pending == 0L -> "missing_local_receipt"
                else -> null
            }
            if (error != null) {
                store.jdbc.update("UPDATE progress_broker_checkpoint SET reconciliation_required=true WHERE singleton")
                log.warn("Progress reads need broker reconciliation (reason={})", error)
                return@execute false
            }
            if (missingReceipt) return@execute false
            store.jdbc.update("""UPDATE progress_broker_checkpoint
                SET ack_floor=?,ack_delivery_floor=?,consumer_created_at=? WHERE singleton""",
                state.ackFloor, state.ackDeliveryFloor, consumer.sqlTime())
            store.jdbc.update("DELETE FROM progress_broker_receipts WHERE consumer_sequence<=?", state.ackDeliveryFloor)
            true
        } == true
        if (intakeValidated) lastValidatedNanos = System.nanoTime()
    }

    private fun hasReceipt(state: BrokerState): Boolean = store.jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM progress_broker_receipts WHERE consumer_sequence=? AND stream_sequence<=?)",
        Boolean::class.java, state.ackDeliveryFloor, state.ackFloor,
    ) == true

    private fun Instant.micros() = truncatedTo(java.time.temporal.ChronoUnit.MICROS)

    companion object { private val CHECK_TTL_NANOS = Duration.ofMinutes(2).toNanos() }
}
