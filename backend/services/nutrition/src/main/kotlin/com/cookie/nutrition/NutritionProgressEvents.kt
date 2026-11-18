package com.cookie.nutrition

import com.cookie.platform.messaging.EventEnvelope
import com.cookie.platform.messaging.ProgressSourceRequest
import com.cookie.platform.messaging.subject
import com.cookie.platform.postgres.ProgressEvidence
import com.cookie.platform.postgres.runProgressCommitWorker
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

internal class NutritionProgressEvents(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    fun evidence(evidence: ProgressEvidence) {
        val fields = when (evidence) {
            is ProgressEvidence.Group -> mapOf(
                "firstSeq" to evidence.firstSeq, "lastSeq" to evidence.lastSeq, "committedAt" to evidence.committedAt.toString(),
            )
            is ProgressEvidence.Cut -> mapOf(
                "boundary" to evidence.boundary.toString(), "beforeSeq" to evidence.beforeSeq, "throughSeq" to evidence.throughSeq,
            )
        }
        val type = when (evidence) {
            is ProgressEvidence.Group -> "nutrition.progress_commit.recorded"
            is ProgressEvidence.Cut -> "nutrition.progress_cut.confirmed"
        }
        enqueue(evidence.userId, type, mapper.valueToTree(fields + mapOf(
            "userId" to evidence.userId, "endLsn" to evidence.endLsn,
        )))
    }

    fun replay(user: UUID, original: String) {
        enqueue(user, "nutrition.progress_fact.replayed", mapper.valueToTree(mapOf("userId" to user, "event" to mapper.readTree(original))))
    }

    private fun enqueue(user: UUID, type: String, payload: JsonNode) {
        val event = EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(), "nutrition", "progress_source", user.toString(), payload)
        jdbc.update("INSERT INTO outbox_events(event_id,user_id,envelope,subject) VALUES (?,?,?::jsonb,?)",
            event.eventId, user, mapper.writeValueAsString(event), event.subject())
    }
}

internal fun NutritionService.handleProgressRequest(subject: String, bytes: ByteArray) {
    val request = try { ProgressSourceRequest.parse(mapper, "nutrition", subject, bytes) }
    catch (_: RuntimeException) { invalid() }
    tx.executeWithoutResult {
        lock(request.user)
        if (jdbc.update("INSERT INTO inbox_events(event_id) VALUES (?) ON CONFLICT DO NOTHING", request.id) == 0 ||
            jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM account_tombstones WHERE user_id=?)", Boolean::class.java, request.user) == true
        ) return@executeWithoutResult
        when (request) {
            is ProgressSourceRequest.Cut -> progress.requestCut(request.user, request.boundary)
            is ProgressSourceRequest.Replay -> {
                val outbox = NutritionProgressEvents(jdbc, mapper)
                progress.replay(request.user, request.first, request.last) { outbox.replay(request.user, it) }
            }
        }
    }
}

internal fun runNutritionCommitWorker() = runProgressCommitWorker("nutrition") { jdbc, tx ->
    NutritionService(jdbc, tx, JsonMapper.builder().findAndAddModules().build()).progress
}
