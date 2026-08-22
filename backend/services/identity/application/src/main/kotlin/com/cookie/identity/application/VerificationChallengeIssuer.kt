package com.cookie.identity.application

import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.application.ports.IdentityEventRecorder
import com.cookie.identity.application.ports.SecretTokenService
import com.cookie.identity.application.ports.VerificationChallengeRepository
import com.cookie.identity.domain.Account
import java.time.Instant

class VerificationChallengeIssuer(
    private val challenges: VerificationChallengeRepository,
    private val secretTokens: SecretTokenService,
    private val ids: IdGenerator,
    private val events: IdentityEventRecorder,
    private val policy: IdentityPolicy,
) {
    fun issue(account: Account, locale: String?, now: Instant) {
        val challengeId = ids.next()
        val rawToken = secretTokens.create(challengeId)
        val expiresAt = now.plus(policy.verificationTokenTtl)
        challenges.add(
            com.cookie.identity.domain.VerificationChallenge.create(
                id = challengeId,
                accountId = account.id,
                verifierHash = rawToken.verifierHash,
                expiresAt = expiresAt,
                now = now,
            ),
        )
        events.verificationRequested(
            accountId = account.id,
            email = account.email,
            locale = locale,
            rawToken = rawToken.value,
            expiresAt = expiresAt,
            now = now,
        )
    }
}
