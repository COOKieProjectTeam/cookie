package com.cookie.identity.application

import com.cookie.identity.application.ports.AccountRepository
import com.cookie.identity.application.ports.ConfirmEmailUseCase
import com.cookie.identity.application.ports.IdentityEventRecorder
import com.cookie.identity.application.ports.LoginWithEmailUseCase
import com.cookie.identity.application.ports.LogoutUseCase
import com.cookie.identity.application.ports.PasswordHashing
import com.cookie.identity.application.ports.RefreshSessionRepository
import com.cookie.identity.application.ports.RefreshSessionUseCase
import com.cookie.identity.application.ports.SecretTokenService
import com.cookie.identity.application.ports.TransactionRunner
import com.cookie.identity.application.ports.VerificationChallengeRepository
import com.cookie.identity.domain.AccountStatus
import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.domain.RefreshRevokeReason
import com.cookie.identity.domain.RotationDecision
import java.time.Clock
import java.time.Duration

class ConfirmEmailHandler(
    private val accounts: AccountRepository,
    private val challenges: VerificationChallengeRepository,
    private val transactions: TransactionRunner,
    private val secretTokens: SecretTokenService,
    private val rateLimiter: IdentityRateLimiter,
    private val events: IdentityEventRecorder,
    private val sessionIssuer: SessionIssuer,
    private val clock: Clock,
) : ConfirmEmailUseCase {
    override fun execute(rawToken: String, deviceId: String?, ip: String): IssuedTokens {
        validateOptionalLength(deviceId, 255, "deviceId")
        rateLimiter.ipOnly("confirm", ip, 60, Duration.ofMinutes(15))
        val parsed = try {
            secretTokens.parse(rawToken)
        } catch (_: InvalidTokenException) {
            throw InvalidActionTokenException()
        }
        val observed = challenges.findById(parsed.id) ?: throw InvalidActionTokenException()
        rateLimiter.confirm(parsed.id.toString())
        val outcome = transactions.requiredNullable {
            val account = accounts.findByIdForUpdate(observed.accountId) ?: return@requiredNullable null
            val challenge = challenges.findByIdForUpdate(parsed.id) ?: return@requiredNullable null
            if (challenge.accountId != account.id) return@requiredNullable null
            val verifierMatches = secretTokens.verifierMatches(challenge.verifierHash, parsed.verifierHash)
            val now = clock.instant()
            if (!challenge.isUsable(verifierMatches, now)) return@requiredNullable null
            val activation = account.activate(now) ?: return@requiredNullable null
            challenge.consume(now)
            accounts.save(account)
            challenges.save(challenge)
            events.accountActivated(activation)
            sessionIssuer.createFamily(account.id, deviceId, newUser = true, now = now)
        }
        return outcome ?: throw InvalidActionTokenException()
    }
}

class LoginWithEmailHandler(
    private val accounts: AccountRepository,
    private val transactions: TransactionRunner,
    private val passwordHashing: PasswordHashing,
    private val rateLimiter: IdentityRateLimiter,
    private val sessionIssuer: SessionIssuer,
    private val clock: Clock,
) : LoginWithEmailUseCase {
    override fun execute(rawEmail: String, password: String, deviceId: String?, ip: String): IssuedTokens {
        validateOptionalLength(deviceId, 255, "deviceId")
        validateCodePointLength(password, 1, 128, "password")
        val email = CanonicalEmail.parse(rawEmail)
        rateLimiter.login(email.value, ip)

        var observed = accounts.findByEmail(email)
        repeat(MAX_SNAPSHOT_ATTEMPTS) {
            val passwordMatches = passwordHashing.matches(
                password,
                observed?.passwordHash ?: passwordHashing.dummyHash,
            )
            when (val outcome = transactions.required {
                val current = accounts.findByEmailForUpdate(email)
                if (!sameCredential(observed, current)) return@required LoginOutcome.Retry
                if (current == null) return@required LoginOutcome.Invalid
                val now = clock.instant()
                if (current.canAuthenticate(passwordMatches, now)) {
                    current.recordSuccessfulLogin()
                    accounts.save(current)
                    return@required LoginOutcome.Success(
                        sessionIssuer.createFamily(current.id, deviceId, newUser = false, now = now),
                    )
                }
                if (current.status == AccountStatus.ACTIVE && !passwordMatches) {
                    current.recordFailedPassword(now)
                    accounts.save(current)
                }
                LoginOutcome.Invalid
            }) {
                is LoginOutcome.Success -> return outcome.tokens
                LoginOutcome.Invalid -> throw InvalidCredentialsException()
                LoginOutcome.Retry -> observed = accounts.findByEmail(email)
            }
        }
        throw IdentityUnavailableException("Credential changed during authentication")
    }

    private fun sameCredential(observed: com.cookie.identity.domain.Account?, current: com.cookie.identity.domain.Account?): Boolean =
        observed?.id == current?.id && observed?.passwordHash == current?.passwordHash

    private sealed interface LoginOutcome {
        data class Success(val tokens: IssuedTokens) : LoginOutcome
        data object Invalid : LoginOutcome
        data object Retry : LoginOutcome
    }

    companion object {
        private const val MAX_SNAPSHOT_ATTEMPTS = 2
    }
}

