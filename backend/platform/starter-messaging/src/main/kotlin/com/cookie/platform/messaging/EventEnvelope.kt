package com.cookie.platform.messaging

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

data class EventEnvelope(
    @get:JsonProperty("event_id")
    val eventId: UUID,
    @get:JsonProperty("event_type")
    val eventType: String,
    @get:JsonProperty("event_version")
    val eventVersion: Int,
    @get:JsonProperty("occurred_at")
    val occurredAt: Instant,
    val producer: String,
    @get:JsonProperty("aggregate_type")
    val aggregateType: String,
    @get:JsonProperty("aggregate_id")
    val aggregateId: String,
    val payload: JsonNode,
    @get:JsonProperty("aggregate_version")
    val aggregateVersion: Long? = null,
    @get:JsonProperty("correlation_id")
    val correlationId: UUID? = null,
    @get:JsonProperty("causation_id")
    val causationId: UUID? = null,
    @get:JsonProperty("trace_id")
    val traceId: String? = null,
)

fun EventEnvelope.subject(): String = "cookie.events.$eventType.v$eventVersion"
