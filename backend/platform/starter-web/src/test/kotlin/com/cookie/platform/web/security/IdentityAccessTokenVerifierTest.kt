package com.cookie.platform.web.security

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
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class IdentityAccessTokenVerifierTest {
    private val initial = Instant.parse("2026-10-23T12:00:00Z")
    private val clock = MutableClock(initial)
    private val account = UUID.fromString("019a89b1-1111-7111-8111-111111111111")
    private val key = signingKey("one")

    @Test fun `slow refresh has one owner while known keys verify and missing keys fail promptly`() {
        val rotated = signingKey("two")
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val verifier = verifier {
            if (calls.incrementAndGet() > 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            publicSet(key, rotated)
        }
        verifier.verify(token(key))
        val unknown = signingKey("unknown")
        clock.now = initial.plusSeconds(31)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val refreshing = pool.submit { verifier.refresh() }
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(pool.submit<UUID> { verifier.verify(token(key)) }.get(500, TimeUnit.MILLISECONDS)).isEqualTo(account)
            repeat(20) {
                val failure = pool.submit<Throwable?> { runCatching { verifier.verify(token(unknown)) }.exceptionOrNull() }
                assertThat(failure.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(IdentityAuthenticationUnavailable::class.java)
            }
            assertThat(calls.get()).isEqualTo(2)
            release.countDown()
            refreshing.get(2, TimeUnit.SECONDS)
            assertThat(verifier.verify(token(rotated))).isEqualTo(account)
            assertThatThrownBy { verifier.verify(token(unknown)) }.isInstanceOf(InvalidIdentityAccessToken::class.java)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `cold concurrent requests fail promptly instead of queuing behind discovery`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val verifier = verifier {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            publicSet(key)
        }
        val signed = token(key)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<UUID> { verifier.verify(signed) }
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            val next = pool.submit<Throwable?> { runCatching { verifier.verify(signed) }.exceptionOrNull() }
            assertThat(next.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(IdentityAuthenticationUnavailable::class.java)
            assertThat(calls.get()).isEqualTo(1)
            release.countDown()
            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo(account)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `expiry is evaluated after a failed refresh and outages do not extend cached keys`() {
        var available = true
        var calls = 0
        val verifier = verifier {
            calls++
            if (!available) {
                clock.now = clock.now.plusSeconds(2)
                error("offline")
            }
            publicSet(key)
        }
        val signed = token(key)
        verifier.verify(signed)
        available = false
        clock.now = initial.plusSeconds(299)
        val unknown = signingKey("unknown")
        assertThatThrownBy { verifier.verify(token(unknown)) }.isInstanceOf(IdentityAuthenticationUnavailable::class.java)
        assertThatThrownBy { verifier.verify(signed) }.isInstanceOf(IdentityAuthenticationUnavailable::class.java)
        assertThat(verifier.ready()).isFalse()
        assertThat(calls).isEqualTo(2)
        available = true
        clock.now = initial.plusSeconds(330)
        assertThat(verifier.verify(signed)).isEqualTo(account)
        assertThat(calls).isEqualTo(3)
    }

    @Test fun `token that expires during discovery is rejected`() {
        val verifier = verifier {
            clock.now = initial.plusSeconds(2)
            publicSet(key)
        }
        assertThatThrownBy { verifier.verify(token(key, initial.plusSeconds(1))) }
            .isInstanceOf(InvalidIdentityAccessToken::class.java)
    }

    private fun verifier(source: () -> String) = IdentityAccessTokenVerifier(source, "https://identity.example", "cookie-api", clock)
    private fun publicSet(vararg keys: ECKey) = JWKSet(keys.map { it.toPublicJWK() }).toString()
    private fun signingKey(id: String) = ECKeyGenerator(Curve.P_256).algorithm(JWSAlgorithm.ES256)
        .keyUse(KeyUse.SIGNATURE).keyID(id).generate()
    private fun token(signing: ECKey, expires: Instant = initial.plusSeconds(900)): String = SignedJWT(
        JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("at+jwt")).keyID(signing.keyID).build(),
        JWTClaimsSet.Builder().issuer("https://identity.example").audience("cookie-api").subject(account.toString())
            .claim("sid", account.toString()).jwtID(account.toString()).issueTime(Date.from(initial))
            .expirationTime(Date.from(expires)).build(),
    ).apply { sign(ECDSASigner(signing)) }.serialize()
}

private class MutableClock(@Volatile var now: Instant) : Clock() {
    override fun instant() = now
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
