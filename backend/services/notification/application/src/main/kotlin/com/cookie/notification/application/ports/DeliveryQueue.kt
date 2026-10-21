package com.cookie.notification.application.ports

import java.time.Instant
import java.util.UUID

/** Called inside the same PostgreSQL transaction as inbox/projection/schedule changes. */
interface DeliveryQueue {
    fun enqueueEmail(eventId: UUID, registrationAttemptId: UUID, jwe: String, expiresAt: Instant, occurredAt: Instant)
    fun enqueuePush(occurrenceId: UUID, userId: UUID, deviceId: UUID, kind: String, locale: String, expiresAt: Instant)
    fun cancelUser(userId: UUID)
    fun cancelPendingPush(userId: UUID)
}
