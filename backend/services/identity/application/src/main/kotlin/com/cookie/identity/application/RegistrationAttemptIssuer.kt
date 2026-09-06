package com.cookie.identity.application

import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.application.ports.IdentityEventRecorder
import com.cookie.identity.application.ports.RegistrationAttemptRepository
import com.cookie.identity.application.ports.RegistrationSecretService
import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.domain.LocaleTag
import com.cookie.identity.domain.RegistrationAttempt
import com.cookie.identity.domain.VerifierHash
import java.time.Instant
import java.util.UUID

/** Creates and persists email-token children; callers own the transaction. */
class RegistrationAttemptIssuer(
    private val attempts: RegistrationAttemptRepository,
    private val registrationSecrets: RegistrationSecretService,
    private val ids: IdGenerator,
    private val events: IdentityEventRecorder,
    private val policy: IdentityPolicy,
) {
    @Suppress("LongParameterList")
    fun start(
        attemptId: UUID,
        email: CanonicalEmail,
        pendingPasswordHash: String,
        registrationProofHash: VerifierHash,
        requestFingerprint: VerifierHash,
        locale: LocaleTag?,
        now: Instant,
    ) {
        val tokenId = ids.next()
        val token = registrationSecrets.createEmailVerificationToken(attemptId, tokenId)
        val attemptExpiresAt = now.plus(policy.registrationAttemptTtl)
        val tokenExpiresAt = minOf(now.plus(policy.verificationTokenTtl), attemptExpiresAt)
        attempts.add(
            RegistrationAttempt.start(
                id = attemptId,
                email = email,
                registrationProofHash = registrationProofHash,
                requestFingerprint = requestFingerprint,
                locale = locale,
                pendingPasswordHash = pendingPasswordHash,
                expiresAt = attemptExpiresAt,
                firstTokenId = tokenId,
                firstTokenVerifierHash = token.verifierHash,
                firstTokenExpiresAt = tokenExpiresAt,
                now = now,
            ),
        )
        recordVerificationRequest(attemptId, email, locale, token.value, tokenExpiresAt, now)
    }

    fun issueVerificationToken(attempt: RegistrationAttempt, now: Instant) {
        val tokenId = ids.next()
        val token = registrationSecrets.createEmailVerificationToken(attempt.id, tokenId)
        val tokenExpiresAt = minOf(now.plus(policy.verificationTokenTtl), attempt.expiresAt)
        attempt.issueVerificationToken(
            id = tokenId,
            verifierHash = token.verifierHash,
            expiresAt = tokenExpiresAt,
            cooldown = policy.verificationResendCooldown,
            now = now,
        )
        attempts.save(attempt)
        recordVerificationRequest(attempt.id, attempt.email, attempt.locale, token.value, tokenExpiresAt, now)
    }

    private fun recordVerificationRequest(
        attemptId: UUID,
        email: CanonicalEmail,
        locale: LocaleTag?,
        rawToken: String,
        expiresAt: Instant,
        now: Instant,
    ) {
        events.verificationRequested(
            registrationAttemptId = attemptId,
            email = email,
            locale = locale,
            rawToken = rawToken,
            expiresAt = expiresAt,
            now = now,
        )
    }
}
