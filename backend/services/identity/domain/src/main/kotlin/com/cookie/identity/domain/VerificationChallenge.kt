package com.cookie.identity.domain

import java.time.Instant
import java.util.UUID

class VerificationChallenge private constructor(
    val id: UUID,
    val accountId: UUID,
    val verifierHash: String,
    val expiresAt: Instant,
    val createdAt: Instant,
    consumedAt: Instant?,
    revokedAt: Instant?,
) {
    init {
        require(VERIFIER.matches(verifierHash)) { "Verification challenge verifier must be a SHA-256 hex value" }
        require(expiresAt.isAfter(createdAt)) { "Verification challenge must expire after creation" }
        require(consumedAt == null || revokedAt == null) { "Verification challenge cannot be consumed and revoked" }
        require(consumedAt == null || !consumedAt.isBefore(createdAt)) { "Challenge consumption cannot precede creation" }
        require(revokedAt == null || !revokedAt.isBefore(createdAt)) { "Challenge revocation cannot precede creation" }
    }

    var consumedAt: Instant? = consumedAt
        private set
    var revokedAt: Instant? = revokedAt
        private set

    fun isUsable(verifierMatches: Boolean, now: Instant): Boolean =
        verifierMatches && consumedAt == null && revokedAt == null && expiresAt.isAfter(now)

    fun consume(now: Instant) {
        check(!now.isBefore(createdAt) && consumedAt == null && revokedAt == null && expiresAt.isAfter(now)) {
            "Verification challenge cannot be consumed"
        }
        consumedAt = now
    }

    fun revoke(now: Instant) {
        check(!now.isBefore(createdAt)) { "Verification challenge cannot be revoked before creation" }
        if (consumedAt == null && revokedAt == null) revokedAt = now
    }

    companion object {
        private val VERIFIER = Regex("[0-9a-f]{64}")

        fun create(
            id: UUID,
            accountId: UUID,
            verifierHash: String,
            expiresAt: Instant,
            now: Instant,
        ): VerificationChallenge = VerificationChallenge(
            id,
            accountId,
            verifierHash,
            expiresAt,
            now,
            consumedAt = null,
            revokedAt = null,
        )

        fun reconstitute(
            id: UUID,
            accountId: UUID,
            verifierHash: String,
            expiresAt: Instant,
            createdAt: Instant,
            consumedAt: Instant?,
            revokedAt: Instant?,
        ): VerificationChallenge = VerificationChallenge(
            id,
            accountId,
            verifierHash,
            expiresAt,
            createdAt,
            consumedAt,
            revokedAt,
        )
    }
}
