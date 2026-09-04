package com.cookie.identity.security

import com.cookie.identity.config.IdentityProperties
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class NimbusAccessTokenProviderTest {
    @Test
    fun `issues minimal es256 access jwt and application jwks`() {
        val now = Instant.parse("2026-08-19T12:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val ids = UuidV7IdGenerator(clock, SecureRandom())
        val properties = IdentityProperties(accessTokenTtl = Duration.ofMinutes(15))
        val keyMaterial = KeyMaterialConfiguration().developmentKeyMaterial(properties, ids)
        val provider = NimbusAccessTokenProvider(properties, keyMaterial, ids)
        val accountId = UUID.fromString("0198c4a5-68b5-7def-8123-456789abcdef")
        val sessionId = UUID.fromString("0198c4a5-68b5-7abc-9234-56789abcdef0")

        val issued = provider.issue(accountId, sessionId, now)
        val signed = SignedJWT.parse(issued.value)

        assertThat(issued.expiresInSeconds).isEqualTo(900)
        assertThat(issued.toString()).doesNotContain(issued.value)
        assertThat(signed.header.algorithm.name).isEqualTo("ES256")
        assertThat(signed.header.type.type).isEqualTo("at+jwt")
        assertThat(signed.verify(ECDSAVerifier(keyMaterial.signingKey.toECPublicKey()))).isTrue()
        assertThat(signed.jwtClaimsSet.subject).isEqualTo(accountId.toString())
        assertThat(signed.jwtClaimsSet.getStringClaim("sid")).isEqualTo(sessionId.toString())
        assertThat(signed.jwtClaimsSet.issuer).isEqualTo("https://api.cookie.app")
        assertThat(signed.jwtClaimsSet.audience).containsExactly("cookie-api")
        assertThat(signed.jwtClaimsSet.expirationTime.toInstant()).isEqualTo(now.plusSeconds(900))
        assertThat(signed.jwtClaimsSet.claims).doesNotContainKeys("email", "roles")

        val jwk = provider.publicKeys().single()
        assertThat(jwk.keyId).isEqualTo(signed.header.keyID)
        assertThat(jwk.algorithm).isEqualTo("ES256")
        assertThat(jwk.keyType).isEqualTo("EC")
        assertThat(jwk.use).isEqualTo("sig")
        assertThat(jwk.curve).isEqualTo("P-256")
    }
}
