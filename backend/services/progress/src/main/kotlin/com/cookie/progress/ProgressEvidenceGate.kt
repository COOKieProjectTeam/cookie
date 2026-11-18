package com.cookie.progress

import org.springframework.stereotype.Component
import java.time.Instant

@Component
internal class ProgressEvidenceGate(private val store: ProgressStore) {
    private data class Checkpoint(val birth: Instant, val floor: Long, val blocked: Boolean)
    @Volatile private var intakeValidated = false

    fun ready(): Boolean = intakeValidated && store.jdbc.queryForObject(
        "SELECT NOT reconciliation_required FROM progress_broker_checkpoint WHERE singleton", Boolean::class.java,
    ) == true

    fun requireReadable() {
        if (!ready()) throw ProgressFailure(503, "PROJECTION_RECONCILIATION_REQUIRED")
    }

    fun inspect(createdAt: Instant, firstAvailable: Long, ackFloor: Long) {
        require(firstAvailable >= 0 && ackFloor >= 0)
        val birth = createdAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        intakeValidated = store.transactions.execute {
            store.jdbc.update(
                """INSERT INTO progress_broker_checkpoint(stream_created_at,ack_floor,reconciliation_required)
                    VALUES (?,?,?) ON CONFLICT DO NOTHING""", birth.sqlTime(), ackFloor, firstAvailable > 1,
            )
            val checkpoint = store.jdbc.queryForObject(
                "SELECT * FROM progress_broker_checkpoint WHERE singleton FOR UPDATE",
                { row, _ -> Checkpoint(checkNotNull(row.instant("stream_created_at")), row.getLong("ack_floor"),
                    row.getBoolean("reconciliation_required")) },
            )
            val unsafe = checkpoint.blocked || checkpoint.birth != birth ||
                ackFloor < checkpoint.floor || firstAvailable - 1 > checkpoint.floor
            store.jdbc.update(
                """UPDATE progress_broker_checkpoint SET ack_floor=GREATEST(ack_floor,?),
                   reconciliation_required=reconciliation_required OR ? WHERE singleton""", ackFloor, unsafe,
            )
            !unsafe
        } == true
    }
}
