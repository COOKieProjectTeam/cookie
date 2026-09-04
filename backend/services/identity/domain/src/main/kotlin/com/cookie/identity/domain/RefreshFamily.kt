package com.cookie.identity.domain

import java.time.Instant
import java.util.UUID

enum class RefreshFamilyStatus {
    ACTIVE,
    REVOKED,
}

enum class RefreshFamilyRevokeReason {
    TOKEN_REUSE_DETECTED,
    LOGOUT,
}

enum class RefreshDecision {
    ROTATE,
    RETRY,
    STALE_RETRY,
    TOKEN_REUSE,
    INVALID,
}

/**
 * One logical device session. Refresh credentials are single-use children of
 * this aggregate and may only be changed through the family.
 *
 * Repositories hydrate the current credential and the credential presented by
 * the command. Older credentials are immutable audit evidence and do not need
 * to be loaded as a growing collection on every refresh.
 */
class RefreshFamily private constructor(
    val id: UUID,
    val accountId: UUID,
    val deviceId: DeviceId?,
    val expiresAt: Instant,
    val createdAt: Instant,
    status: RefreshFamilyStatus,
    lastActivityAt: Instant,
    revokedAt: Instant?,
    revokeReason: RefreshFamilyRevokeReason?,
    reuseDetectedAt: Instant?,
    credentials: Collection<RefreshCredential>,
) {
    private val loadedCredentials = credentials.associateByTo(linkedMapOf()) { it.id }

    init {
        require(expiresAt.isAfter(createdAt)) { "Refresh family must expire after creation" }
        require(!lastActivityAt.isBefore(createdAt)) { "Refresh activity cannot precede creation" }
        require(lastActivityAt.isBefore(expiresAt)) { "Refresh activity must precede family expiry" }
        require(loadedCredentials.size == credentials.size) { "Refresh credentials must have unique ids" }
        require(loadedCredentials.isNotEmpty()) { "Refresh family must contain its current credential" }
        require(loadedCredentials.values.all { it.familyId == id }) {
            "Refresh credential belongs to another family"
        }
        require(loadedCredentials.values.all { !it.createdAt.isBefore(createdAt) && it.createdAt.isBefore(expiresAt) }) {
            "Refresh credential lifetime is outside its family lifetime"
        }
        require(loadedCredentials.values.count { !it.isRedeemed } == 1) {
            "Refresh family must contain exactly one current credential"
        }
        require(revokedAt == null || !revokedAt.isBefore(createdAt)) { "Revocation cannot precede creation" }
        require(revokedAt == null || !revokedAt.isBefore(lastActivityAt)) {
            "Revocation cannot precede the last family activity"
        }
        require(reuseDetectedAt == null || reuseDetectedAt == revokedAt) {
            "Token reuse detection and revocation must be one atomic transition"
        }
        require(stateIsConsistent(status, revokedAt, revokeReason, reuseDetectedAt)) {
            "Refresh family state is inconsistent"
        }
    }

    var status: RefreshFamilyStatus = status
        private set
    var lastActivityAt: Instant = lastActivityAt
        private set
    var revokedAt: Instant? = revokedAt
        private set
    var revokeReason: RefreshFamilyRevokeReason? = revokeReason
        private set
    var reuseDetectedAt: Instant? = reuseDetectedAt
        private set

    val currentCredentialId: UUID
        get() = currentCredential().id

    fun verifierHashFor(credentialId: UUID): VerifierHash? = loadedCredentials[credentialId]?.verifierHash

    fun refreshDecision(
        credentialId: UUID,
        verifierMatches: Boolean,
        idempotencyKey: UUID,
        now: Instant,
    ): RefreshDecision {
        val presented = loadedCredentials[credentialId] ?: return RefreshDecision.INVALID
        if (!verifierMatches || now.isBefore(presented.createdAt) || now.isBefore(lastActivityAt)) {
            return RefreshDecision.INVALID
        }
        if (status != RefreshFamilyStatus.ACTIVE || !expiresAt.isAfter(now)) return RefreshDecision.INVALID
        if (credentialId == currentCredentialId) return RefreshDecision.ROTATE
        if (!presented.wasRedeemedWith(idempotencyKey)) return RefreshDecision.TOKEN_REUSE
        return if (presented.canRetryWith(currentCredentialId, now)) RefreshDecision.RETRY else RefreshDecision.STALE_RETRY
    }

    fun rotateCurrentTo(
        presentedCredentialId: UUID,
        replacementCredentialId: UUID,
        replacementVerifierHash: VerifierHash,
        idempotencyKey: UUID,
        retryUntil: Instant,
        now: Instant,
    ) {
        check(status == RefreshFamilyStatus.ACTIVE) { "Only an active refresh family can rotate credentials" }
        check(expiresAt.isAfter(now)) { "Refresh family cannot rotate at or after expiry" }
        check(!now.isBefore(lastActivityAt)) { "Refresh rotation cannot precede the last family activity" }
        val current = currentCredential()
        check(current.id == presentedCredentialId) { "Only the current refresh credential can rotate" }
        check(replacementCredentialId !in loadedCredentials) { "Replacement refresh credential id already exists" }
        current.redeemWith(replacementCredentialId, idempotencyKey, retryUntil, now)
        loadedCredentials[replacementCredentialId] = RefreshCredential.issue(
            id = replacementCredentialId,
            familyId = id,
            verifierHash = replacementVerifierHash,
            now = now,
        )
        lastActivityAt = now
    }

    fun revoke(reason: RefreshFamilyRevokeReason, now: Instant) {
        if (status == RefreshFamilyStatus.REVOKED) return
        check(!now.isBefore(lastActivityAt)) { "Refresh family revocation cannot precede its last activity" }
        status = RefreshFamilyStatus.REVOKED
        revokedAt = now
        revokeReason = reason
        if (reason == RefreshFamilyRevokeReason.TOKEN_REUSE_DETECTED) reuseDetectedAt = now
    }

    fun credentialSnapshots(): List<RefreshCredential> = loadedCredentials.values.toList()

    override fun toString(): String = "RefreshFamily(id=$id,accountId=$accountId,status=$status)"

    private fun currentCredential(): RefreshCredential = loadedCredentials.values.single { !it.isRedeemed }

    companion object {
        fun start(
            id: UUID,
            accountId: UUID,
            firstCredentialId: UUID,
            firstVerifierHash: VerifierHash,
            deviceId: DeviceId?,
            expiresAt: Instant,
            now: Instant,
        ): RefreshFamily = RefreshFamily(
            id = id,
            accountId = accountId,
            deviceId = deviceId,
            expiresAt = expiresAt,
            createdAt = now,
            status = RefreshFamilyStatus.ACTIVE,
            lastActivityAt = now,
            revokedAt = null,
            revokeReason = null,
            reuseDetectedAt = null,
            credentials = listOf(
                RefreshCredential.issue(firstCredentialId, id, firstVerifierHash, now),
            ),
        )

        @Suppress("LongParameterList")
        fun reconstitute(
            id: UUID,
            accountId: UUID,
            deviceId: DeviceId?,
            expiresAt: Instant,
            createdAt: Instant,
            status: RefreshFamilyStatus,
            lastActivityAt: Instant,
            revokedAt: Instant?,
            revokeReason: RefreshFamilyRevokeReason?,
            reuseDetectedAt: Instant?,
            credentials: Collection<RefreshCredential>,
        ): RefreshFamily = RefreshFamily(
            id,
            accountId,
            deviceId,
            expiresAt,
            createdAt,
            status,
            lastActivityAt,
            revokedAt,
            revokeReason,
            reuseDetectedAt,
            credentials,
        )

        private fun stateIsConsistent(
            status: RefreshFamilyStatus,
            revokedAt: Instant?,
            reason: RefreshFamilyRevokeReason?,
            reuseDetectedAt: Instant?,
        ): Boolean = when (status) {
            RefreshFamilyStatus.ACTIVE -> revokedAt == null && reason == null && reuseDetectedAt == null
            RefreshFamilyStatus.REVOKED ->
                revokedAt != null && reason != null &&
                    (
                        (reason == RefreshFamilyRevokeReason.TOKEN_REUSE_DETECTED && reuseDetectedAt == revokedAt) ||
                            (reason == RefreshFamilyRevokeReason.LOGOUT && reuseDetectedAt == null)
                    )
        }
    }
}

