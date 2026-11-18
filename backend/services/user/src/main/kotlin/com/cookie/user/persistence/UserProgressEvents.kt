package com.cookie.user.persistence

import com.cookie.platform.messaging.ProgressSourceRequest
import com.cookie.platform.postgres.ProgressEvidence
import com.cookie.platform.postgres.runProgressCommitWorker
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

internal class UserProgressEvents(private val store: JdbcUserStore) {
    fun evidence(evidence: ProgressEvidence) {
        val (type, fields, version) = when (evidence) {
            is ProgressEvidence.Group -> Triple("user.progress_commit.recorded", mapOf(
                "firstSeq" to evidence.firstSeq, "lastSeq" to evidence.lastSeq, "committedAt" to evidence.committedAt.toString(),
            ), evidence.lastSeq)
            is ProgressEvidence.Cut -> Triple("user.progress_cut.confirmed", mapOf(
                "boundary" to evidence.boundary.toString(), "beforeSeq" to evidence.beforeSeq, "throughSeq" to evidence.throughSeq,
            ), evidence.throughSeq)
        }
        enqueue(evidence.userId, type, maxOf(1, version), store.mapper.valueToTree(fields + mapOf(
            "userId" to evidence.userId, "endLsn" to evidence.endLsn,
        )))
    }

    private fun enqueue(user: UUID, type: String, version: Long, payload: JsonNode) {
        store.insertOutbox(store.newId(), user, type, version, 1, store.clock.instant(), payload)
    }

    fun handle(subject: String, bytes: ByteArray) {
        val request = try { ProgressSourceRequest.parse(store.mapper, "user", subject, bytes) }
        catch (_: RuntimeException) { throw InvalidUserEvent("invalid_progress_source_request") }
        store.transactions.executeWithoutResult {
            store.lock(request.user)
            if (store.jdbc.update("""INSERT INTO inbox_events(consumer_name,event_id,event_type) VALUES (?,?,?)
                 ON CONFLICT DO NOTHING""", LifecycleEvents.CONSUMER, request.id, subject) == 0 || store.deleted(request.user)
            ) return@executeWithoutResult
            when (request) {
                is ProgressSourceRequest.Cut -> store.progress.requestCut(request.user, request.boundary)
                is ProgressSourceRequest.Replay -> store.progress.replay(request.user, request.first, request.last) { original ->
                    enqueue(request.user, "user.progress_fact.replayed", 1,
                        store.mapper.valueToTree(mapOf("userId" to request.user, "event" to store.mapper.readTree(original))))
                }
            }
        }
    }
}

internal fun runUserCommitWorker() = runProgressCommitWorker("user") { jdbc, tx ->
    JdbcUserStore(jdbc, tx, JsonMapper.builder().findAndAddModules().build(), Clock.systemUTC()).progress
}
