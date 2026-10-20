package com.cookie.notification.security

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jwt.SignedJWT
import org.springframework.scheduling.annotation.Scheduled
import java.net.URI
import java.net.http.HttpRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

class InvalidAccessTokenException : RuntimeException("Invalid access token")
class AuthenticationUnavailableException : RuntimeException("Authentication key source unavailable")

fun interface JwksSource { fun fetch(): String }

class HttpJwksSource(private val uri: URI, private val http: BoundedHttpExchange) : JwksSource {
    override fun fetch(): String {
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build()
        val response = http.send(request, Duration.ofSeconds(3))
        check(response.statusCode() == 200) { "JWKS request failed" }
        return response.body().toString(Charsets.UTF_8)
    }
}

/** The configured issuer determines the key URL; token-controlled jku/x5u/jwk are never used. */
class RemoteJwksAccessTokenVerifier(
    private val source: JwksSource,
    private val issuer: String,
    private val audience: String,
    private val clock: Clock,
) {
    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var lastRefreshSucceeded = false
    private var lastRefreshAttempt: Instant? = null

    fun verify(rawToken: String): UUID {
        val token = try {
            require(rawToken.length in 1..16_384)
            SignedJWT.parse(rawToken).also {
                require(it.header.algorithm == JWSAlgorithm.ES256 && it.header.type == JOSEObjectType("at+jwt"))
                require(it.header.criticalParams.isNullOrEmpty())
                require(it.header.jwk == null && it.header.getJWKURL() == null && it.header.x509CertURL == null)
                require(!it.header.keyID.isNullOrBlank() && it.header.keyID.length <= 128)
            }
        } catch (_: Exception) { throw InvalidAccessTokenException() }
        val now = clock.instant()
        var current = snapshot
        if (current == null || !current.expiresAt.isAfter(now) || token.header.keyID !in current.keys) {
            refresh()
            current = snapshot
        }
        if (current == null || !current.expiresAt.isAfter(now)) throw AuthenticationUnavailableException()
        val key = current.keys[token.header.keyID] ?: if (lastRefreshSucceeded) throw InvalidAccessTokenException()
            else throw AuthenticationUnavailableException()
        return try {
            require(token.verify(ECDSAVerifier(key.toECPublicKey())))
            val claims = token.jwtClaimsSet
            require(claims.issuer == issuer && claims.audience == listOf(audience))
            val issued = requireNotNull(claims.issueTime).toInstant()
            val expires = requireNotNull(claims.expirationTime).toInstant()
            require(!issued.isAfter(now) && expires.isAfter(now) && expires.isAfter(issued))
            require(Duration.between(issued, expires) <= Duration.ofHours(24))
            claims.notBeforeTime?.toInstant()?.let { require(!it.isAfter(now)) }
            uuidV7(requireNotNull(claims.getStringClaim("sid")))
            uuidV7(requireNotNull(claims.getJWTID()))
            uuidV7(requireNotNull(claims.subject))
        } catch (_: Exception) { throw InvalidAccessTokenException() }
    }

    fun ready(): Boolean = snapshot?.expiresAt?.isAfter(clock.instant()) == true

    @Scheduled(fixedDelay = 240_000, initialDelay = 0)
    fun refreshScheduled() { refresh() }

    @Synchronized
    fun refresh() {
        val now = clock.instant()
        if (lastRefreshAttempt?.plusSeconds(30)?.isAfter(now) == true) return
        lastRefreshAttempt = now
        try {
            val parsed = JWKSet.parse(source.fetch()).keys
            require(parsed.isNotEmpty() && parsed.size <= 16)
            val keys = parsed.map { raw ->
                require(raw is ECKey && !raw.isPrivate && raw.curve == Curve.P_256)
                require(raw.algorithm == JWSAlgorithm.ES256 && raw.keyUse == KeyUse.SIGNATURE)
                require(!raw.keyID.isNullOrBlank() && raw.keyID.length <= 128)
                raw.toECPublicKey()
                raw
            }
            require(keys.map { it.keyID }.distinct().size == keys.size)
            snapshot = Snapshot(keys.associateBy { it.keyID }, now.plusSeconds(300))
            lastRefreshSucceeded = true
        } catch (_: Exception) {
            lastRefreshSucceeded = false
            // Preserve only a still-fresh verified cache; callers fail closed at its expiry.
        }
    }

    private fun uuidV7(raw: String): UUID = UUID.fromString(raw).also {
        require(it.toString().equals(raw, ignoreCase = true) && it.version() == 7 && it.variant() == 2)
    }
    private data class Snapshot(val keys: Map<String, ECKey>, val expiresAt: Instant)
}
