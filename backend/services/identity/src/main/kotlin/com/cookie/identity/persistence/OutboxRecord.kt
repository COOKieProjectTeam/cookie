package com.cookie.identity.persistence

import java.time.Instant
import java.util.UUID

data class OutboxRecord(
    val eventId: UUID,
    val claimId: UUID,
    val eventType: String,
    val eventVersion: Int,
    val aggregateType: String,
    val aggregateId: String,
    val payload: String,
    val occurredAt: Instant,
    val attemptCount: Int,
    val correlationId: UUID?,
    val causationId: UUID?,
    val traceId: String?,
)
