package com.cookie.identity.application

import com.cookie.identity.application.ports.AccessTokenProvider
import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.application.ports.RefreshFamilyRepository
import com.cookie.identity.application.ports.RefreshTokenService
import com.cookie.identity.domain.DeviceId
import com.cookie.identity.domain.RefreshFamily
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SessionIssuer(
    private val families: RefreshFamilyRepository,
    private val tokens: RefreshTokenService,
    private val ids: IdGenerator,
    private val accessTokens: AccessTokenProvider,
    private val policy: IdentityPolicy,
) {
    fun createFamily(accountId: UUID, deviceId: DeviceId?, now: Instant): IssuedTokens {
        val familyId = ids.next()
        val credentialId = ids.next()
        val refresh = tokens.create(credentialId)
        val familyExpiresAt = now.plus(policy.refreshFamilyTtl)
        families.add(
            RefreshFamily.start(
                id = familyId,
                accountId = accountId,
                firstCredentialId = credentialId,
                firstVerifierHash = refresh.verifierHash,
                deviceId = deviceId,
                expiresAt = familyExpiresAt,
                now = now,
            ),
        )
        return issued(accountId, familyId, refresh.value, familyExpiresAt, now)
    }

    fun rotate(
        family: RefreshFamily,
        predecessorRawToken: String,
        idempotencyKey: UUID,
        now: Instant,
    ): IssuedTokens {
        val predecessorId = family.currentCredentialId
        val replacementId = ids.next()
        val replacement = tokens.createRefreshSuccessor(
            predecessorRawToken,
            replacementId,
            idempotencyKey,
        )
        family.rotateCurrentTo(
            presentedCredentialId = predecessorId,
            replacementCredentialId = replacementId,
            replacementVerifierHash = replacement.verifierHash,
            idempotencyKey = idempotencyKey,
            retryUntil = family.expiresAt,
            now = now,
        )
        families.save(family)
        return issued(
            accountId = family.accountId,
            sessionId = family.id,
            refreshToken = replacement.value,
            familyExpiresAt = family.expiresAt,
            now = now,
        )
    }

    fun retry(
        family: RefreshFamily,
        predecessorRawToken: String,
        idempotencyKey: UUID,
        now: Instant,
    ): IssuedTokens {
        val replacement = tokens.createRefreshSuccessor(
            predecessorRawToken,
            family.currentCredentialId,
            idempotencyKey,
        )
        return issued(
            accountId = family.accountId,
            sessionId = family.id,
            refreshToken = replacement.value,
            familyExpiresAt = family.expiresAt,
            now = now,
        )
    }

    private fun issued(
        accountId: UUID,
        sessionId: UUID,
        refreshToken: String,
        familyExpiresAt: Instant,
        now: Instant,
    ): IssuedTokens {
        val access = accessTokens.issue(accountId, sessionId, now)
        return IssuedTokens(
            accountId = accountId,
            accessToken = access.value,
            accessTokenExpiresIn = access.expiresInSeconds,
            refreshToken = refreshToken,
            refreshTokenExpiresIn = Duration.between(now, familyExpiresAt).seconds.coerceAtLeast(0).toInt(),
        )
    }
}
