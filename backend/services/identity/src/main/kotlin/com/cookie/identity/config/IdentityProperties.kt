package com.cookie.identity.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties("cookie.identity")
class IdentityProperties(
    val accessTokenTtl: Duration = Duration.ofMinutes(15),
    val refreshFamilyTtl: Duration = Duration.ofDays(30),
    val registrationAttemptTtl: Duration = Duration.ofDays(1),
    val verificationTokenTtl: Duration = Duration.ofMinutes(30),
    val verificationResendCooldown: Duration = Duration.ofMinutes(1),
    val issuer: URI = URI.create("https://api.cookie.app"),
    val audience: String = "cookie-api",
    val jwtPrivateKeyPath: String = "",
    val jwtRetiringPublicKeyPaths: List<String> = emptyList(),
    val notificationPublicKeyPath: String = "",
    val devNotificationPrivateKeyOutputPath: String = "",
    val natsUrl: String = "nats://localhost:4222",
    val natsCredentialsPath: String = "",
    val natsTruststorePath: String = "",
    val natsTruststorePassword: String = "",
    val trustedProxyCidrs: List<String> = emptyList(),
) {
    init {
        require(
            listOf(
                accessTokenTtl,
                refreshFamilyTtl,
                registrationAttemptTtl,
                verificationTokenTtl,
                verificationResendCooldown,
            )
                .all { it.nano == 0 },
        ) { "Identity token durations must use whole-second precision" }
        require(accessTokenTtl in MIN_TOKEN_TTL..MAX_ACCESS_TOKEN_TTL) {
            "Access token TTL must be between $MIN_TOKEN_TTL and $MAX_ACCESS_TOKEN_TTL"
        }
        require(refreshFamilyTtl > accessTokenTtl && refreshFamilyTtl <= MAX_REFRESH_FAMILY_TTL) {
            "Refresh family TTL must be greater than the access token TTL and at most $MAX_REFRESH_FAMILY_TTL"
        }
        require(verificationTokenTtl in MIN_TOKEN_TTL..MAX_VERIFICATION_TOKEN_TTL) {
            "Verification token TTL must be between $MIN_TOKEN_TTL and $MAX_VERIFICATION_TOKEN_TTL"
        }
        require(registrationAttemptTtl in verificationTokenTtl..MAX_REGISTRATION_ATTEMPT_TTL) {
            "Registration attempt TTL must be at least the verification token TTL and at most " +
                "$MAX_REGISTRATION_ATTEMPT_TTL"
        }
        require(!verificationResendCooldown.isNegative && verificationResendCooldown <= verificationTokenTtl) {
            "Verification resend cooldown must be non-negative and no greater than the verification token TTL"
        }
        require(issuer.scheme.equals("https", ignoreCase = true)) { "Identity issuer must use HTTPS" }
        require(!issuer.isOpaque && !issuer.host.isNullOrBlank()) { "Identity issuer must be an absolute hierarchical URI" }
        require(issuer.userInfo == null && issuer.query == null && issuer.fragment == null) {
            "Identity issuer must not contain user info, query or fragment"
        }
        require(audience.isNotBlank() && audience.length <= MAX_AUDIENCE_LENGTH) {
            "Identity audience must contain between 1 and $MAX_AUDIENCE_LENGTH characters"
        }
        require(audience.none(Char::isWhitespace) && audience.none(Char::isISOControl)) {
            "Identity audience must not contain whitespace or control characters"
        }
        require(jwtRetiringPublicKeyPaths.none(String::isBlank)) {
            "Retiring Identity JWK paths must not contain blank entries"
        }
        require(jwtRetiringPublicKeyPaths.distinct().size == jwtRetiringPublicKeyPaths.size) {
            "Retiring Identity JWK paths must be unique"
        }
        require(notificationPublicKeyPath.isBlank() || devNotificationPrivateKeyOutputPath.isBlank()) {
            "Notification public-key input and development private-key output paths are mutually exclusive"
        }
        require(listOf(
            jwtPrivateKeyPath,
            notificationPublicKeyPath,
            devNotificationPrivateKeyOutputPath,
            natsCredentialsPath,
            natsTruststorePath,
        )
            .none { '\u0000' in it }) {
            "Configured file paths must not contain NUL characters"
        }
        require(trustedProxyCidrs.none(String::isBlank)) {
            "Trusted proxy CIDRs must not contain blank entries"
        }
        require(trustedProxyCidrs.distinct().size == trustedProxyCidrs.size) {
            "Trusted proxy CIDRs must be unique"
        }
        validateNatsUrl(natsUrl)
    }

    override fun toString(): String =
        "IdentityProperties(accessTokenTtl=$accessTokenTtl,refreshFamilyTtl=$refreshFamilyTtl," +
            "registrationAttemptTtl=$registrationAttemptTtl,verificationTokenTtl=$verificationTokenTtl," +
            "verificationResendCooldown=$verificationResendCooldown," +
            "issuer=$issuer,audience=$audience,natsUrl=[redacted],trustedProxyCidrs=${trustedProxyCidrs.size})"

    private fun validateNatsUrl(rawUrl: String) {
        val uri = runCatching { URI.create(rawUrl) }
            .getOrElse { throw IllegalArgumentException("NATS URL must be a valid URI", it) }
        require(uri.scheme?.lowercase() in NATS_SCHEMES && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "NATS URL must use nats, tls, ws or wss and contain a host"
        }
    }

    companion object {
        private val MIN_TOKEN_TTL: Duration = Duration.ofSeconds(1)
        private val MAX_ACCESS_TOKEN_TTL: Duration = Duration.ofHours(24)
        private val MAX_REFRESH_FAMILY_TTL: Duration = Duration.ofDays(365)
        private val MAX_REGISTRATION_ATTEMPT_TTL: Duration = Duration.ofDays(7)
        private val MAX_VERIFICATION_TOKEN_TTL: Duration = Duration.ofHours(24)
        private const val MAX_AUDIENCE_LENGTH = 255
        private val NATS_SCHEMES = setOf("nats", "tls", "ws", "wss")
    }
}
