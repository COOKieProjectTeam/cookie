package com.cookie.identity.application

import com.cookie.identity.application.ports.AccountRepository
import com.cookie.identity.application.ports.ConfirmEmailUseCase
import com.cookie.identity.application.ports.CurrentTimeProvider
import com.cookie.identity.application.ports.IdentityEventRecorder
import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.application.ports.LoginWithEmailUseCase
import com.cookie.identity.application.ports.LogoutUseCase
import com.cookie.identity.application.ports.PasswordHashing
import com.cookie.identity.application.ports.RefreshFamilyRepository
import com.cookie.identity.application.ports.RefreshTokenService
import com.cookie.identity.application.ports.RefreshSessionUseCase
import com.cookie.identity.application.ports.RegistrationAttemptRepository
import com.cookie.identity.application.ports.RegistrationSecretService
import com.cookie.identity.application.ports.TransactionRunner
import com.cookie.identity.domain.Account
import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.domain.DeviceId
import com.cookie.identity.domain.InvalidRegistrationProofException
import com.cookie.identity.domain.PasswordPolicy
import com.cookie.identity.domain.PasswordAuthenticationResult
import com.cookie.identity.domain.RefreshDecision
import com.cookie.identity.domain.RefreshFamilyRevokeReason
import com.cookie.identity.domain.RegistrationProof
import com.cookie.identity.domain.RegistrationTokenDecision
import java.time.Duration
import java.util.UUID

class ConfirmEmailHandler(
    private val accounts: AccountRepository,
    private val attempts: RegistrationAttemptRepository,
    private val transactions: TransactionRunner,
    private val registrationSecrets: RegistrationSecretService,
    private val ids: IdGenerator,
    private val rateLimiter: IdentityRateLimiter,
    private val events: IdentityEventRecorder,
    private val currentTime: CurrentTimeProvider,
) : ConfirmEmailUseCase {
    override fun execute(rawToken: String, registrationProof: String, ip: String) {
        rateLimiter.confirmIp(ip)
        val proofHash = try {
            registrationSecrets.hashRegistrationProof(RegistrationProof.parse(registrationProof))
        } catch (_: InvalidRegistrationProofException) {
            throw InvalidActionTokenException()
        }
        val parsed = try {
            registrationSecrets.parseEmailVerificationToken(rawToken)
        } catch (_: InvalidTokenException) {
            throw InvalidActionTokenException()
        }
        val observed = attempts.findByTokenId(parsed.tokenId)
        val observedTokenHash = observed?.verificationTokenVerifierHash(parsed.tokenId)
        val tokenMatches = observedTokenHash != null &&
            registrationSecrets.verifierMatches(observedTokenHash, parsed.verifierHash)
        val proofMatches = observed != null &&
            registrationSecrets.verifierMatches(observed.registrationProofHash, proofHash)
        if (
            observed?.id == parsed.registrationAttemptId &&
            observed.tokenDecision(parsed.tokenId, tokenMatches, proofMatches, currentTime.now()) ==
            RegistrationTokenDecision.EXACT_RETRY
        ) {
            return
        }

        if (observed == null || observed.id != parsed.registrationAttemptId || !tokenMatches) {
            throw InvalidActionTokenException()
        }
        rateLimiter.confirm(parsed.tokenId.toString())
        if (!proofMatches) {
            throw InvalidActionTokenException()
        }
        val confirmed = transactions.required {
            accounts.lockRegistration(observed.email)
            val existingAccount = accounts.findByEmailForUpdate(observed.email)
            val attempt = attempts.findByTokenIdForUpdate(parsed.tokenId) ?: return@required false
            if (attempt.id != parsed.registrationAttemptId || attempt.email != observed.email) return@required false
            val currentTokenHash = attempt.verificationTokenVerifierHash(parsed.tokenId) ?: return@required false
            val currentTokenMatches = registrationSecrets.verifierMatches(currentTokenHash, parsed.verifierHash)
            val currentProofMatches = registrationSecrets.verifierMatches(attempt.registrationProofHash, proofHash)
            val now = currentTime.now()
            when (attempt.tokenDecision(parsed.tokenId, currentTokenMatches, currentProofMatches, now)) {
                RegistrationTokenDecision.INVALID -> return@required false
                RegistrationTokenDecision.EXACT_RETRY -> {
                    return@required attempt.activatedAccountId == existingAccount?.id
                }
                RegistrationTokenDecision.COMPLETE -> if (existingAccount != null) return@required false
            }
            val registration = Account.register(
                ids.next(),
                attempt.email,
                attempt.passwordHashForActivation(),
                now,
            )
            accounts.add(registration.account)
            attempt.complete(
                tokenId = parsed.tokenId,
                tokenVerifierMatches = currentTokenMatches,
                registrationProofMatches = currentProofMatches,
                accountId = registration.account.id,
                now = now,
            )
            attempts.save(attempt)
            attempts.abandonPendingByEmailExcept(attempt.email, attempt.id, now)
            events.accountActivated(registration.event)
            true
        }
        if (!confirmed) throw InvalidActionTokenException()
    }
}

