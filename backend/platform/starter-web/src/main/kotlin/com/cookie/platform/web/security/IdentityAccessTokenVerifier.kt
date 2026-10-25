package com.cookie.platform.web.security

import com.cookie.platform.web.BoundedHttpExchange
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import java.net.http.HttpRequest
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class InvalidIdentityAccessToken : RuntimeException("Invalid access token")
class IdentityAuthenticationUnavailable : RuntimeException("Authentication key source unavailable")

/** Fixed configured origin, bounded response and deadline; never accepts token-controlled URLs. */
class BoundedJwksSource(private val uri: URI, private val http: BoundedHttpExchange) {
    fun fetch(): String {
        val timeout = Duration.ofSeconds(3)
        val response = http.send(HttpRequest.newBuilder(uri).timeout(timeout).GET().build(), timeout)
        check(response.statusCode() == 200) { "JWKS request failed" }
        return response.body().toString(Charsets.UTF_8)
    }
}

/** Shared Identity token policy. Refresh ownership never makes another request wait on network I/O. */
class IdentityAccessTokenVerifier(
    private val source: () -> String,
    private val issuer: String,
    private val audience: String,
    private val clock: Clock,
    private val maximumTokenLength: Int = 16_384,
    private val rejectWhitespace: Boolean = false,
) {
    private data class Snapshot(val keys: Map<String, ECPublicKey>, val expiresAt: Instant)
    private data class State(
        val snapshot: Snapshot? = null,
        val attemptedAt: Instant? = null,
        val refreshing: Boolean = false,
        val succeeded: Boolean = false,
    )
    private val state = AtomicReference(State())

    fun ready(): Boolean = state.get().snapshot?.expiresAt?.isAfter(clock.instant()) == true

    fun refresh() {
        val claimed: State
        while (true) {
            val before = state.get()
            val now = clock.instant()
            if (before.refreshing || before.attemptedAt?.plusSeconds(30)?.isAfter(now) == true) return
            val next = before.copy(attemptedAt = now, refreshing = true)
            if (state.compareAndSet(before, next)) {
                claimed = next
                break
            }
        }
        var completed = claimed.copy(succeeded = false)
        try {
            val parsed = JWKSet.parse(source()).keys
            require(parsed.size in 1..16)
            val keys = parsed.map { raw ->
                require(raw is ECKey && !raw.isPrivate && raw.curve == Curve.P_256)
                require(raw.algorithm == JWSAlgorithm.ES256 && raw.keyUse == KeyUse.SIGNATURE)
                require(!raw.keyID.isNullOrBlank() && raw.keyID.length <= 128)
                raw.keyID to raw.toECPublicKey()
            }
            require(keys.map { it.first }.distinct().size == keys.size)
            // A slow fetch cannot extend the original attempt's trust window.
            completed = claimed.copy(snapshot = Snapshot(keys.toMap(), claimed.attemptedAt!!.plusSeconds(300)), succeeded = true)
        } catch (_: Exception) {
            // An outage retains only the original snapshot and expiry.
        } finally {
            state.set(completed.copy(refreshing = false))
        }
    }

    fun verify(rawToken: String): UUID {
        val token = try {
            require(rawToken.length in 1..maximumTokenLength)
            require(!rejectWhitespace || rawToken.none(Char::isWhitespace))
            SignedJWT.parse(rawToken).also {
                require(it.header.algorithm == JWSAlgorithm.ES256 && it.header.type == JOSEObjectType("at+jwt"))
                require(it.header.criticalParams.isNullOrEmpty())
                require(it.header.jwk == null && it.header.getJWKURL() == null && it.header.x509CertURL == null)
                require(!it.header.keyID.isNullOrBlank() && it.header.keyID.length <= 128)
            }
        } catch (_: Exception) { throw InvalidIdentityAccessToken() }
        val initial = state.get().snapshot
        if (initial == null || !initial.expiresAt.isAfter(clock.instant()) || token.header.keyID !in initial.keys) refresh()
        val current = state.get()
        // Refresh may perform I/O: time checks must use the time after it returns.
        val now = clock.instant()
        val snapshot = current.snapshot?.takeIf { it.expiresAt.isAfter(now) } ?: throw IdentityAuthenticationUnavailable()
        val key = snapshot.keys[token.header.keyID] ?: if (current.refreshing || !current.succeeded) {
            throw IdentityAuthenticationUnavailable()
        } else throw InvalidIdentityAccessToken()
        return try {
            require(token.verify(ECDSAVerifier(key)))
            val claims = token.jwtClaimsSet
            require(claims.issuer == issuer && claims.audience == listOf(audience))
            val issued = requireNotNull(claims.issueTime).toInstant()
            val expires = requireNotNull(claims.expirationTime).toInstant()
            val checkedAt = clock.instant()
            if (!snapshot.expiresAt.isAfter(checkedAt)) throw IdentityAuthenticationUnavailable()
            require(!issued.isAfter(checkedAt) && expires.isAfter(checkedAt) && expires.isAfter(issued))
            require(Duration.between(issued, expires) <= Duration.ofHours(24))
            claims.notBeforeTime?.toInstant()?.let { require(!it.isAfter(checkedAt)) }
            uuidV7(requireNotNull(claims.getStringClaim("sid")))
            uuidV7(requireNotNull(claims.getJWTID()))
            uuidV7(requireNotNull(claims.subject))
        } catch (unavailable: IdentityAuthenticationUnavailable) {
            throw unavailable
        } catch (_: Exception) { throw InvalidIdentityAccessToken() }
    }

    private fun uuidV7(raw: String): UUID = UUID.fromString(raw).also {
        require(it.toString().equals(raw, ignoreCase = true) && it.version() == 7 && it.variant() == 2)
    }
}
