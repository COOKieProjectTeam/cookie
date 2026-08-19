package com.cookie.identity.security

import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSAEncrypter
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant

data class VerificationDelivery(
    val recipientEmail: String,
    val locale: String?,
    val token: String,
    val expiresAt: Instant,
)

@Component
class NotificationPayloadEncryptor(
    private val keyMaterial: KeyMaterial,
    private val objectMapper: ObjectMapper,
) {
    fun encrypt(delivery: VerificationDelivery): String {
        val jwe = JWEObject(
            JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM)
                .contentType("application/json")
                .keyID(keyMaterial.notificationEncryptionKey.keyID)
                .build(),
            Payload(objectMapper.writeValueAsString(delivery)),
        )
        jwe.encrypt(RSAEncrypter(keyMaterial.notificationEncryptionKey))
        return jwe.serialize()
    }
}
