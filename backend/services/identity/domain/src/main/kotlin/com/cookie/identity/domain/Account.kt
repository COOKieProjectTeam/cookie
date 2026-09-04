package com.cookie.identity.domain

import java.time.Instant
import java.util.UUID
import kotlin.math.min

enum class PasswordAuthenticationResult {
    AUTHENTICATED,
    REJECTED,
    REJECTED_WITH_RECORDED_FAILURE,
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
) {
    init {
        require(passwordHash.isNotBlank()) { "Password hash must not be blank" }
        require(failedLoginCount >= 0) { "Failed login count must not be negative" }
        require(lockedUntil == null || !lockedUntil.isBefore(createdAt)) {
            "Account lock cannot precede registration"
        }
    }

    var failedLoginCount: Int = failedLoginCount
        private set
    var lockedUntil: Instant? = lockedUntil
        private set

    fun authenticatePassword(passwordMatches: Boolean, now: Instant): PasswordAuthenticationResult {
        check(!now.isBefore(createdAt)) { "Login attempt cannot precede registration" }
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
        ): Account = Account(
            id,
            email,
            passwordHash,
            createdAt,
            failedLoginCount,
            lockedUntil,
        )

        private const val LOCKOUT_THRESHOLD = 5
        private const val MAX_LOCKOUT_EXPONENT = 10
        private const val INITIAL_LOCKOUT_SECONDS = 30L
        private const val MAX_LOCKOUT_SECONDS = 900L
    }
}
