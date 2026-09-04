package com.cookie.identity.application

import com.cookie.identity.domain.VerifierHash
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class IdentityPolicy(
    val refreshFamilyTtl: Duration,
    val registrationAttemptTtl: Duration,
    val verificationTokenTtl: Duration,
    val verificationResendCooldown: Duration,
) {
    val refreshRetryWindow: Duration
        get() = REFRESH_RETRY_WINDOW

    init {
        require(refreshFamilyTtl.isPositive()) { "Refresh family TTL must be positive" }
        require(refreshRetryWindow < refreshFamilyTtl) { "Refresh retry window must be shorter than family TTL" }
        require(registrationAttemptTtl.isPositive()) { "Registration attempt TTL must be positive" }
        require(verificationTokenTtl.isPositive()) { "Verification token TTL must be positive" }
        require(verificationTokenTtl <= registrationAttemptTtl) {
            "Verification token TTL must not exceed registration attempt TTL"
        }
        require(!verificationResendCooldown.isNegative) { "Verification resend cooldown must not be negative" }
        require(verificationResendCooldown <= verificationTokenTtl) {
            "Verification resend cooldown must not exceed token TTL"
        }
        require(refreshFamilyTtl.seconds <= Int.MAX_VALUE) { "Refresh family TTL is too large" }
        require(registrationAttemptTtl.seconds <= Int.MAX_VALUE) { "Registration attempt TTL is too large" }
    }

    companion object {
        /** Public protocol guarantee; clients use this exact retry window. */
        val REFRESH_RETRY_WINDOW: Duration = Duration.ofSeconds(30)
    }
}

class GeneratedSecretToken(
    val id: UUID,
    val value: String,
    val verifierHash: VerifierHash,
) {
    override fun toString(): String = "GeneratedSecretToken(id=$id,value=[redacted])"
}

class ParsedSecretToken(
    val id: UUID,
    val verifierHash: VerifierHash,
) {
    override fun toString(): String = "ParsedSecretToken(id=$id,value=[redacted])"
}

class GeneratedEmailVerificationToken(
    val registrationAttemptId: UUID,
    val tokenId: UUID,
    val value: String,
    val verifierHash: VerifierHash,
) {
    override fun toString(): String =
        "GeneratedEmailVerificationToken(registrationAttemptId=$registrationAttemptId,tokenId=$tokenId,value=[redacted])"
}

class ParsedEmailVerificationToken(
    val registrationAttemptId: UUID,
    val tokenId: UUID,
    val verifierHash: VerifierHash,
) {
    override fun toString(): String =
        "ParsedEmailVerificationToken(registrationAttemptId=$registrationAttemptId,tokenId=$tokenId,value=[redacted])"
}

class IssuedAccessToken(
    val value: String,
    val expiresInSeconds: Int,
) {
    override fun toString(): String = "IssuedAccessToken(value=[redacted],expiresInSeconds=$expiresInSeconds)"
}

class IssuedTokens(
    val accountId: UUID,
    val accessToken: String,
    val accessTokenExpiresIn: Int,
    val refreshToken: String,
    val refreshTokenExpiresIn: Int,
) {
    override fun toString(): String =
        "IssuedTokens(accountId=$accountId,accessToken=[redacted],refreshToken=[redacted])"
}

data class PublicJwk(
    val keyType: String,
    val use: String,
    val algorithm: String,
    val keyId: String,
    val curve: String,
    val x: String,
    val y: String,
)

data class RateLimitWindow(val attemptCount: Int, val retryAfterSeconds: Long) {
    init {
        require(attemptCount > 0) { "Rate-limit attempt count must be positive" }
        require(retryAfterSeconds > 0) { "Rate-limit retry delay must be positive" }
    }
}

data class RefreshCredentialLookup(
    val familyId: UUID,
    val verifierHash: VerifierHash,
)
