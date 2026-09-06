package com.cookie.identity.domain

import java.time.Instant
import java.util.UUID
import kotlin.math.min

enum class PasswordAuthenticationResult {
    AUTHENTICATED,
    REJECTED,
    REJECTED_WITH_RECORDED_FAILURE,
}

enum class AccountStatus {
    ACTIVE,
    DELETION_PENDING,
}

data class AccountActivated(
    val accountId: UUID,
    val registeredAt: Instant,
    val activatedAt: Instant,
)

data class AccountRegistration(
    val account: Account,
    val event: AccountActivated,
)

/** A registered account. Pending signup state belongs to RegistrationAttempt. */
class Account private constructor(
    val id: UUID,
    val email: CanonicalEmail,
    val passwordHash: String,
    val createdAt: Instant,
    failedLoginCount: Int,
    lockedUntil: Instant?,
    status: AccountStatus,
) {
    init {
        require(passwordHash.isNotBlank()) { "Password hash must not be blank" }
        require(failedLoginCount >= 0) { "Failed login count must not be negative" }
        require(lockedUntil == null || !lockedUntil.isBefore(createdAt)) {
            "Account lock cannot precede registration"
        }
        require(stateIsConsistent(status, lockedUntil)) {
            "Account lifecycle state is inconsistent"
        }
    }

    var failedLoginCount: Int = failedLoginCount
        private set
    var lockedUntil: Instant? = lockedUntil
        private set
    var status: AccountStatus = status
        private set

    fun authenticatePassword(passwordMatches: Boolean, now: Instant): PasswordAuthenticationResult {
        check(!now.isBefore(createdAt)) { "Login attempt cannot precede registration" }
        // A deletion request is irreversible. This explicit state guard keeps a
        // pending account closed even after the compatibility lock sentinel is
        // eventually reached and must never mutate the lockout counters.
        if (status != AccountStatus.ACTIVE) return PasswordAuthenticationResult.REJECTED
        // A request made during an already active lock is observational only.
        // Neither a correct nor an incorrect password may keep extending a
        // victim's lockout window.
        if (lockedUntil?.isAfter(now) == true) return PasswordAuthenticationResult.REJECTED
        if (!passwordMatches) {
            recordFailedPassword(now)
            return PasswordAuthenticationResult.REJECTED_WITH_RECORDED_FAILURE
        }
        failedLoginCount = 0
        lockedUntil = null
        return PasswordAuthenticationResult.AUTHENTICATED
    }

    /**
     * Irreversibly closes the account for authentication while distributed
     * data deletion is pending. The far-future lock is deliberately persisted
     * alongside the new status so an older binary, which only understands
     * lockout state, also rejects logins during an expand/contract rollout.
     *
     * @return `true` only for the first ACTIVE -> DELETION_PENDING transition.
     */
    fun requestDeletion(now: Instant): Boolean {
        check(!now.isBefore(createdAt)) { "Account deletion request cannot precede registration" }
        when (status) {
            AccountStatus.DELETION_PENDING -> return false
            AccountStatus.ACTIVE -> Unit
        }
        check(now.isBefore(DELETION_LOCKED_UNTIL)) { "Account deletion request exceeds supported time range" }

        status = AccountStatus.DELETION_PENDING
        lockedUntil = DELETION_LOCKED_UNTIL
        return true
    }

    private fun recordFailedPassword(now: Instant) {
        failedLoginCount += 1
        if (failedLoginCount >= LOCKOUT_THRESHOLD) {
            val exponent = min(failedLoginCount - LOCKOUT_THRESHOLD, MAX_LOCKOUT_EXPONENT)
            val seconds = min(MAX_LOCKOUT_SECONDS, INITIAL_LOCKOUT_SECONDS * (1L shl exponent))
            lockedUntil = now.plusSeconds(seconds)
        }
    }

    override fun toString(): String = "Account(id=$id)"

    companion object {
        fun register(
            id: UUID,
            email: CanonicalEmail,
            passwordHash: String,
            now: Instant,
        ): AccountRegistration {
            val account = Account(
                id = id,
                email = email,
                passwordHash = passwordHash,
                createdAt = now,
                failedLoginCount = 0,
                lockedUntil = null,
                status = AccountStatus.ACTIVE,
            )
            return AccountRegistration(account, AccountActivated(id, now, now))
        }

        fun reconstitute(
            id: UUID,
            email: CanonicalEmail,
            passwordHash: String,
            createdAt: Instant,
            failedLoginCount: Int,
            lockedUntil: Instant?,
            status: AccountStatus = AccountStatus.ACTIVE,
        ): Account = Account(
            id,
            email,
            passwordHash,
            createdAt,
            failedLoginCount,
            lockedUntil,
            status,
        )

        /** PostgreSQL-safe sentinel used to close pending accounts to old binaries. */
        val DELETION_LOCKED_UNTIL: Instant = Instant.parse("9999-12-31T23:59:59.999999Z")

        private fun stateIsConsistent(
            status: AccountStatus,
            lockedUntil: Instant?,
        ): Boolean = when (status) {
            AccountStatus.ACTIVE -> true
            AccountStatus.DELETION_PENDING ->
                lockedUntil != null && !lockedUntil.isBefore(DELETION_LOCKED_UNTIL)
        }

        private const val LOCKOUT_THRESHOLD = 5
        private const val MAX_LOCKOUT_EXPONENT = 10
        private const val INITIAL_LOCKOUT_SECONDS = 30L
        private const val MAX_LOCKOUT_SECONDS = 900L
    }
}