class LoginWithEmailHandler(
    private val accounts: AccountRepository,
    private val transactions: TransactionRunner,
    private val passwordPolicy: PasswordPolicy,
    private val passwordHashing: PasswordHashing,
    private val rateLimiter: IdentityRateLimiter,
    private val sessionIssuer: SessionIssuer,
    private val currentTime: CurrentTimeProvider,
) : LoginWithEmailUseCase {
    override fun execute(rawEmail: String, password: String, deviceId: DeviceId?, ip: String): IssuedTokens {
        rateLimiter.loginIp(ip)
        val normalizedPassword = passwordPolicy.prepareForAuthentication(password)
        val email = CanonicalEmail.parse(rawEmail)
        rateLimiter.loginEmail(email.value)

        var observed = accounts.findByEmail(email)
        repeat(MAX_SNAPSHOT_ATTEMPTS) {
            val passwordMatches = passwordHashing.matches(
                normalizedPassword.value,
                observed?.passwordHash ?: passwordHashing.dummyHash,
            )
            when (val outcome = transactions.required {
                val current = accounts.findByEmailForUpdate(email)
                if (!sameCredential(observed, current)) return@required LoginOutcome.Retry
                if (current == null) return@required LoginOutcome.Invalid
                val now = currentTime.now()
                when (current.authenticatePassword(passwordMatches, now)) {
                    PasswordAuthenticationResult.AUTHENTICATED -> {
                        accounts.save(current)
                        return@required LoginOutcome.Success(
                            sessionIssuer.createFamily(current.id, deviceId, now),
                        )
                    }
                    PasswordAuthenticationResult.REJECTED_WITH_RECORDED_FAILURE -> accounts.save(current)
                    PasswordAuthenticationResult.REJECTED -> Unit
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
    private val families: RefreshFamilyRepository,
    private val transactions: TransactionRunner,
    private val refreshTokens: RefreshTokenService,
    private val rateLimiter: IdentityRateLimiter,
    private val sessionIssuer: SessionIssuer,
    private val currentTime: CurrentTimeProvider,
) : RefreshSessionUseCase {
    override fun execute(rawToken: String, idempotencyKey: UUID, ip: String): IssuedTokens {
        rateLimiter.ipOnly("refresh", ip, 120, Duration.ofMinutes(1))
        val parsed = refreshTokens.parse(rawToken)
        val observed = families.findCredentialLookup(parsed.id) ?: throw InvalidTokenException()
        if (!refreshTokens.verifierMatches(observed.verifierHash, parsed.verifierHash)) {
            throw InvalidTokenException()
        }
        rateLimiter.refresh(observed.familyId.toString())
        return transactions.required {
            val family = families.findByCredentialIdForUpdate(parsed.id) ?: return@required RefreshOutcome.Invalid
            val expectedVerifier = family.verifierHashFor(parsed.id) ?: return@required RefreshOutcome.Invalid
            val matches = refreshTokens.verifierMatches(expectedVerifier, parsed.verifierHash)
            val now = currentTime.now()
            when (family.refreshDecision(parsed.id, matches, idempotencyKey, now)) {
                RefreshDecision.INVALID,
                RefreshDecision.STALE_RETRY,
                -> RefreshOutcome.Invalid
                RefreshDecision.TOKEN_REUSE -> {
                    family.revoke(RefreshFamilyRevokeReason.TOKEN_REUSE_DETECTED, now)
                    families.save(family)
                    RefreshOutcome.Invalid
                }
                RefreshDecision.ROTATE -> RefreshOutcome.Success(
                    sessionIssuer.rotate(family, rawToken, idempotencyKey, now),
                )
                RefreshDecision.RETRY -> RefreshOutcome.Success(
                    sessionIssuer.retry(family, rawToken, idempotencyKey, now),
                )
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
    private val families: RefreshFamilyRepository,
    private val transactions: TransactionRunner,
    private val refreshTokens: RefreshTokenService,
    private val rateLimiter: IdentityRateLimiter,
    private val currentTime: CurrentTimeProvider,
) : LogoutUseCase {
    override fun execute(rawToken: String, ip: String) {
        rateLimiter.ipOnly("logout", ip, 120, Duration.ofMinutes(1))
        val parsed = try {
            refreshTokens.parse(rawToken)
        } catch (_: InvalidTokenException) {
            return
        }
        val observed = families.findCredentialLookup(parsed.id) ?: return
        if (!refreshTokens.verifierMatches(observed.verifierHash, parsed.verifierHash)) return
        rateLimiter.logout(observed.familyId.toString())
        transactions.requiredUnit {
            val family = families.findByCredentialIdForUpdate(parsed.id) ?: return@requiredUnit
            val expectedVerifier = family.verifierHashFor(parsed.id) ?: return@requiredUnit
            if (!refreshTokens.verifierMatches(expectedVerifier, parsed.verifierHash)) return@requiredUnit
            val now = currentTime.now()
            family.revoke(RefreshFamilyRevokeReason.LOGOUT, now)
            families.save(family)
        }
    }
}
