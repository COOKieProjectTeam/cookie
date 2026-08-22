package com.cookie.identity.application

import com.cookie.identity.application.ports.AccountRepository
import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.application.ports.PasswordHashing
import com.cookie.identity.application.ports.RegisterWithEmailUseCase
import com.cookie.identity.application.ports.ResendEmailVerificationUseCase
import com.cookie.identity.application.ports.TransactionRunner
import com.cookie.identity.application.ports.VerificationChallengeRepository
import com.cookie.identity.domain.Account
import com.cookie.identity.domain.AccountStatus
import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.domain.InvalidInputException
import com.cookie.identity.domain.PasswordPolicy
import java.time.Clock

class RegisterWithEmailHandler(
    private val accounts: AccountRepository,
    private val transactions: TransactionRunner,
    private val passwordPolicy: PasswordPolicy,
    private val passwordHashing: PasswordHashing,
    private val ids: IdGenerator,
    private val rateLimiter: IdentityRateLimiter,
    private val challengeIssuer: VerificationChallengeIssuer,
    private val clock: Clock,
) : RegisterWithEmailUseCase {
    override fun execute(rawEmail: String, password: String, locale: String?, ip: String) {
        validateOptionalLength(locale, 35, "locale")
        val email = CanonicalEmail.parse(rawEmail)
        passwordPolicy.validate(password)
        rateLimiter.register(email.value, ip)
        val passwordHash = passwordHashing.encode(password)
        transactions.requiredUnit {
            accounts.lockRegistration(email)
            if (accounts.findByEmail(email) != null) return@requiredUnit
            val now = clock.instant()
            val account = Account.pending(ids.next(), email, passwordHash, now)
            accounts.add(account)
            challengeIssuer.issue(account, locale, now)
        }
    }
}

class ResendEmailVerificationHandler(
    private val accounts: AccountRepository,
    private val challenges: VerificationChallengeRepository,
    private val transactions: TransactionRunner,
    private val rateLimiter: IdentityRateLimiter,
    private val challengeIssuer: VerificationChallengeIssuer,
    private val policy: IdentityPolicy,
    private val clock: Clock,
) : ResendEmailVerificationUseCase {
    override fun execute(rawEmail: String, ip: String) {
        val email = CanonicalEmail.parse(rawEmail)
        rateLimiter.resend(email.value, ip)
        val observed = accounts.findByEmail(email) ?: return
        transactions.requiredUnit {
            val account = accounts.findByIdForUpdate(observed.id) ?: return@requiredUnit
            if (account.email != email || account.status != AccountStatus.PENDING_VERIFICATION) return@requiredUnit
            val now = clock.instant()
            val latest = challenges.findLatest(account.id)
            if (latest != null && latest.createdAt.plus(policy.verificationResendCooldown).isAfter(now)) {
                return@requiredUnit
            }
            challenges.findActiveForUpdate(account.id, now).forEach { challenge ->
                challenge.revoke(now)
                challenges.save(challenge)
            }
            challengeIssuer.issue(account, locale = null, now = now)
        }
    }
}

internal fun validateOptionalLength(value: String?, maximum: Int, field: String) {
    if (value != null && value.codePointCount(0, value.length) > maximum) {
        throw InvalidInputException("$field is too long")
    }
}

internal fun validateCodePointLength(value: String, minimum: Int, maximum: Int, field: String) {
    val length = value.codePointCount(0, value.length)
    if (length !in minimum..maximum) {
        throw InvalidInputException("$field must contain between $minimum and $maximum characters")
    }
}
