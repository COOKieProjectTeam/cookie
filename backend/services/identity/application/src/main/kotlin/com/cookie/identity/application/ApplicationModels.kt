package com.cookie.identity.application

import java.time.Duration
import java.time.Instant
import java.util.UUID

data class IdentityPolicy(
    val refreshFamilyTtl: Duration,
    val verificationTokenTtl: Duration,
    val verificationResendCooldown: Duration,
) {
    init {
        require(refreshFamilyTtl.isPositive()) { "Refresh family TTL must be positive" }
        require(verificationTokenTtl.isPositive()) { "Verification token TTL must be positive" }
        require(!verificationResendCooldown.isNegative) { "Verification resend cooldown must not be negative" }
        require(refreshFamilyTtl.seconds <= Int.MAX_VALUE) { "Refresh family TTL is too large" }
    }
}

class GeneratedSecretToken(
    val id: UUID,
    val value: String,
    val verifierHash: String,
) {
    override fun toString(): String = "GeneratedSecretToken(id=$id,value=[redacted])"
}

class ParsedSecretToken(
    val id: UUID,
    val verifierHash: String,
) {
    override fun toString(): String = "ParsedSecretToken(id=$id,value=[redacted])"
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
    val newUser: Boolean,
) {
    override fun toString(): String =
        "IssuedTokens(accountId=$accountId,accessToken=[redacted],refreshToken=[redacted],newUser=$newUser)"
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

data class RateLimitWindow(val attemptCount: Int, val expiresAt: Instant)
