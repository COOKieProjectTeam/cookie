package com.cookie.notification.security

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

class RemoteJwksAccessTokenVerifierTest {
    private val clock = MutableClock(Instant.parse("2026-09-17T12:00:00Z"))
    private val account = UUID.fromString("0198c4a5-68b5-7def-8123-456789abcdef")
    private val key = signingKey("one")

    @Test fun `accepts only the Identity token profile and does not fetch token supplied URLs`() {
        var requests = 0
        val verifier = verifier { requests++; publicSet(key) }
        assertThat(verifier.verify(token(key))).isEqualTo(account)
        assertThatThrownBy { verifier.verify(token(key, audience = "another-service")) }.isInstanceOf(InvalidAccessTokenException::class.java)
        assertThatThrownBy { verifier.verify(token(key, remoteUrl = URI.create("https://attacker.invalid/jwks"))) }
            .isInstanceOf(InvalidAccessTokenException::class.java)
        assertThat(requests).isEqualTo(1)
    }

    @Test fun `outage can use a fresh key cache but cannot extend its lifetime`() {
        var unavailable = false
        val verifier = verifier { if (unavailable) error("offline") else publicSet(key) }
        val token = token(key)
        assertThat(verifier.verify(token)).isEqualTo(account)
        unavailable = true
        clock.now = clock.now.plusSeconds(299)
        assertThat(verifier.verify(token)).isEqualTo(account)
        clock.now = clock.now.plusSeconds(2)
        assertThatThrownBy { verifier.verify(token) }.isInstanceOf(AuthenticationUnavailableException::class.java)
        assertThat(verifier.ready()).isFalse()
    }

    @Test fun `new signing key refreshes with bounded unknown kid traffic`() {
        val rotated = signingKey("two")
        var requests = 0
        var keys = publicSet(key)
        val verifier = verifier { requests++; keys }
        verifier.verify(token(key))
        repeat(20) { assertThatThrownBy { verifier.verify(token(rotated)) }.isInstanceOf(InvalidAccessTokenException::class.java) }
        assertThat(requests).isEqualTo(1)
        keys = publicSet(key, rotated)
        clock.now = clock.now.plusSeconds(31)
        assertThat(verifier.verify(token(rotated))).isEqualTo(account)
        assertThat(requests).isEqualTo(2)
    }

    @Test fun `wrong curve private JWKS keys and duplicate key ids are rejected`() {
        val p384 = ECKeyGenerator(Curve.P_384).keyID(key.keyID).keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.ES384).generate()
        val wrongCurve = verifier { publicSet(p384) }
        assertThatThrownBy { wrongCurve.verify(token(key)) }.isInstanceOf(AuthenticationUnavailableException::class.java)
        val privateSource = verifier { JWKSet(key).toString(false) }
        assertThatThrownBy { privateSource.verify(token(key)) }.isInstanceOf(AuthenticationUnavailableException::class.java)
        val duplicate = verifier { JWKSet(listOf(key.toPublicJWK(), key.toPublicJWK())).toString() }
        assertThatThrownBy { duplicate.verify(token(key)) }.isInstanceOf(AuthenticationUnavailableException::class.java)
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = ["wrong issuer", "wrong signature", "missing sub", "invalid sub", "missing sid",
        "invalid sid", "missing jti", "invalid jti", "wrong typ", "wrong alg", "expired", "future iat",
        "excessive lifetime", "future nbf", "missing exp", "missing iat"])
    fun `rejects invalid access token profile even when key id is trusted`(violation: String) {
        val verifier = verifier { publicSet(key) }
        // Prove the trusted baseline before changing exactly one profile property.
        assertThat(verifier.verify(token(key))).isEqualTo(account)
        val claims = claims()
        var signing = key
        var algorithm = JWSAlgorithm.ES256
        var type = JOSEObjectType("at+jwt")
        when (violation) {
            "wrong issuer" -> claims.issuer("https://other-identity.example")
            "wrong signature" -> signing = signingKey(key.keyID)
            "missing sub" -> claims.subject(null)
            "invalid sub" -> claims.subject(UUID.randomUUID().toString())
            "missing sid" -> claims.claim("sid", null)
            "invalid sid" -> claims.claim("sid", "not-a-session-id")
            "missing jti" -> claims.jwtID(null)
            "invalid jti" -> claims.jwtID(UUID.randomUUID().toString())
            "wrong typ" -> type = JOSEObjectType.JWT
            "wrong alg" -> {
                algorithm = JWSAlgorithm.ES384
                signing = ECKeyGenerator(Curve.P_384).keyID(key.keyID).generate()
            }
            "expired" -> claims.issueTime(Date.from(clock.now.minusSeconds(900)))
                .expirationTime(Date.from(clock.now))
            "future iat" -> claims.issueTime(Date.from(clock.now.plusSeconds(1)))
            "excessive lifetime" -> claims.expirationTime(Date.from(clock.now.plusSeconds(86_401)))
            "future nbf" -> claims.notBeforeTime(Date.from(clock.now.plusSeconds(1)))
            "missing exp" -> claims.expirationTime(null)
            "missing iat" -> claims.issueTime(null)
            else -> error("Unknown test case")
        }
        val invalid = SignedJWT(JWSHeader.Builder(algorithm).type(type).keyID(key.keyID).build(), claims.build())
            .apply { sign(ECDSASigner(signing)) }.serialize()
        assertThatThrownBy { verifier.verify(invalid) }.isInstanceOf(InvalidAccessTokenException::class.java)
    }

    @Test fun `maximum issuer key id and full rotation set remain verifiable`() {
        val keys=listOf(signingKey("k".repeat(128)))+(1..15).map { signingKey("retiring-$it") }
        val verifier=verifier { publicSet(*keys.toTypedArray()) }
        for(key in keys) assertThat(verifier.verify(token(key))).isEqualTo(account)
    }

    @Test fun `oversized key id and set fail closed`() {
        val oversized=signingKey("k".repeat(129))
        var requests=0
        val invalidHeader=verifier { requests++;publicSet(oversized) }
        assertThatThrownBy { invalidHeader.verify(token(oversized)) }.isInstanceOf(InvalidAccessTokenException::class.java)
        assertThat(requests).isZero()
        val tooMany=verifier { publicSet(key, *(1..16).map { signingKey("retiring-$it") }.toTypedArray()) }
        assertThatThrownBy { tooMany.verify(token(key)) }.isInstanceOf(AuthenticationUnavailableException::class.java)
    }

    private fun verifier(source: JwksSource) = RemoteJwksAccessTokenVerifier(source, "https://api.cookie.app", "cookie-api", clock)
    private fun publicSet(vararg keys: ECKey): String = JWKSet(keys.map { it.toPublicJWK() }).toString()
    private fun signingKey(id: String) = ECKeyGenerator(Curve.P_256).keyID(id).keyUse(KeyUse.SIGNATURE)
        .algorithm(JWSAlgorithm.ES256).generate()
    private fun claims(audience: String = "cookie-api") = JWTClaimsSet.Builder()
        .issuer("https://api.cookie.app").audience(audience).subject(account.toString())
        .claim("sid", account.toString()).jwtID(account.toString()).issueTime(Date.from(clock.now))
        .expirationTime(Date.from(clock.now.plusSeconds(900)))
    private fun token(signing: ECKey, audience: String = "cookie-api", remoteUrl: URI? = null): String = SignedJWT(
        JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("at+jwt")).keyID(signing.keyID).jwkURL(remoteUrl).build(),
        claims(audience).build(),
    ).apply { sign(ECDSASigner(signing)) }.serialize()
}

private class MutableClock(var now: Instant) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
