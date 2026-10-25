package com.cookie.user

import com.cookie.user.security.IdentityTokens
import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.*
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.*
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IdentityTokensTest {
    private val key=ECKeyGenerator(Curve.P_256).algorithm(JWSAlgorithm.ES256).keyUse(KeyUse.SIGNATURE).keyID("key").generate()
    private val now=Instant.parse("2026-10-23T10:00:00Z")
    private val account=UUID.fromString("019a89b1-1111-7111-8111-111111111111")
    private class MutableClock(var value:Instant):Clock() {
        override fun instant()=value
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=this
    }
    private fun token(issuer:String="https://identity.example",audience:String="cookie-api",type:String="at+jwt",subject:String=account.toString(),
                      expires:Instant=now.plusSeconds(600),issued:Instant=now,signer:ECKey=key)=SignedJWT(
        JWSHeader.Builder(JWSAlgorithm.ES256).keyID("key").type(JOSEObjectType(type)).build(),
        JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject).jwtID(account.toString()).claim("sid",account.toString())
            .issueTime(Date.from(issued)).expirationTime(Date.from(expires)).build()).also { it.sign(ECDSASigner(signer)) }.serialize()
    @Test fun `verifies exact Identity token profile and rejects forged context`() {
        val verifier=IdentityTokens({ JWKSet(key.toPublicJWK()).toString() },"https://identity.example","cookie-api",Clock.fixed(now,ZoneOffset.UTC))
        assertThat(verifier.verify(token())).isEqualTo(account)
        val wrong=ECKeyGenerator(Curve.P_256).generate()
        listOf(token(issuer="https://other.example"),token(audience="other"),token(type="JWT"),token(subject=UUID.randomUUID().toString()),
            token(expires=now),token(issued=now.plusSeconds(1)),token(signer=wrong),"malformed").forEach {
            assertThatThrownBy { verifier.verify(it) }.hasMessage("INVALID_ACCESS_TOKEN")
        }
    }
    @Test fun `fresh keys survive source outage but expired cache fails closed and recovers`() {
        val clock=MutableClock(now);var available=true;var fetches=0
        val verifier=IdentityTokens({ fetches++;check(available);JWKSet(key.toPublicJWK()).toString() },"https://identity.example","cookie-api",clock)
        assertThat(verifier.verify(token())).isEqualTo(account)
        available=false;clock.value=now.plusSeconds(240);verifier.refresh()
        assertThat(verifier.verify(token())).isEqualTo(account)
        clock.value=now.plusSeconds(301)
        assertThatThrownBy { verifier.verify(token()) }.hasMessage("AUTHENTICATION_UNAVAILABLE")
        val failedFetches=fetches
        repeat(100) { runCatching { verifier.verify(token()) } }
        assertThat(fetches).isEqualTo(failedFetches)
        available=true;clock.value=now.plusSeconds(332)
        assertThat(verifier.verify(token())).isEqualTo(account)
    }
    @Test fun `private and duplicate discovery keys are never trusted`() {
        listOf(JWKSet(key).toString(false),JWKSet(listOf(key.toPublicJWK(),key.toPublicJWK())).toString()).forEach { json ->
            val verifier=IdentityTokens({ json },"https://identity.example","cookie-api",Clock.fixed(now,ZoneOffset.UTC))
            assertThatThrownBy { verifier.verify(token()) }.hasMessage("AUTHENTICATION_UNAVAILABLE")
        }
    }
    @Test fun `a slow discovery does not block other request threads`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val verifier=IdentityTokens({
            entered.countDown();check(release.await(5,TimeUnit.SECONDS))
            JWKSet(key.toPublicJWK()).toString()
        },"https://identity.example","cookie-api",Clock.fixed(now,ZoneOffset.UTC))
        val signed=token();val pool=Executors.newFixedThreadPool(2)
        try {
            val first=pool.submit<UUID> { verifier.verify(signed) }
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue()
            val second=pool.submit<Throwable?> { runCatching { verifier.verify(signed) }.exceptionOrNull() }
            assertThat(second.get(500,TimeUnit.MILLISECONDS)).hasMessage("AUTHENTICATION_UNAVAILABLE")
            release.countDown()
            assertThat(first.get(2,TimeUnit.SECONDS)).isEqualTo(account)
        } finally { release.countDown();pool.shutdownNow() }
    }
    @Test fun `a token expiring during discovery is rejected`() {
        val clock=MutableClock(now)
        val verifier=IdentityTokens({
            clock.value=now.plusSeconds(2);JWKSet(key.toPublicJWK()).toString()
        },"https://identity.example","cookie-api",clock)
        assertThatThrownBy { verifier.verify(token(expires=now.plusSeconds(1))) }.hasMessage("INVALID_ACCESS_TOKEN")
    }
}
