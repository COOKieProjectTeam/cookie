package com.cookie.identity.application

import com.cookie.identity.domain.UuidV7Generator
import com.cookie.identity.persistence.IdentityRepository
import com.cookie.identity.security.NotificationPayloadEncryptor
import com.cookie.identity.security.VerificationDelivery
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@Component
class IdentityOutboxWriter(
    private val repository: IdentityRepository,
    private val encryptor: NotificationPayloadEncryptor,
    private val objectMapper: ObjectMapper,
    private val uuidV7Generator: UuidV7Generator,
) {
    fun verificationRequested(
        accountId: UUID,
        email: String,
        locale: String?,
        rawToken: String,
        expiresAt: Instant,
        now: Instant,
    ) {
        val encryptedPayload = encryptor.encrypt(
            VerificationDelivery(email, locale, rawToken, expiresAt),
        )
        val payload = objectMapper.createObjectNode()
            .put("template", "EMAIL_VERIFICATION")
            .put("encryptedPayload", encryptedPayload)
            .put("expiresAt", expiresAt.toString())
        repository.insertOutbox(
            eventId = uuidV7Generator.next(),
            eventType = "notification.email.requested",
            eventVersion = 1,
            aggregateType = "account",
            aggregateId = accountId.toString(),
            payloadJson = objectMapper.writeValueAsString(payload),
            now = now,
        )
    }

    fun accountCreated(accountId: UUID, createdAt: Instant, now: Instant) {
        val metadata = objectMapper.createObjectNode()
            .put("registeredWith", "email")
            .put("createdAt", createdAt.toString())
        val payload = objectMapper.createObjectNode()
            .put("userId", accountId.toString())
            .set("accountMetadata", metadata)
        repository.insertOutbox(
            eventId = uuidV7Generator.next(),
            eventType = "account.created",
            eventVersion = 1,
            aggregateType = "account",
            aggregateId = accountId.toString(),
            payloadJson = objectMapper.writeValueAsString(payload),
            now = now,
        )
    }
}
