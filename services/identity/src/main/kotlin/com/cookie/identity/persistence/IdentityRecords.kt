package com.cookie.identity.persistence

import java.time.Instant
import java.util.UUID

data class CredentialRecord(
    val accountId: UUID,
    val accountStatus: String,
    val email: String,
    val passwordHash: String,
    val emailVerifiedAt: Instant?,
    val failedLoginCount: Int,
    val lockedUntil: Instant?,
    val createdAt: Instant,
)

data class ActionTokenRecord(
    val id: UUID,
    val accountId: UUID,
    val tokenHash: String,
    val expiresAt: Instant,
    val consumedAt: Instant?,
    val revokedAt: Instant?,
    val createdAt: Instant,
)

data class RefreshSessionRecord(
    val id: UUID,
    val accountId: UUID,
    val familyId: UUID,
    val tokenHash: String,
    val status: String,
    val deviceId: String?,
    val familyExpiresAt: Instant,
)

data class RateLimitResult(val attemptCount: Int, val expiresAt: Instant)

data class OutboxRecord(
    val eventId: UUID,
    val eventType: String,
    val eventVersion: Int,
    val aggregateType: String,
    val aggregateId: String,
    val payload: String,
    val occurredAt: Instant,
    val attemptCount: Int,
)
