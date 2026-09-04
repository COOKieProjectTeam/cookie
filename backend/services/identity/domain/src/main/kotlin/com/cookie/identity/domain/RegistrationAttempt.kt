package com.cookie.identity.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class RegistrationRequestDecision {
    EXACT_RETRY,
    CONFLICT,
    EXPIRED,
    ABANDONED,
    INVALID,
}

enum class RegistrationTokenDecision {
    COMPLETE,
    EXACT_RETRY,
    INVALID,
}

/**
 * One logical registration attempt, proven by a client-held proof and one of
 * its email-verification tokens. An Account does not exist until this
 * aggregate completes.
 *
 * Resends add child tokens to this root instead of copying pending credentials
 * into independent records. Completing through one child invalidates every
 * sibling and scrubs the pending password and delivery locale.
 */
class RegistrationAttempt private constructor(
    val id: UUID,
    val email: CanonicalEmail,
    val registrationProofHash: VerifierHash,
    val requestFingerprint: VerifierHash,
    locale: LocaleTag?,
    pendingPasswordHash: String?,
    val expiresAt: Instant,
    val createdAt: Instant,
    completedAt: Instant?,
    activatedAccountId: UUID?,
    abandonedAt: Instant?,
    verificationTokens: Collection<RegistrationVerificationToken>,
) {
    private val loadedVerificationTokens = verificationTokens.associateByTo(linkedMapOf()) { it.id }

    init {
        require(expiresAt.isAfter(createdAt)) { "Registration attempt must expire after creation" }
        require(pendingPasswordHash == null || pendingPasswordHash.isNotBlank()) {
            "Pending password hash must not be blank"
        }
        require(loadedVerificationTokens.size == verificationTokens.size) {
            "Registration verification tokens must have unique ids"
        }
        require(loadedVerificationTokens.isNotEmpty()) {
            "Registration attempt must contain a verification token"
        }
        require(loadedVerificationTokens.values.all { it.attemptId == id }) {
            "Registration verification token belongs to another attempt"
        }
        require(
            loadedVerificationTokens.values.all {
                !it.issuedAt.isBefore(createdAt) &&
                    it.issuedAt.isBefore(expiresAt) &&
                    !it.expiresAt.isAfter(expiresAt)
            },
        ) { "Registration verification token lifetime is outside its attempt lifetime" }
        require(completedAt == null || !completedAt.isBefore(createdAt)) {
            "Registration completion cannot precede creation"
        }
        require(completedAt == null || completedAt.isBefore(expiresAt)) {
            "Registration completion must precede attempt expiry"
        }
        require(abandonedAt == null || !abandonedAt.isBefore(createdAt)) {
            "Registration abandonment cannot precede creation"
        }
        require(completedAt == null || abandonedAt == null) {
            "Registration attempt cannot be both completed and abandoned"
        }
        require((completedAt == null && abandonedAt == null) || locale == null) {
            "Terminal registration locale must be scrubbed"
        }
        require(stateIsConsistent(completedAt, activatedAccountId, abandonedAt, pendingPasswordHash)) {
            "Registration terminal state is inconsistent"
        }
        require(
            if (completedAt == null) {
                loadedVerificationTokens.values.none { it.isRedeemed }
            } else {
                loadedVerificationTokens.values.count { it.redeemedAt == completedAt } == 1 &&
                    loadedVerificationTokens.values.count { it.isRedeemed } == 1
            },
        ) { "Registration token redemption state is inconsistent" }
    }

    var pendingPasswordHash: String? = pendingPasswordHash
        private set
    var locale: LocaleTag? = locale
        private set
    var completedAt: Instant? = completedAt
        private set
    var activatedAccountId: UUID? = activatedAccountId
        private set
    var abandonedAt: Instant? = abandonedAt
        private set

    val latestVerificationTokenIssuedAt: Instant
        get() = latestVerificationToken().issuedAt

    val isCompleted: Boolean
        get() = completedAt != null

    val isAbandoned: Boolean
        get() = abandonedAt != null

    /** Classifies a repeat of the registration command without changing state. */
    fun requestDecision(candidateRequestFingerprint: VerifierHash, now: Instant): RegistrationRequestDecision {
        if (now.isBefore(createdAt)) return RegistrationRequestDecision.INVALID
        if (candidateRequestFingerprint != requestFingerprint) return RegistrationRequestDecision.CONFLICT
        if (isCompleted) return RegistrationRequestDecision.EXACT_RETRY
        if (isAbandoned) return RegistrationRequestDecision.ABANDONED
        return if (expiresAt.isAfter(now)) {
            RegistrationRequestDecision.EXACT_RETRY
        } else {
            RegistrationRequestDecision.EXPIRED
        }
    }

    /** Classifies a presented token and both proofs without changing state. */
    fun tokenDecision(
        tokenId: UUID,
        tokenVerifierMatches: Boolean,
        registrationProofMatches: Boolean,
        now: Instant,
    ): RegistrationTokenDecision {
        val token = loadedVerificationTokens[tokenId] ?: return RegistrationTokenDecision.INVALID
        if (!tokenVerifierMatches || !registrationProofMatches || now.isBefore(createdAt)) {
            return RegistrationTokenDecision.INVALID
        }
        if (isCompleted) {
            return if (token.redeemedAt == completedAt) {
                RegistrationTokenDecision.EXACT_RETRY
            } else {
                RegistrationTokenDecision.INVALID
            }
        }
        if (isAbandoned) return RegistrationTokenDecision.INVALID
        if (!expiresAt.isAfter(now) || !token.isUsableAt(now)) return RegistrationTokenDecision.INVALID
        return RegistrationTokenDecision.COMPLETE
    }

    fun verificationTokenVerifierHash(tokenId: UUID): VerifierHash? =
        loadedVerificationTokens[tokenId]?.verifierHash

    fun canIssueVerificationToken(now: Instant, cooldown: Duration): Boolean {
        require(!cooldown.isNegative) { "Verification resend cooldown must not be negative" }
        if (isCompleted || isAbandoned || now.isBefore(createdAt) || !expiresAt.isAfter(now)) return false
        return !now.isBefore(latestVerificationTokenIssuedAt.plus(cooldown))
    }

    fun issueVerificationToken(
        id: UUID,
        verifierHash: VerifierHash,
        expiresAt: Instant,
        cooldown: Duration,
        now: Instant,
    ): RegistrationVerificationToken {
        check(canIssueVerificationToken(now, cooldown)) {
            "Registration verification token cannot be issued at this time"
        }
        check(id !in loadedVerificationTokens) { "Registration verification token id already exists" }
        check(expiresAt.isAfter(now) && !expiresAt.isAfter(this.expiresAt)) {
            "Registration verification token lifetime is outside its attempt lifetime"
        }
        return RegistrationVerificationToken.issue(id, this.id, verifierHash, expiresAt, now).also {
            loadedVerificationTokens[id] = it
        }
    }

    /**
     * Completes this attempt after application-layer hash comparison. Requiring
     * the comparison results here prevents callers from bypassing either proof.
     */
    fun complete(
        tokenId: UUID,
        tokenVerifierMatches: Boolean,
        registrationProofMatches: Boolean,
        accountId: UUID,
        now: Instant,
    ) {
        check(
            tokenDecision(tokenId, tokenVerifierMatches, registrationProofMatches, now) ==
                RegistrationTokenDecision.COMPLETE,
        ) { "Registration attempt cannot be completed" }
        loadedVerificationTokens.getValue(tokenId).redeem(now)
        completedAt = now
        activatedAccountId = accountId
        pendingPasswordHash = null
        locale = null
    }

    /** Terminates a losing or expired attempt and scrubs its retained secrets. */
    fun abandon(now: Instant) {
        if (isAbandoned) return
        check(!isCompleted) { "Completed registration attempt cannot be abandoned" }
        check(!now.isBefore(createdAt)) { "Registration abandonment cannot precede creation" }
        abandonedAt = now
        pendingPasswordHash = null
        locale = null
    }

    fun passwordHashForActivation(): String = checkNotNull(pendingPasswordHash) {
        "Terminal registration attempt has no pending password"
    }

    fun verificationTokenSnapshots(): List<RegistrationVerificationToken> =
        loadedVerificationTokens.values.toList()

    override fun toString(): String =
        "RegistrationAttempt(id=$id,email=[redacted],completed=$isCompleted,abandoned=$isAbandoned)"

    private fun latestVerificationToken(): RegistrationVerificationToken =
        loadedVerificationTokens.values.maxWith(
            compareBy<RegistrationVerificationToken> { it.issuedAt }.thenBy { it.id.toString() },
        )

    companion object {
        @Suppress("LongParameterList")
        fun start(
            id: UUID,
            email: CanonicalEmail,
            registrationProofHash: VerifierHash,
            requestFingerprint: VerifierHash,
            locale: LocaleTag?,
            pendingPasswordHash: String,
            expiresAt: Instant,
            firstTokenId: UUID,
            firstTokenVerifierHash: VerifierHash,
            firstTokenExpiresAt: Instant,
            now: Instant,
        ): RegistrationAttempt = RegistrationAttempt(
            id = id,
            email = email,
            registrationProofHash = registrationProofHash,
            requestFingerprint = requestFingerprint,
            locale = locale,
            pendingPasswordHash = pendingPasswordHash,
            expiresAt = expiresAt,
            createdAt = now,
            completedAt = null,
            activatedAccountId = null,
            abandonedAt = null,
            verificationTokens = listOf(
                RegistrationVerificationToken.issue(
                    id = firstTokenId,
                    attemptId = id,
                    verifierHash = firstTokenVerifierHash,
                    expiresAt = firstTokenExpiresAt,
                    now = now,
                ),
            ),
        )

        @Suppress("LongParameterList")
        fun reconstitute(
            id: UUID,
            email: CanonicalEmail,
            registrationProofHash: VerifierHash,
            requestFingerprint: VerifierHash,
            locale: LocaleTag?,
            pendingPasswordHash: String?,
            expiresAt: Instant,
            createdAt: Instant,
            completedAt: Instant?,
            activatedAccountId: UUID?,
            abandonedAt: Instant?,
            verificationTokens: Collection<RegistrationVerificationToken>,
        ): RegistrationAttempt = RegistrationAttempt(
            id,
            email,
            registrationProofHash,
            requestFingerprint,
            locale,
            pendingPasswordHash,
            expiresAt,
            createdAt,
            completedAt,
            activatedAccountId,
            abandonedAt,
            verificationTokens,
        )

        private fun stateIsConsistent(
            completedAt: Instant?,
            activatedAccountId: UUID?,
            abandonedAt: Instant?,
            pendingPasswordHash: String?,
        ): Boolean {
            val active = completedAt == null && abandonedAt == null &&
                activatedAccountId == null && pendingPasswordHash != null
            val completed = completedAt != null && abandonedAt == null &&
                activatedAccountId != null && pendingPasswordHash == null
            val abandoned = completedAt == null && abandonedAt != null &&
                activatedAccountId == null && pendingPasswordHash == null
            return active || completed || abandoned
        }
    }
}

