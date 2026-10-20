package com.cookie.identity.security

import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSAEncrypter
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey

class KeyMaterial(
    val signingKey: ECKey,
    val retiringSigningKeys: List<ECKey>,
    val notificationEncryptionKey: RSAKey,
) {
    init {
        validateSigningKey(signingKey, "Active Identity signing JWK", privateRequired = true)
        retiringSigningKeys.forEachIndexed { index, key ->
            validateSigningKey(key, "Retiring Identity signing JWK[$index]", privateRequired = false)
        }
        val keyIds = (listOf(signingKey) + retiringSigningKeys).map { requireNotNull(it.keyID) }
        require(keyIds.distinct().size == keyIds.size) { "Identity signing JWK kid values must be unique" }
        validateNotificationKey(notificationEncryptionKey)
        verifySigningOperation()
        verifyEncryptionOperation()
    }

    override fun toString(): String =
        "KeyMaterial(signingKid=${signingKey.keyID},retiringKids=${retiringSigningKeys.map { it.keyID }}," +
            "notificationKid=${notificationEncryptionKey.keyID},privateMaterial=[redacted])"

    private fun validateSigningKey(key: ECKey, label: String, privateRequired: Boolean) {
        require(key.curve == Curve.P_256) { "$label must use P-256" }
        require(key.keyUse == KeyUse.SIGNATURE) { "$label must declare use=sig" }
        require(key.algorithm == JWSAlgorithm.ES256) { "$label must declare alg=ES256" }
        require(!key.keyID.isNullOrBlank()) { "$label must have kid" }
        if (privateRequired) {
            require(key.isPrivate) { "$label must contain private key material" }
        } else {
            require(!key.isPrivate) { "$label must contain public key material only" }
        }
    }

    private fun validateNotificationKey(key: RSAKey) {
        require(!key.isPrivate) { "Notification encryption JWK must contain public key material only" }
        require(key.toRSAPublicKey().modulus.bitLength() >= MINIMUM_RSA_BITS) {
            "Notification encryption JWK must contain an RSA key of at least $MINIMUM_RSA_BITS bits"
        }
        require(key.keyUse == KeyUse.ENCRYPTION) { "Notification encryption JWK must declare use=enc" }
        require(key.algorithm == JWEAlgorithm.RSA_OAEP_256) {
            "Notification encryption JWK must declare alg=RSA-OAEP-256"
        }
        require(!key.keyID.isNullOrBlank()) { "Notification encryption JWK must have kid" }
    }

    private fun verifySigningOperation() {
        try {
            val probe = JWSObject(
                JWSHeader.Builder(JWSAlgorithm.ES256).keyID(signingKey.keyID).build(),
                Payload(SELF_TEST_PAYLOAD),
            )
            probe.sign(ECDSASigner(signingKey))
            require(probe.verify(ECDSAVerifier(signingKey.toECPublicKey()))) {
                "Identity signing JWK self-test verification failed"
            }
        } catch (exception: Exception) {
            throw IllegalArgumentException("Identity signing JWK failed its startup self-test", exception)
        }
    }

    private fun verifyEncryptionOperation() {
        try {
            val probe = JWEObject(
                JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM)
                    .keyID(notificationEncryptionKey.keyID)
                    .build(),
                Payload(SELF_TEST_PAYLOAD),
            )
            probe.encrypt(RSAEncrypter(notificationEncryptionKey))
            require(probe.serialize().isNotBlank()) { "Notification encryption JWK self-test failed" }
        } catch (exception: Exception) {
            throw IllegalArgumentException("Notification encryption JWK failed its startup self-test", exception)
        }
    }

    companion object {
        private const val MINIMUM_RSA_BITS = 2048
        private const val SELF_TEST_PAYLOAD = "cookie-key-self-test"
    }
}
