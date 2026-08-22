package com.cookie.identity.security

import com.cookie.identity.application.IssuedAccessToken
import com.cookie.identity.application.PublicJwk
import com.cookie.identity.application.ports.AccessTokenProvider
import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.config.IdentityProperties
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.Date
import java.util.UUID

@Component
class NimbusAccessTokenProvider(
    private val properties: IdentityProperties,
    private val keyMaterial: KeyMaterial,
    private val idGenerator: IdGenerator,
    private val clock: Clock,
) : AccessTokenProvider {
    override fun issue(accountId: UUID, sessionId: UUID): IssuedAccessToken {
        val now = clock.instant()
        val claims = JWTClaimsSet.Builder()
            .issuer(properties.issuer.toASCIIString())
            .audience(properties.audience)
            .subject(accountId.toString())
            .claim("sid", sessionId.toString())
            .jwtID(idGenerator.next().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(properties.accessTokenTtl)))
            .build()
        val compactJwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(ACCESS_TOKEN_TYPE)
                .keyID(keyMaterial.signingKey.keyID)
                .build(),
            claims,
        ).apply { sign(ECDSASigner(keyMaterial.signingKey)) }
            .serialize()
        return IssuedAccessToken(compactJwt, properties.accessTokenTtl.seconds.toInt())
    }

    override fun publicKeys(): List<PublicJwk> =
        (listOf(keyMaterial.signingKey.toPublicJWK()) + keyMaterial.retiringSigningKeys)
            .map { publicKey ->
                PublicJwk(
                    keyType = "EC",
                    use = "sig",
                    algorithm = "ES256",
                    keyId = requireNotNull(publicKey.keyID),
                    curve = "P-256",
                    x = publicKey.x.toString(),
                    y = publicKey.y.toString(),
                )
            }

    companion object {
        private val ACCESS_TOKEN_TYPE = JOSEObjectType("at+jwt")
    }
}
