package com.cookie.platform.messaging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

class EventEnvelopeTest {
    @Test
    fun `serializes the canonical snake case wire envelope and subject`() {
        val eventId = UUID.fromString("0198c790-8d64-7c9d-8b47-d53f4cae2711")
        val correlationId = UUID.fromString("0198c790-8d65-79af-b3f4-2ecbb36302d7")
        val objectMapper = JsonMapper.builder().findAndAddModules().build()
        val envelope = EventEnvelope(
            eventId = eventId,
            eventType = "account.activated",
            eventVersion = 1,
            occurredAt = Instant.parse("2026-08-20T10:00:00Z"),
            producer = "identity",
            aggregateType = "account",
            aggregateId = "0198c790-8d63-7276-a46e-e25d66f89caf",
            payload = objectMapper.createObjectNode().put("userId", "user-id"),
            correlationId = correlationId,
            traceId = "0123456789abcdef",
        )

        val json = objectMapper.readTree(objectMapper.writeValueAsBytes(envelope))

        assertThat(json.required("event_id").asString()).isEqualTo(eventId.toString())
        assertThat(json.required("event_type").asString()).isEqualTo("account.activated")
        assertThat(json.required("event_version").asInt()).isEqualTo(1)
        assertThat(json.has("occurred_at")).isTrue()
        assertThat(json.required("aggregate_type").asString()).isEqualTo("account")
        assertThat(json.required("aggregate_id").asString()).isEqualTo(envelope.aggregateId)
        assertThat(json.required("correlation_id").asString()).isEqualTo(correlationId.toString())
        assertThat(json.required("trace_id").asString()).isEqualTo("0123456789abcdef")
        assertThat(json.has("eventId")).isFalse()
        assertThat(envelope.subject()).isEqualTo("cookie.events.account.activated.v1")
    }
}
