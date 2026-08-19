package com.cookie.identity.application

import com.cookie.identity.config.IdentityProperties
import com.cookie.identity.domain.EmailCanonicalizer
import com.cookie.identity.domain.InvalidActionTokenException
import com.cookie.identity.domain.InvalidCredentialsException
import com.cookie.identity.domain.InvalidInputException
import com.cookie.identity.domain.InvalidTokenException
import com.cookie.identity.domain.PasswordHasher
import com.cookie.identity.domain.PasswordPolicy
import com.cookie.identity.domain.SecretTokens
import com.cookie.identity.domain.UuidV7Generator
import com.cookie.identity.persistence.CredentialRecord
import com.cookie.identity.persistence.IdentityRepository
import com.cookie.identity.security.AccessTokenIssuer
import com.cookie.platform.postgres.Transactions
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.min

data class IssuedTokens(
    val accountId: UUID,
    val accessToken: String,
    val accessTokenExpiresIn: Int,
    val refreshToken: String,
    val refreshTokenExpiresIn: Int,
    val newUser: Boolean,
)

@Service
class IdentityService(
    private val repository: IdentityRepository,
    private val transactions: Transactions,
    private val properties: IdentityProperties,
    private val emailCanonicalizer: EmailCanonicalizer,
    private val passwordPolicy: PasswordPolicy,
    private val passwordHasher: PasswordHasher,
    private val secretTokens: SecretTokens,
    private val uuidV7Generator: UuidV7Generator,
    private val rateLimiter: IdentityRateLimiter,
    private val outboxWriter: IdentityOutboxWriter,
    private val accessTokenIssuer: AccessTokenIssuer,
    private val clock: Clock,
) {
    fun register(rawEmail: String, password: String, locale: String?, ip: String) {
        validateOptionalLength(locale, 35, "locale")
        val email = emailCanonicalizer.canonicalize(rawEmail)
        passwordPolicy.validate(password)
        rateLimiter.register(email, ip)
        val passwordHash = passwordHasher.encode(password)
        transactions.requiredUnit {
            repository.advisoryLock(email)
            if (repository.findCredentialByEmail(email) != null) return@requiredUnit
            val now = clock.instant()
            val accountId = uuidV7Generator.next()
            repository.insertAccount(accountId, email, passwordHash, now)
            createVerificationToken(accountId, email, locale, now)
        }
    }

    fun resend(rawEmail: String, ip: String) {
        val email = emailCanonicalizer.canonicalize(rawEmail)
        rateLimiter.resend(email, ip)
        transactions.requiredUnit {
            repository.advisoryLock(email)
            val credential = repository.findCredentialByEmail(email, forUpdate = true)
                ?: return@requiredUnit
            if (credential.accountStatus != PENDING_VERIFICATION) return@requiredUnit
            val now = clock.instant()
            val latest = repository.latestActionToken(credential.accountId)
            if (latest != null && latest.createdAt.plus(properties.verificationResendCooldown).isAfter(now)) {
                return@requiredUnit
            }
            repository.revokeActiveActionTokens(credential.accountId, now)
            createVerificationToken(credential.accountId, email, null, now)
        }
    }

    fun confirm(rawToken: String, deviceId: String?, ip: String): IssuedTokens {
        validateOptionalLength(deviceId, 255, "deviceId")
        rateLimiter.ipOnly("confirm", ip, 60, Duration.ofMinutes(15))
        val parsed = try {
            secretTokens.parse(rawToken)
        } catch (_: InvalidTokenException) {
            throw InvalidActionTokenException()
        }
        rateLimiter.confirm(parsed.id.toString())
        val outcome = requireNotNull(transactions.requiredNullable {
            val now = clock.instant()
            val token = repository.findActionTokenForUpdate(parsed.id) ?: return@requiredNullable null
            val valid = secretTokens.verifierMatches(token.tokenHash, parsed.verifierHash) &&
                token.consumedAt == null && token.revokedAt == null && token.expiresAt.isAfter(now)
            if (!valid) return@requiredNullable null
            val accountCreatedAt = repository.activateAccount(token.accountId, now) ?: return@requiredNullable null
            repository.consumeActionToken(token.id, now)
            outboxWriter.accountCreated(token.accountId, accountCreatedAt, now)
            issueNewFamily(token.accountId, deviceId, newUser = true, now = now)
        }) { throw InvalidActionTokenException() }
        return outcome
    }

    fun login(rawEmail: String, password: String, deviceId: String?, ip: String): IssuedTokens {
        validateOptionalLength(deviceId, 255, "deviceId")
        val email = emailCanonicalizer.canonicalize(rawEmail)
        passwordPolicy.validate(password)
        rateLimiter.login(email, ip)
        val result = transactions.requiredNullable {
            val now = clock.instant()
            val credential = repository.findCredentialByEmail(email, forUpdate = true)
            val activeCredential = credential?.takeIf {
                it.accountStatus == ACTIVE && (it.lockedUntil == null || !it.lockedUntil.isAfter(now))
            }
            val passwordMatches = passwordHasher.matches(
                password,
                activeCredential?.passwordHash ?: passwordHasher.dummyHash,
            )
            val authenticated = activeCredential != null && passwordMatches
            if (!authenticated) {
                if (credential != null && credential.accountStatus == ACTIVE) {
                    recordFailure(credential, now)
                }
                return@requiredNullable null
            }
            checkNotNull(activeCredential)
            repository.resetLoginFailures(activeCredential.accountId, now)
            issueNewFamily(activeCredential.accountId, deviceId, newUser = false, now = now)
        }
        return result ?: throw InvalidCredentialsException()
    }

    fun refresh(rawToken: String, ip: String): IssuedTokens {
        rateLimiter.ipOnly("refresh", ip, 120, Duration.ofMinutes(1))
        val parsed = secretTokens.parse(rawToken)
        rateLimiter.refresh(parsed.id.toString())
        val result = transactions.required {
            val now = clock.instant()
            val session = repository.findRefreshSessionForUpdate(parsed.id)
                ?: return@required RefreshOutcome.Invalid
            if (!secretTokens.verifierMatches(session.tokenHash, parsed.verifierHash)) {
                return@required RefreshOutcome.Invalid
            }
            if (session.status == ROTATED) {
                repository.revokeRefreshFamily(session.familyId, now)
                return@required RefreshOutcome.Invalid
            }
            if (session.status != ACTIVE_SESSION || !session.familyExpiresAt.isAfter(now)) {
                return@required RefreshOutcome.Invalid
            }
            val replacementId = uuidV7Generator.next()
            val replacement = secretTokens.create(replacementId)
            repository.insertRefreshSession(
                id = replacementId,
                accountId = session.accountId,
                familyId = session.familyId,
                tokenHash = replacement.verifierHash,
                deviceId = session.deviceId,
                familyExpiresAt = session.familyExpiresAt,
                now = now,
            )
            repository.rotateRefreshSession(session.id, replacementId, now)
            RefreshOutcome.Success(
                tokensFor(
                    session.accountId,
                    replacementId,
                    replacement.value,
                    session.familyExpiresAt,
                    newUser = false,
                    now = now,
                ),
            )
        }
        return when (result) {
            is RefreshOutcome.Success -> result.tokens
            else -> throw InvalidTokenException()
        }
    }

    fun logout(rawToken: String, ip: String) {
        rateLimiter.ipOnly("logout", ip, 120, Duration.ofMinutes(1))
        val parsed = try {
            secretTokens.parse(rawToken)
        } catch (_: InvalidTokenException) {
            return
        }
        rateLimiter.logout(parsed.id.toString())
        transactions.requiredUnit {
            val session = repository.findRefreshSessionForUpdate(parsed.id) ?: return@requiredUnit
            if (secretTokens.verifierMatches(session.tokenHash, parsed.verifierHash)) {
                repository.revokeSession(session.id, "LOGOUT", clock.instant())
            }
        }
    }

    private fun createVerificationToken(accountId: UUID, email: String, locale: String?, now: Instant) {
        val tokenId = uuidV7Generator.next()
        val rawToken = secretTokens.create(tokenId)
        val expiresAt = now.plus(properties.verificationTokenTtl)
        repository.insertActionToken(tokenId, accountId, rawToken.verifierHash, expiresAt, now)
        outboxWriter.verificationRequested(accountId, email, locale, rawToken.value, expiresAt, now)
    }

    private fun issueNewFamily(
        accountId: UUID,
        deviceId: String?,
        newUser: Boolean,
        now: Instant,
    ): IssuedTokens {
        val sessionId = uuidV7Generator.next()
        val familyId = uuidV7Generator.next()
        val refresh = secretTokens.create(sessionId)
        val familyExpiresAt = now.plus(properties.refreshFamilyTtl)
        repository.insertRefreshSession(
            sessionId,
            accountId,
            familyId,
            refresh.verifierHash,
            deviceId,
            familyExpiresAt,
            now,
        )
        return tokensFor(accountId, sessionId, refresh.value, familyExpiresAt, newUser, now)
    }

    private fun tokensFor(
        accountId: UUID,
        sessionId: UUID,
        refreshToken: String,
        familyExpiresAt: Instant,
        newUser: Boolean,
        now: Instant,
    ) = IssuedTokens(
        accountId = accountId,
        accessToken = accessTokenIssuer.issue(accountId, sessionId),
        accessTokenExpiresIn = accessTokenIssuer.expiresInSeconds(),
        refreshToken = refreshToken,
        refreshTokenExpiresIn = Duration.between(now, familyExpiresAt).seconds.coerceAtLeast(0).toInt(),
        newUser = newUser,
    )

    private fun recordFailure(credential: CredentialRecord, now: Instant) {
        val newCount = credential.failedLoginCount + 1
        val lockedUntil = if (newCount >= 5) {
            val exponent = min(newCount - 5, 10)
            val seconds = min(900L, 30L * (1L shl exponent))
            now.plusSeconds(seconds)
        } else {
            null
        }
        repository.recordLoginFailure(credential.accountId, newCount, lockedUntil, now)
    }

    private fun validateOptionalLength(value: String?, maximum: Int, field: String) {
        if (value != null && value.codePointCount(0, value.length) > maximum) {
            throw InvalidInputException("$field is too long")
        }
    }

    private sealed interface RefreshOutcome {
        data class Success(val tokens: IssuedTokens) : RefreshOutcome
        data object Invalid : RefreshOutcome
    }

    companion object {
        private const val PENDING_VERIFICATION = "PENDING_VERIFICATION"
        private const val ACTIVE = "ACTIVE"
        private const val ACTIVE_SESSION = "ACTIVE"
        private const val ROTATED = "ROTATED"
    }
}
