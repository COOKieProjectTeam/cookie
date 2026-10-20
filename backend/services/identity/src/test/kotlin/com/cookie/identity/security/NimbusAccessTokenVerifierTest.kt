package com.cookie.identity.security

import com.cookie.identity.application.InvalidAccessTokenException
import com.cookie.identity.config.IdentityProperties
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.Date
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NimbusAccessTokenVerifierTest {
    private val properties = IdentityProperties()
    private val activeKey = signingKey("active")
    private val retiringKey = signingKey("retiring")
    private val untrustedKey = signingKey("untrusted")
    private val verifier = NimbusAccessTokenVerifier(
        properties,
        KeyMaterial(activeKey, listOf(retiringKey.toPublicJWK()), notificationPublicKey()),
    )

    @Test
    fun `accepts an access token signed by the active key`() {
        val verified = verifier.verify(accessToken(signingKey = activeKey), NOW)

        assertThat(verified.accountId).isEqualTo(ACCOUNT_ID)
    }

    @Test
    fun `accepts an access token signed by a retiring key`() {
        val verified = verifier.verify(accessToken(signingKey = retiringKey), NOW)

        assertThat(verified.accountId).isEqualTo(ACCOUNT_ID)
    }

    @TestFactory
    fun `rejects invalid JOSE protections with a generic error`(): List<DynamicTest> = listOf(
        RejectionCase("signature from a different key") {
            accessToken(signingKey = untrustedKey, keyId = activeKey.keyID)
        },
        RejectionCase("unknown kid") {
            accessToken(signingKey = activeKey, keyId = "unknown")
        },
        RejectionCase("algorithm other than ES256") {
            accessToken(
                signingKey = activeKey,
                algorithm = JWSAlgorithm.HS256,
                signer = MACSigner(ByteArray(32) { 0x42.toByte() }),
            )
        },
        RejectionCase("token type other than at+jwt") {
            accessToken(signingKey = activeKey, type = JOSEObjectType.JWT)
        },
        RejectionCase("malformed compact token") { "not-a-jwt" },
    ).map { case ->
        DynamicTest.dynamicTest(case.name) { assertGenericRejection(case.token()) }
    }

    @TestFactory
    fun `rejects mismatched identity and invalid validity windows with a generic error`(): List<DynamicTest> = listOf(
        RejectionCase("issuer mismatch") {
            accessToken(issuer = "https://attacker.example")
        },
        RejectionCase("audience mismatch") {
            accessToken(audience = listOf("different-api"))
        },
        RejectionCase("additional audience") {
            accessToken(audience = listOf(properties.audience, "different-api"))
        },
        RejectionCase("expired token") {
            accessToken(expiresAt = NOW.minusSeconds(1))
        },
        RejectionCase("future issued-at") {
            accessToken(issuedAt = NOW.plusSeconds(1))
        },
        RejectionCase("future not-before") {
            accessToken(notBefore = NOW.plusSeconds(1))
        },
    ).map { case ->
        DynamicTest.dynamicTest(case.name) { assertGenericRejection(case.token()) }
    }

    @TestFactory
    fun `rejects every missing required claim with a generic error`(): List<DynamicTest> = listOf(
        RejectionCase("missing issuer") { accessToken(issuer = null) },
        RejectionCase("missing audience") { accessToken(audience = null) },
        RejectionCase("missing issued-at") { accessToken(issuedAt = null) },
        RejectionCase("missing expiration") { accessToken(expiresAt = null) },
        RejectionCase("missing subject") { accessToken(subject = null) },
        RejectionCase("missing session id") { accessToken(sessionId = null) },
        RejectionCase("missing token id") { accessToken(tokenId = null) },
    ).map { case ->
        DynamicTest.dynamicTest(case.name) { assertGenericRejection(case.token()) }
    }

    @TestFactory
    fun `rejects malformed and non-v7 identity claims with a generic error`(): List<DynamicTest> = listOf(
        RejectionCase("malformed subject") { accessToken(subject = "not-a-uuid") },
        RejectionCase("non-v7 subject") { accessToken(subject = UUID.randomUUID().toString()) },
        RejectionCase("malformed session id") { accessToken(sessionId = "not-a-uuid") },
        RejectionCase("non-v7 session id") { accessToken(sessionId = UUID.randomUUID().toString()) },
        RejectionCase("malformed token id") { accessToken(tokenId = "not-a-uuid") },
        RejectionCase("non-v7 token id") { accessToken(tokenId = UUID.randomUUID().toString()) },
    ).map { case ->
        DynamicTest.dynamicTest(case.name) { assertGenericRejection(case.token()) }
    }

    private fun assertGenericRejection(rawToken: String) {
        assertThatThrownBy { verifier.verify(rawToken, NOW) }
            .isExactlyInstanceOf(InvalidAccessTokenException::class.java)
            .hasMessage("Invalid or expired access token")
            .hasNoCause()
    }

    private fun accessToken(
        signingKey: ECKey = activeKey,
        keyId: String = signingKey.keyID,
        algorithm: JWSAlgorithm = JWSAlgorithm.ES256,
        type: JOSEObjectType = JOSEObjectType("at+jwt"),
        signer: JWSSigner = ECDSASigner(signingKey),
        issuer: String? = properties.issuer.toASCIIString(),
        audience: List<String>? = listOf(properties.audience),
        issuedAt: Instant? = NOW.minusSeconds(30),
        expiresAt: Instant? = NOW.plusSeconds(900),
        notBefore: Instant? = null,
        subject: String? = ACCOUNT_ID.toString(),
        sessionId: String? = SESSION_ID.toString(),
        tokenId: String? = TOKEN_ID.toString(),
    ): String {
        val claims = JWTClaimsSet.Builder().apply {
            issuer?.let { issuer(it) }
            audience?.let { audience(it) }
            issuedAt?.let { issueTime(Date.from(it)) }
            expiresAt?.let { expirationTime(Date.from(it)) }
            notBefore?.let { notBeforeTime(Date.from(it)) }
            subject?.let { subject(it) }
            sessionId?.let { claim("sid", it) }
            tokenId?.let { jwtID(it) }
        }.build()
        return SignedJWT(
            JWSHeader.Builder(algorithm)
                .type(type)
                .keyID(keyId)
                .build(),
            claims,
        ).apply { sign(signer) }.serialize()
    }

    private fun signingKey(keyId: String): ECKey = ECKeyGenerator(Curve.P_256)
        .keyUse(KeyUse.SIGNATURE)
        .algorithm(JWSAlgorithm.ES256)
        .keyID(keyId)
        .generate()

    private fun notificationPublicKey(): RSAKey = RSAKeyGenerator(2048)
        .keyUse(KeyUse.ENCRYPTION)
        .algorithm(JWEAlgorithm.RSA_OAEP_256)
        .keyID("notification")
        .generate()
        .toPublicJWK()

    private data class RejectionCase(
        val name: String,
        val token: () -> String,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-04T12:00:00Z")
        val ACCOUNT_ID: UUID = UUID.fromString("0198c4a5-68b5-7def-8123-456789abcdef")
        val SESSION_ID: UUID = UUID.fromString("0198c4a5-68b5-7abc-9234-56789abcdef0")
        val TOKEN_ID: UUID = UUID.fromString("0198c4a5-68b5-7cde-a345-6789abcdef01")
    }
}
