package com.cookie.identity.domain

import java.time.Instant
import java.util.UUID

enum class RefreshSessionStatus {
    ACTIVE,
    ROTATED,
    REVOKED,
}

enum class RefreshRevokeReason {
    ROTATED,
    REPLAY_DETECTED,
    LOGOUT,
}

enum class RotationDecision {
    ROTATE,
    REPLAY,
    INVALID,
}

class RefreshSession private constructor(
    val id: UUID,
    val accountId: UUID,
    val familyId: UUID,
    val verifierHash: String,
    val deviceId: String?,
    val familyExpiresAt: Instant,
    val createdAt: Instant,
    status: RefreshSessionStatus,
    replacedBySessionId: UUID?,
    lastUsedAt: Instant?,
    revokedAt: Instant?,
    revokeReason: RefreshRevokeReason?,
    reuseDetectedAt: Instant?,
) {
    init {
        require(VERIFIER.matches(verifierHash)) { "Refresh verifier must be a SHA-256 hex value" }
        require(familyExpiresAt.isAfter(createdAt)) { "Refresh family must expire after session creation" }
        require(replacedBySessionId == null || replacedBySessionId != id) { "Refresh session cannot replace itself" }
        require(stateIsConsistent(status, replacedBySessionId, lastUsedAt, revokedAt, revokeReason, reuseDetectedAt)) {
            "Refresh session state is inconsistent"
        }
    }

    var status: RefreshSessionStatus = status
        private set
    var replacedBySessionId: UUID? = replacedBySessionId
        private set
    var lastUsedAt: Instant? = lastUsedAt
        private set
    var revokedAt: Instant? = revokedAt
        private set
    var revokeReason: RefreshRevokeReason? = revokeReason
        private set
    var reuseDetectedAt: Instant? = reuseDetectedAt
        private set

    fun rotationDecision(verifierMatches: Boolean, now: Instant): RotationDecision = when {
        !verifierMatches -> RotationDecision.INVALID
        status == RefreshSessionStatus.ROTATED -> RotationDecision.REPLAY
        status != RefreshSessionStatus.ACTIVE || !familyExpiresAt.isAfter(now) -> RotationDecision.INVALID
        else -> RotationDecision.ROTATE
    }

    fun rotate(replacementId: UUID, now: Instant) {
        check(status == RefreshSessionStatus.ACTIVE) { "Only an active refresh session can rotate" }
        check(replacementId != id) { "Refresh session cannot replace itself" }
        check(!now.isBefore(createdAt) && familyExpiresAt.isAfter(now)) { "Refresh session cannot rotate at this time" }
        status = RefreshSessionStatus.ROTATED
        replacedBySessionId = replacementId
        lastUsedAt = now
        revokedAt = now
        revokeReason = RefreshRevokeReason.ROTATED
    }

    fun revoke(reason: RefreshRevokeReason, now: Instant) {
        require(reason != RefreshRevokeReason.ROTATED) { "Rotation must use the rotate transition" }
        check(!now.isBefore(createdAt)) { "Refresh session cannot be revoked before creation" }
        if (status == RefreshSessionStatus.REVOKED && reason != RefreshRevokeReason.REPLAY_DETECTED) return
        if (reason == RefreshRevokeReason.REPLAY_DETECTED) {
            check(revokedAt == null || !now.isBefore(revokedAt)) { "Replay detection cannot precede revocation" }
        }
        status = RefreshSessionStatus.REVOKED
        revokedAt = revokedAt ?: now
        revokeReason = reason
        if (reason == RefreshRevokeReason.REPLAY_DETECTED) reuseDetectedAt = now
    }

    override fun toString(): String = "RefreshSession(id=$id,familyId=$familyId,status=$status)"

    companion object {
        private val VERIFIER = Regex("[0-9a-f]{64}")

        private fun stateIsConsistent(
            status: RefreshSessionStatus,
            replacement: UUID?,
            lastUsedAt: Instant?,
            revokedAt: Instant?,
            reason: RefreshRevokeReason?,
            reuseDetectedAt: Instant?,
        ): Boolean = when (status) {
            RefreshSessionStatus.ACTIVE ->
                replacement == null && lastUsedAt == null && revokedAt == null && reason == null && reuseDetectedAt == null
            RefreshSessionStatus.ROTATED ->
                replacement != null && lastUsedAt != null && revokedAt != null &&
                    reason == RefreshRevokeReason.ROTATED && reuseDetectedAt == null
            RefreshSessionStatus.REVOKED ->
                revokedAt != null && reason in setOf(RefreshRevokeReason.REPLAY_DETECTED, RefreshRevokeReason.LOGOUT) &&
                    ((reason == RefreshRevokeReason.REPLAY_DETECTED) == (reuseDetectedAt != null))
        }

        fun active(
            id: UUID,
            accountId: UUID,
            familyId: UUID,
            verifierHash: String,
            deviceId: String?,
            familyExpiresAt: Instant,
            now: Instant,
        ): RefreshSession = RefreshSession(
            id,
            accountId,
            familyId,
            verifierHash,
            deviceId,
            familyExpiresAt,
            now,
            RefreshSessionStatus.ACTIVE,
            replacedBySessionId = null,
            lastUsedAt = null,
            revokedAt = null,
            revokeReason = null,
            reuseDetectedAt = null,
        )

        @Suppress("LongParameterList")
        fun reconstitute(
            id: UUID,
            accountId: UUID,
            familyId: UUID,
            verifierHash: String,
            status: RefreshSessionStatus,
            deviceId: String?,
            familyExpiresAt: Instant,
            createdAt: Instant,
            replacedBySessionId: UUID?,
            lastUsedAt: Instant?,
            revokedAt: Instant?,
            revokeReason: RefreshRevokeReason?,
            reuseDetectedAt: Instant?,
        ): RefreshSession = RefreshSession(
            id,
            accountId,
            familyId,
            verifierHash,
            deviceId,
            familyExpiresAt,
            createdAt,
            status,
            replacedBySessionId,
            lastUsedAt,
            revokedAt,
            revokeReason,
            reuseDetectedAt,
        )
    }
}
