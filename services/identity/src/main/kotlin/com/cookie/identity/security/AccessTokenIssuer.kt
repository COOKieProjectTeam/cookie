package com.cookie.identity.security

import com.cookie.identity.config.IdentityProperties
import com.cookie.identity.domain.UuidV7Generator
import com.cookie.identity.generated.model.JsonWebKey
import com.cookie.identity.generated.model.JsonWebKeySet
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.Date
import java.util.UUID

@Component
class AccessTokenIssuer(
    private val properties: IdentityProperties,
    private val keyMaterial: KeyMaterial,
    private val uuidV7Generator: UuidV7Generator,
    private val clock: Clock,
) {
    fun issue(accountId: UUID, sessionId: UUID): String {
        val now = clock.instant()
        val claims = JWTClaimsSet.Builder()
            .issuer(properties.issuer)
            .audience(properties.audience)
            .subject(accountId.toString())
            .claim("sid", sessionId.toString())
            .jwtID(uuidV7Generator.next().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(properties.accessTokenTtl)))
            .build()
        return SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(JOSEObjectType("at+jwt"))
                .keyID(keyMaterial.signingKey.keyID)
                .build(),
            claims,
        ).apply { sign(ECDSASigner(keyMaterial.signingKey)) }.serialize()
    }

    fun jwks(): JsonWebKeySet {
        return JsonWebKeySet(
            propertyKeys = (listOf(keyMaterial.signingKey.toPublicJWK()) + keyMaterial.retiringSigningKeys)
                .distinctBy { it.keyID }
                .map { publicKey ->
                    JsonWebKey(
                        kty = JsonWebKey.Kty.EC,
                        use = JsonWebKey.Use.SIG,
                        alg = JsonWebKey.Alg.ES256,
                        kid = publicKey.keyID,
                        crv = JsonWebKey.Crv.P_256,
                        x = publicKey.x.toString(),
                        y = publicKey.y.toString(),
                    )
                },
        )
    }

    fun expiresInSeconds(): Int = properties.accessTokenTtl.seconds.toInt()

    fun expiresAt(now: Instant = clock.instant()): Instant = now.plus(properties.accessTokenTtl)
}
