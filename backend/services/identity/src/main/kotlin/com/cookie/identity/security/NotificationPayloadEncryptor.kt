package com.cookie.identity.security

import com.cookie.identity.domain.LocaleTag
import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSAEncrypter
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

class VerificationDelivery(
    val registrationAttemptId: UUID,
    val recipientEmail: String,
    val locale: LocaleTag?,
    val token: String,
    val expiresAt: Instant,
) {
    override fun toString(): String =
        "VerificationDelivery(registrationAttemptId=$registrationAttemptId,recipientEmail=[redacted]," +
            "locale=$locale,token=[redacted],expiresAt=$expiresAt)"
}

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
            Payload(
                objectMapper.writeValueAsString(
                    VerificationDeliveryPayload(
                        registrationAttemptId = delivery.registrationAttemptId,
                        recipientEmail = delivery.recipientEmail,
                        locale = delivery.locale?.value,
                        token = delivery.token,
                        expiresAt = delivery.expiresAt,
                    ),
                ),
            ),
        )
        jwe.encrypt(RSAEncrypter(keyMaterial.notificationEncryptionKey))
        return jwe.serialize()
    }
}

private data class VerificationDeliveryPayload(
    val registrationAttemptId: UUID,
    val recipientEmail: String,
    val locale: String?,
    val token: String,
    val expiresAt: Instant,
)