class RefreshCredential private constructor(
    val id: UUID,
    val familyId: UUID,
    val verifierHash: VerifierHash,
    val createdAt: Instant,
    redeemedAt: Instant?,
    replacedByCredentialId: UUID?,
    rotationIdempotencyKey: UUID?,
    retryUntil: Instant?,
) {
    init {
        require(replacedByCredentialId == null || replacedByCredentialId != id) {
            "Refresh credential cannot replace itself"
        }
        require(redeemedAt == null || !redeemedAt.isBefore(createdAt)) {
            "Refresh credential redemption cannot precede creation"
        }
        require(retryUntil == null || redeemedAt == null || retryUntil.isAfter(redeemedAt)) {
            "Refresh retry window must end after credential redemption"
        }
        require(
            listOf(redeemedAt, replacedByCredentialId, rotationIdempotencyKey, retryUntil)
                .all { it == null } ||
                listOf(redeemedAt, replacedByCredentialId, rotationIdempotencyKey, retryUntil).all { it != null },
        ) { "Refresh credential rotation metadata is inconsistent" }
    }

    var redeemedAt: Instant? = redeemedAt
        private set
    var replacedByCredentialId: UUID? = replacedByCredentialId
        private set
    var rotationIdempotencyKey: UUID? = rotationIdempotencyKey
        private set
    var retryUntil: Instant? = retryUntil
        private set

    val isRedeemed: Boolean
        get() = redeemedAt != null

    internal fun wasRedeemedWith(idempotencyKey: UUID): Boolean = rotationIdempotencyKey == idempotencyKey

    internal fun canRetryWith(currentCredentialId: UUID, now: Instant): Boolean =
        replacedByCredentialId == currentCredentialId &&
            retryUntil?.isAfter(now) == true

    internal fun redeemWith(
        replacementCredentialId: UUID,
        idempotencyKey: UUID,
        retryUntil: Instant,
        now: Instant,
    ) {
        check(!isRedeemed) { "Refresh credential has already been redeemed" }
        check(!now.isBefore(createdAt)) { "Refresh credential redemption cannot precede creation" }
        check(replacementCredentialId != id) { "Refresh credential cannot replace itself" }
        check(retryUntil.isAfter(now)) { "Refresh retry window must be positive" }
        redeemedAt = now
        replacedByCredentialId = replacementCredentialId
        rotationIdempotencyKey = idempotencyKey
        this.retryUntil = retryUntil
    }

    override fun toString(): String = "RefreshCredential(id=$id,familyId=$familyId,redeemed=$isRedeemed)"

    companion object {
        internal fun issue(
            id: UUID,
            familyId: UUID,
            verifierHash: VerifierHash,
            now: Instant,
        ): RefreshCredential = RefreshCredential(
            id,
            familyId,
            verifierHash,
            now,
            redeemedAt = null,
            replacedByCredentialId = null,
            rotationIdempotencyKey = null,
            retryUntil = null,
        )

        @Suppress("LongParameterList")
        fun reconstitute(
            id: UUID,
            familyId: UUID,
            verifierHash: VerifierHash,
            createdAt: Instant,
            redeemedAt: Instant?,
            replacedByCredentialId: UUID?,
            rotationIdempotencyKey: UUID?,
            retryUntil: Instant?,
        ): RefreshCredential = RefreshCredential(
            id,
            familyId,
            verifierHash,
            createdAt,
            redeemedAt,
            replacedByCredentialId,
            rotationIdempotencyKey,
            retryUntil,
        )
    }
}
