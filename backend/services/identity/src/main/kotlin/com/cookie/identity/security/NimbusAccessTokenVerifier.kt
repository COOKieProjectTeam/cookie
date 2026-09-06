package com.cookie.identity.security

import com.cookie.identity.application.InvalidAccessTokenException
import com.cookie.identity.application.VerifiedAccessToken
import com.cookie.identity.application.ports.AccessTokenVerifier
import com.cookie.identity.config.IdentityProperties
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class NimbusAccessTokenVerifier(
    private val properties: IdentityProperties,
    keyMaterial: KeyMaterial,
) : AccessTokenVerifier {
    private val verificationKeys: Map<String, ECKey> =
        (listOf(keyMaterial.signingKey.toPublicJWK()) + keyMaterial.retiringSigningKeys)
            .associateBy { requireNotNull(it.keyID) }

    override fun verify(rawToken: String, now: Instant): VerifiedAccessToken = invalidAccessTokenOnFailure {
        val token = SignedJWT.parse(rawToken)
        val header = token.header
        require(header.algorithm == JWSAlgorithm.ES256)
        require(header.type == ACCESS_TOKEN_TYPE)
        require(header.criticalParams.isNullOrEmpty())
        val keyId = requireNotNull(header.keyID).also { require(it.isNotBlank()) }
        val key = requireNotNull(verificationKeys[keyId])
        require(token.verify(ECDSAVerifier(key.toECPublicKey())))

        val claims = token.jwtClaimsSet
        require(claims.issuer == properties.issuer.toASCIIString())
        require(claims.audience == listOf(properties.audience))
        val issuedAt = requireNotNull(claims.issueTime).toInstant()
        val expiresAt = requireNotNull(claims.expirationTime).toInstant()
        require(!issuedAt.isAfter(now))
        require(expiresAt.isAfter(now))
        require(expiresAt.isAfter(issuedAt))
        claims.notBeforeTime?.toInstant()?.let { notBefore -> require(!notBefore.isAfter(now)) }

        val accountId = requireUuidV7(requireNotNull(claims.subject))
        requireUuidV7(requireNotNull(claims.getStringClaim(SESSION_ID_CLAIM)))
        requireUuidV7(requireNotNull(claims.getJWTID()))
        VerifiedAccessToken(accountId)
    }

    private fun requireUuidV7(rawValue: String): UUID = UUID.fromString(rawValue).also { value ->
        require(value.version() == UUID_V7 && value.variant() == RFC_4122_VARIANT)
    }

    private fun <T> invalidAccessTokenOnFailure(block: () -> T): T = try {
        block()
    } catch (_: InvalidAccessTokenException) {
        throw InvalidAccessTokenException()
    } catch (_: Exception) {
        throw InvalidAccessTokenException()
    }

    private companion object {
        val ACCESS_TOKEN_TYPE = JOSEObjectType("at+jwt")
        const val SESSION_ID_CLAIM = "sid"
        const val UUID_V7 = 7
        const val RFC_4122_VARIANT = 2
    }
}
