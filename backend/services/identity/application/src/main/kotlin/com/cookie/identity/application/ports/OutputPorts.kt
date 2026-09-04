package com.cookie.identity.application.ports

import com.cookie.identity.application.GeneratedEmailVerificationToken
import com.cookie.identity.application.GeneratedSecretToken
import com.cookie.identity.application.IssuedAccessToken
import com.cookie.identity.application.ParsedEmailVerificationToken
import com.cookie.identity.application.ParsedSecretToken
import com.cookie.identity.application.PublicJwk
import com.cookie.identity.application.RateLimitWindow
import com.cookie.identity.application.RefreshCredentialLookup
import com.cookie.identity.domain.Account
import com.cookie.identity.domain.AccountActivated
import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.domain.LocaleTag
import com.cookie.identity.domain.NormalizedPassword
import com.cookie.identity.domain.RefreshFamily
import com.cookie.identity.domain.RegistrationAttempt
import com.cookie.identity.domain.RegistrationProof
import com.cookie.identity.domain.VerifierHash
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

interface RegistrationAttemptRepository {
    fun findById(id: UUID): RegistrationAttempt?
    fun findByIdForUpdate(id: UUID): RegistrationAttempt?
    fun findByTokenId(tokenId: UUID): RegistrationAttempt?
    fun findByTokenIdForUpdate(tokenId: UUID): RegistrationAttempt?
    fun findByProof(proofHash: VerifierHash): RegistrationAttempt?
    fun lockCreationKeys(attemptId: UUID, proofHash: VerifierHash)
    fun add(attempt: RegistrationAttempt)
    fun save(attempt: RegistrationAttempt)
    fun abandonPendingByEmailExcept(email: CanonicalEmail, winningAttemptId: UUID, now: Instant): Int
}

interface RefreshFamilyRepository {
    fun findCredentialLookup(id: UUID): RefreshCredentialLookup?
    fun findByCredentialIdForUpdate(credentialId: UUID): RefreshFamily?
    fun add(family: RefreshFamily)
    fun save(family: RefreshFamily)
}

interface RateLimitRepository {
    fun consume(scopeKey: String, window: Duration): RateLimitWindow
}

interface PasswordHashing {
    val dummyHash: String
    fun encode(password: String): String
    fun matches(password: String, encoded: String): Boolean
}

interface RefreshTokenService {
    fun create(id: UUID): GeneratedSecretToken
    fun createRefreshSuccessor(
        predecessorRawToken: String,
        replacementId: UUID,
        idempotencyKey: UUID,
    ): GeneratedSecretToken
    fun parse(value: String): ParsedSecretToken
    fun verifierMatches(expected: VerifierHash, actual: VerifierHash): Boolean
}

interface RegistrationSecretService {
    fun createEmailVerificationToken(attemptId: UUID, tokenId: UUID): GeneratedEmailVerificationToken
    fun parseEmailVerificationToken(value: String): ParsedEmailVerificationToken
    fun hashRegistrationProof(proof: RegistrationProof): VerifierHash
    fun registrationRequestFingerprint(
        proof: RegistrationProof,
        attemptId: UUID,
        email: CanonicalEmail,
        password: NormalizedPassword,
        locale: LocaleTag?,
    ): VerifierHash
    fun verifierMatches(expected: VerifierHash, actual: VerifierHash): Boolean
}

interface IdGenerator {
    fun next(): UUID
}

fun interface CurrentTimeProvider {
    fun now(): Instant
}

interface AccessTokenProvider {
    fun issue(accountId: UUID, sessionId: UUID, now: Instant): IssuedAccessToken
    fun publicKeys(): List<PublicJwk>
}

interface IdentityEventRecorder {
    fun verificationRequested(
        registrationAttemptId: UUID,
        email: CanonicalEmail,
        locale: LocaleTag?,
        rawToken: String,
        expiresAt: Instant,
        now: Instant,
    )

    fun accountActivated(event: AccountActivated)
}

interface TransactionRunner {
    fun <T : Any> required(block: () -> T): T
    fun requiredUnit(block: () -> Unit)
}
