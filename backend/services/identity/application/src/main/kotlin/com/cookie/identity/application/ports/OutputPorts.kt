package com.cookie.identity.application.ports

import com.cookie.identity.application.GeneratedSecretToken
import com.cookie.identity.application.IssuedAccessToken
import com.cookie.identity.application.ParsedSecretToken
import com.cookie.identity.application.PublicJwk
import com.cookie.identity.application.RateLimitWindow
import com.cookie.identity.domain.Account
import com.cookie.identity.domain.AccountActivated
import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.domain.RefreshSession
import com.cookie.identity.domain.VerificationChallenge
import java.time.Duration
import java.time.Instant
import java.util.UUID

interface AccountRepository {
    fun lockRegistration(email: CanonicalEmail)
    fun findByEmail(email: CanonicalEmail): Account?
    fun findByEmailForUpdate(email: CanonicalEmail): Account?
    fun findByIdForUpdate(accountId: UUID): Account?
    fun add(account: Account)
    fun save(account: Account)
}

interface VerificationChallengeRepository {
    fun findById(id: UUID): VerificationChallenge?
    fun findByIdForUpdate(id: UUID): VerificationChallenge?
    fun findLatest(accountId: UUID): VerificationChallenge?
    fun findActiveForUpdate(accountId: UUID, now: Instant): List<VerificationChallenge>
    fun add(challenge: VerificationChallenge)
    fun save(challenge: VerificationChallenge)
}

interface RefreshSessionRepository {
    fun findById(id: UUID): RefreshSession?
    fun lockFamily(familyId: UUID)
    fun findFamilyForUpdate(familyId: UUID): List<RefreshSession>
    fun add(session: RefreshSession)
    fun save(session: RefreshSession)
    fun saveAll(sessions: Collection<RefreshSession>) = sessions.forEach(::save)
}

interface RateLimitRepository {
    fun consume(scopeKey: String, window: Duration): RateLimitWindow
}

interface PasswordHashing {
    val dummyHash: String
    fun encode(password: String): String
    fun matches(password: String, encoded: String): Boolean
}

interface SecretTokenService {
    fun create(id: UUID): GeneratedSecretToken
    fun parse(value: String): ParsedSecretToken
    fun verifierMatches(expectedHex: String, actualHex: String): Boolean
}

interface IdGenerator {
    fun next(): UUID
}

interface AccessTokenProvider {
    fun issue(accountId: UUID, sessionId: UUID): IssuedAccessToken
    fun publicKeys(): List<PublicJwk>
}

interface IdentityEventRecorder {
    fun verificationRequested(
        accountId: UUID,
        email: CanonicalEmail,
        locale: String?,
        rawToken: String,
        expiresAt: Instant,
        now: Instant,
    )

    fun accountActivated(event: AccountActivated)
}

interface TransactionRunner {
    fun <T : Any> required(block: () -> T): T
    fun requiredUnit(block: () -> Unit)
    fun <T : Any> requiredNullable(block: () -> T?): T?
}
