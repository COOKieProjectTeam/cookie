package com.cookie.identity.security

import com.cookie.identity.config.IdentityProperties
import com.cookie.identity.domain.UuidV7Generator
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

class AccessTokenIssuerTest {
    @Test
    fun `issues minimal es256 access jwt and public jwks`() {
        val now = Instant.parse("2026-08-19T12:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val ids = UuidV7Generator(clock, SecureRandom())
        val properties = IdentityProperties(accessTokenTtl = Duration.ofMinutes(15))
        val keyMaterial = KeyMaterialConfiguration().developmentKeyMaterial(properties, ids)
        val issuer = AccessTokenIssuer(properties, keyMaterial, ids, clock)
        val accountId = UUID.fromString("0198c4a5-68b5-7def-8123-456789abcdef")
        val sessionId = UUID.fromString("0198c4a5-68b5-7abc-9234-56789abcdef0")

        val signed = SignedJWT.parse(issuer.issue(accountId, sessionId))

        assertThat(signed.header.algorithm.name).isEqualTo("ES256")
        assertThat(signed.header.type.type).isEqualTo("at+jwt")
        assertThat(signed.verify(ECDSAVerifier(keyMaterial.signingKey.toECPublicKey()))).isTrue()
        assertThat(signed.jwtClaimsSet.subject).isEqualTo(accountId.toString())
        assertThat(signed.jwtClaimsSet.getStringClaim("sid")).isEqualTo(sessionId.toString())
        assertThat(signed.jwtClaimsSet.issuer).isEqualTo("https://api.cookie.app")
        assertThat(signed.jwtClaimsSet.audience).containsExactly("cookie-api")
        assertThat(signed.jwtClaimsSet.expirationTime.toInstant()).isEqualTo(now.plusSeconds(900))
        assertThat(signed.jwtClaimsSet.claims).doesNotContainKeys("email", "roles")

        val jwks = issuer.jwks().propertyKeys.single()
        assertThat(jwks.kid).isEqualTo(signed.header.keyID)
        assertThat(jwks.alg.value).isEqualTo("ES256")
    }
}
