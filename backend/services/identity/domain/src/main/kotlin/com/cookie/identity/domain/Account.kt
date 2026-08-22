package com.cookie.identity.domain

import java.time.Instant
import java.util.UUID
import kotlin.math.min

enum class AccountStatus {
    PENDING_VERIFICATION,
    ACTIVE,
}

data class AccountActivated(
    val accountId: UUID,
    val registeredAt: Instant,
    val activatedAt: Instant,
)

class Account private constructor(
    val id: UUID,
    val email: CanonicalEmail,
    val passwordHash: String,
    val createdAt: Instant,
    status: AccountStatus,
    activatedAt: Instant?,
    emailVerifiedAt: Instant?,
    failedLoginCount: Int,
    lockedUntil: Instant?,
) {
    init {
        require(passwordHash.isNotBlank()) { "Password hash must not be blank" }
        require(failedLoginCount >= 0) { "Failed login count must not be negative" }
        require(activatedAt == null || !activatedAt.isBefore(createdAt)) { "Activation cannot precede registration" }
        require(emailVerifiedAt == null || !emailVerifiedAt.isBefore(createdAt)) {
            "Email verification cannot precede registration"
        }
        require(
            (status == AccountStatus.PENDING_VERIFICATION && activatedAt == null && emailVerifiedAt == null) ||
                (status == AccountStatus.ACTIVE && activatedAt != null && emailVerifiedAt != null),
        ) { "Account status and activation timestamps are inconsistent" }
    }

    var status: AccountStatus = status
        private set
    var activatedAt: Instant? = activatedAt
        private set
    var emailVerifiedAt: Instant? = emailVerifiedAt
        private set
    var failedLoginCount: Int = failedLoginCount
        private set
    var lockedUntil: Instant? = lockedUntil
        private set

    fun activate(now: Instant): AccountActivated? {
        if (status != AccountStatus.PENDING_VERIFICATION) return null
        check(!now.isBefore(createdAt)) { "Activation cannot precede registration" }
        status = AccountStatus.ACTIVE
        activatedAt = now
        emailVerifiedAt = now
        return AccountActivated(id, createdAt, now)
    }

    fun isLocked(now: Instant): Boolean = lockedUntil?.isAfter(now) == true

    fun canAuthenticate(passwordMatches: Boolean, now: Instant): Boolean =
        status == AccountStatus.ACTIVE && !isLocked(now) && passwordMatches

    fun recordFailedPassword(now: Instant) {
        if (status != AccountStatus.ACTIVE) return
        check(!now.isBefore(createdAt)) { "Login attempt cannot precede registration" }
        failedLoginCount += 1
        if (failedLoginCount >= LOCKOUT_THRESHOLD) {
            val exponent = min(failedLoginCount - LOCKOUT_THRESHOLD, MAX_LOCKOUT_EXPONENT)
            val seconds = min(MAX_LOCKOUT_SECONDS, INITIAL_LOCKOUT_SECONDS * (1L shl exponent))
            lockedUntil = now.plusSeconds(seconds)
        }
    }

    fun recordSuccessfulLogin() {
        check(status == AccountStatus.ACTIVE) { "Only active accounts can authenticate" }
        failedLoginCount = 0
        lockedUntil = null
    }

    override fun toString(): String = "Account(id=$id,status=$status)"

    companion object {
        fun pending(
            id: UUID,
            email: CanonicalEmail,
            passwordHash: String,
            now: Instant,
        ): Account = Account(
            id = id,
            email = email,
            passwordHash = passwordHash,
            createdAt = now,
            status = AccountStatus.PENDING_VERIFICATION,
            activatedAt = null,
            emailVerifiedAt = null,
            failedLoginCount = 0,
            lockedUntil = null,
        )

        fun reconstitute(
            id: UUID,
            email: CanonicalEmail,
            passwordHash: String,
            createdAt: Instant,
            status: AccountStatus,
            activatedAt: Instant?,
            emailVerifiedAt: Instant?,
            failedLoginCount: Int,
            lockedUntil: Instant?,
        ): Account = Account(
            id,
            email,
            passwordHash,
            createdAt,
            status,
            activatedAt,
            emailVerifiedAt,
            failedLoginCount,
            lockedUntil,
        )

        private const val LOCKOUT_THRESHOLD = 5
        private const val MAX_LOCKOUT_EXPONENT = 10
        private const val INITIAL_LOCKOUT_SECONDS = 30L
        private const val MAX_LOCKOUT_SECONDS = 900L
    }
}
