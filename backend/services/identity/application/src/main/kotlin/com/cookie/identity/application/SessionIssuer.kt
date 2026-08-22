package com.cookie.identity.application

import com.cookie.identity.application.ports.AccessTokenProvider
import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.application.ports.RefreshSessionRepository
import com.cookie.identity.application.ports.SecretTokenService
import com.cookie.identity.domain.RefreshSession
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SessionIssuer(
    private val sessions: RefreshSessionRepository,
    private val tokens: SecretTokenService,
    private val ids: IdGenerator,
    private val accessTokens: AccessTokenProvider,
    private val policy: IdentityPolicy,
) {
    fun createFamily(accountId: UUID, deviceId: String?, newUser: Boolean, now: Instant): IssuedTokens {
        val sessionId = ids.next()
        val familyId = ids.next()
        val refresh = tokens.create(sessionId)
        val familyExpiresAt = now.plus(policy.refreshFamilyTtl)
        sessions.add(
            RefreshSession.active(
                id = sessionId,
                accountId = accountId,
                familyId = familyId,
                verifierHash = refresh.verifierHash,
                deviceId = deviceId,
                familyExpiresAt = familyExpiresAt,
                now = now,
            ),
        )
        return issued(accountId, sessionId, refresh.value, familyExpiresAt, newUser, now)
    }

    fun rotate(session: RefreshSession, now: Instant): IssuedTokens {
        val replacementId = ids.next()
        val replacementSecret = tokens.create(replacementId)
        val replacement = RefreshSession.active(
            id = replacementId,
            accountId = session.accountId,
            familyId = session.familyId,
            verifierHash = replacementSecret.verifierHash,
            deviceId = session.deviceId,
            familyExpiresAt = session.familyExpiresAt,
            now = now,
        )
        sessions.add(replacement)
        session.rotate(replacementId, now)
        sessions.save(session)
        return issued(
            accountId = session.accountId,
            sessionId = replacementId,
            refreshToken = replacementSecret.value,
            familyExpiresAt = session.familyExpiresAt,
            newUser = false,
            now = now,
        )
    }

    private fun issued(
        accountId: UUID,
        sessionId: UUID,
        refreshToken: String,
        familyExpiresAt: Instant,
        newUser: Boolean,
        now: Instant,
    ): IssuedTokens {
        val access = accessTokens.issue(accountId, sessionId)
        return IssuedTokens(
            accountId = accountId,
            accessToken = access.value,
            accessTokenExpiresIn = access.expiresInSeconds,
            refreshToken = refreshToken,
            refreshTokenExpiresIn = Duration.between(now, familyExpiresAt).seconds.coerceAtLeast(0).toInt(),
            newUser = newUser,
        )
    }
}
