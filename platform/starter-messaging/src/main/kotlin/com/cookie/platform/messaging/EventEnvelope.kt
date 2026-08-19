package com.cookie.platform.messaging

import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

data class EventEnvelope(
    val eventId: UUID,
    val eventType: String,
    val eventVersion: Int,
    val occurredAt: Instant,
    val producer: String,
    val aggregateType: String,
    val aggregateId: String,
    val payload: JsonNode,
)

fun EventEnvelope.subject(): String = "cookie.events.$eventType.v$eventVersion"
