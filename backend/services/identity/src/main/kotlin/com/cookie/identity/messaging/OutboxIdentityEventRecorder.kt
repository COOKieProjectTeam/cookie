package com.cookie.identity.messaging

import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.application.ports.IdentityEventRecorder
import com.cookie.identity.domain.LocaleTag
import com.cookie.identity.domain.AccountActivated
import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.persistence.JdbcOutboxRepository
import com.cookie.identity.security.NotificationPayloadEncryptor
import com.cookie.identity.security.VerificationDelivery
import com.cookie.platform.web.RequestIdFilter
import org.slf4j.MDC
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@Component
class OutboxIdentityEventRecorder(
    private val outbox: JdbcOutboxRepository,
    private val encryptor: NotificationPayloadEncryptor,
    private val objectMapper: ObjectMapper,
    private val ids: IdGenerator,
) : IdentityEventRecorder {
    override fun verificationRequested(
        registrationAttemptId: UUID,
        email: CanonicalEmail,
        locale: LocaleTag?,
        rawToken: String,
        expiresAt: Instant,
        now: Instant,
    ) {
        val encryptedPayload = encryptor.encrypt(
            VerificationDelivery(registrationAttemptId, email.value, locale, rawToken, expiresAt),
        )
        val payload = objectMapper.createObjectNode()
            .put("template", "EMAIL_VERIFICATION")
            .put("encryptedPayload", encryptedPayload)
            .put("expiresAt", expiresAt.toString())
        outbox.insert(
            eventId = ids.next(),
            eventType = "notification.email.requested",
            eventVersion = 1,
            aggregateType = "registration_attempt",
            aggregateId = registrationAttemptId.toString(),
            payloadJson = objectMapper.writeValueAsString(payload),
            occurredAt = now,
            correlationId = correlationId(),
            causationId = null,
            traceId = traceId(),
        )
    }

    override fun accountActivated(event: AccountActivated) {
        val metadata = objectMapper.createObjectNode()
            .put("registeredWith", "email")
            .put("createdAt", event.registeredAt.toString())
        val payload = objectMapper.createObjectNode()
            .put("userId", event.accountId.toString())
            .set("accountMetadata", metadata)
        outbox.insert(
            eventId = ids.next(),
            eventType = "account.activated",
            eventVersion = 1,
            aggregateType = "account",
            aggregateId = event.accountId.toString(),
            payloadJson = objectMapper.writeValueAsString(payload),
            occurredAt = event.activatedAt,
            correlationId = correlationId(),
            causationId = null,
            traceId = traceId(),
        )
    }

    private fun correlationId(): UUID? = MDC.get(RequestIdFilter.REQUEST_ID_MDC)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun traceId(): String? = MDC.get(TRACE_ID_MDC)?.take(MAX_TRACE_ID_LENGTH)

    private companion object {
        const val TRACE_ID_MDC = "traceId"
        const val MAX_TRACE_ID_LENGTH = 128
    }
}