class RegistrationVerificationToken private constructor(
    val id: UUID,
    val attemptId: UUID,
    val verifierHash: VerifierHash,
    val issuedAt: Instant,
    val expiresAt: Instant,
    redeemedAt: Instant?,
) {
    init {
        require(expiresAt.isAfter(issuedAt)) { "Registration verification token must expire after issuance" }
        require(redeemedAt == null || !redeemedAt.isBefore(issuedAt)) {
            "Registration verification token redemption cannot precede issuance"
        }
        require(redeemedAt == null || redeemedAt.isBefore(expiresAt)) {
            "Registration verification token redemption must precede expiry"
        }
    }

    var redeemedAt: Instant? = redeemedAt
        private set

    val isRedeemed: Boolean
        get() = redeemedAt != null

    internal fun isUsableAt(now: Instant): Boolean =
        !isRedeemed && !now.isBefore(issuedAt) && expiresAt.isAfter(now)

    internal fun redeem(now: Instant) {
        check(isUsableAt(now)) { "Registration verification token cannot be redeemed" }
        redeemedAt = now
    }

    override fun toString(): String =
        "RegistrationVerificationToken(id=$id,attemptId=$attemptId,redeemed=$isRedeemed)"

    companion object {
        internal fun issue(
            id: UUID,
            attemptId: UUID,
            verifierHash: VerifierHash,
            expiresAt: Instant,
            now: Instant,
        ): RegistrationVerificationToken = RegistrationVerificationToken(
            id,
            attemptId,
            verifierHash,
            now,
            expiresAt,
            redeemedAt = null,
        )

        @Suppress("LongParameterList")
        fun reconstitute(
            id: UUID,
            attemptId: UUID,
            verifierHash: VerifierHash,
            issuedAt: Instant,
            expiresAt: Instant,
            redeemedAt: Instant?,
        ): RegistrationVerificationToken = RegistrationVerificationToken(
            id,
            attemptId,
            verifierHash,
            issuedAt,
            expiresAt,
            redeemedAt,
        )
    }
}