class RefreshSessionHandler(
    private val sessions: RefreshSessionRepository,
    private val transactions: TransactionRunner,
    private val secretTokens: SecretTokenService,
    private val rateLimiter: IdentityRateLimiter,
    private val sessionIssuer: SessionIssuer,
    private val clock: Clock,
) : RefreshSessionUseCase {
    override fun execute(rawToken: String, ip: String): IssuedTokens {
        rateLimiter.ipOnly("refresh", ip, 120, Duration.ofMinutes(1))
        val parsed = secretTokens.parse(rawToken)
        val observed = sessions.findById(parsed.id) ?: throw InvalidTokenException()
        rateLimiter.refresh(parsed.id.toString())
        return transactions.required {
            sessions.lockFamily(observed.familyId)
            val family = sessions.findFamilyForUpdate(observed.familyId)
            val current = family.singleOrNull { it.id == parsed.id } ?: return@required RefreshOutcome.Invalid
            val matches = secretTokens.verifierMatches(current.verifierHash, parsed.verifierHash)
            val now = clock.instant()
            when (current.rotationDecision(matches, now)) {
                RotationDecision.INVALID -> RefreshOutcome.Invalid
                RotationDecision.REPLAY -> {
                    family.forEach { it.revoke(RefreshRevokeReason.REPLAY_DETECTED, now) }
                    sessions.saveAll(family)
                    RefreshOutcome.Invalid
                }
                RotationDecision.ROTATE -> RefreshOutcome.Success(sessionIssuer.rotate(current, now))
            }
        }.let { outcome ->
            when (outcome) {
                is RefreshOutcome.Success -> outcome.tokens
                RefreshOutcome.Invalid -> throw InvalidTokenException()
            }
        }
    }

    private sealed interface RefreshOutcome {
        data class Success(val tokens: IssuedTokens) : RefreshOutcome
        data object Invalid : RefreshOutcome
    }
}

class LogoutHandler(
    private val sessions: RefreshSessionRepository,
    private val transactions: TransactionRunner,
    private val secretTokens: SecretTokenService,
    private val rateLimiter: IdentityRateLimiter,
    private val clock: Clock,
) : LogoutUseCase {
    override fun execute(rawToken: String, ip: String) {
        rateLimiter.ipOnly("logout", ip, 120, Duration.ofMinutes(1))
        val parsed = try {
            secretTokens.parse(rawToken)
        } catch (_: InvalidTokenException) {
            return
        }
        val observed = sessions.findById(parsed.id) ?: return
        rateLimiter.logout(parsed.id.toString())
        transactions.requiredUnit {
            sessions.lockFamily(observed.familyId)
            val family = sessions.findFamilyForUpdate(observed.familyId)
            val presented = family.singleOrNull { it.id == parsed.id } ?: return@requiredUnit
            if (!secretTokens.verifierMatches(presented.verifierHash, parsed.verifierHash)) return@requiredUnit
            val now = clock.instant()
            family.forEach { it.revoke(RefreshRevokeReason.LOGOUT, now) }
            sessions.saveAll(family)
        }
    }
}
