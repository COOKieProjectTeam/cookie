package com.cookie.platform.messaging

import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

sealed interface ProgressSourceRequest {
    val id: UUID
    val user: UUID

    data class Cut(override val id: UUID, override val user: UUID, val boundary: Instant) : ProgressSourceRequest
    data class Replay(override val id: UUID, override val user: UUID, val first: Long, val last: Long) : ProgressSourceRequest

    companion object {
        fun subjects(owner: String) = listOf(
            "cookie.events.progress.${owner}_cut.requested.v1",
            "cookie.events.progress.${owner}_replay.requested.v1",
        )

        fun parse(mapper: ObjectMapper, owner: String, subject: String, bytes: ByteArray): ProgressSourceRequest {
            require(bytes.size <= 8192)
            val event = mapper.readTree(bytes)
            val type = checkNotNull(event.path("event_type").stringValue())
            val version = event.path("event_version")
            require(subject in subjects(owner) && subject == "cookie.events.$type.v1")
            require(version.isIntegralNumber && version.canConvertToInt() && version.intValue() == 1)
            require(event.path("producer").asString() == "progress")
            Instant.parse(event.path("occurred_at").stringValue())
            val id = UUID.fromString(event.path("event_id").stringValue())
            val payload = event.path("payload")
            val user = UUID.fromString(payload.path("userId").stringValue())
            require(event.path("aggregate_type").asString() == "progress_source" && event.path("aggregate_id").asString() == user.toString())
            return if (type == "progress.${owner}_cut.requested") {
                val boundary = Instant.parse(payload.path("boundary").stringValue())
                require(boundary.nano == 0)
                Cut(id, user, boundary)
            } else {
                val first = payload.path("firstSeq")
                val last = payload.path("lastSeq")
                require(first.isIntegralNumber && first.canConvertToLong() && last.isIntegralNumber && last.canConvertToLong())
                val start = first.longValue()
                val end = last.longValue()
                require(start > 0 && end >= start && end - start < 64)
                Replay(id, user, start, end)
            }
        }
    }
}
