package com.cookie.tools.notificationsink

import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSAEncrypter
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class DevNotificationSinkTest {
    @TempDir
    lateinit var tempDirectory: Path

    private val now = Instant.parse("2026-09-03T12:00:00Z")
    private val objectMapper = ObjectMapper()
    private val key: RSAKey = RSAKeyGenerator(2048)
        .keyID("notification-test")
        .keyUse(KeyUse.ENCRYPTION)
        .algorithm(JWEAlgorithm.RSA_OAEP_256)
        .generate()

    @Test
    fun `redelivery of an already delivered event does not send a second email`() {
        val deliveries = mutableListOf<UUID>()
        val sink = sink { eventId, _, _ -> deliveries += eventId }
        val eventId = UUID.randomUUID()
        val message = message(eventId, now.plusSeconds(60))

        assertThat(sink.handle(message)).isTrue()
        assertThat(sink.handle(message)).isTrue()

        assertThat(deliveries).containsExactly(eventId)
    }

    @Test
    fun `expired delivery is acknowledged without sending email`() {
        val deliveries = mutableListOf<UUID>()
        val sink = sink { eventId, _, _ -> deliveries += eventId }

        assertThat(sink.handle(message(UUID.randomUUID(), now))).isTrue()

        assertThat(deliveries).isEmpty()
    }

    @Test
    fun `failed delivery may be retried and long processing emits heartbeats`() {
        var attempts = 0
        var heartbeats = 0
        val sink = sink { _, _, _ ->
            attempts += 1
            if (attempts == 1) error("temporary SMTP failure")
        }
        val message = message(UUID.randomUUID(), now.plusSeconds(60))

        assertThat(sink.handle(message) { heartbeats += 1 }).isFalse()
        assertThat(sink.handle(message) { heartbeats += 1 }).isTrue()

        assertThat(attempts).isEqualTo(2)
        assertThat(heartbeats).isGreaterThanOrEqualTo(4)
    }

    @Test
    fun `deduplicator distinguishes an active delivery and evicts only completed history`() {
        val deliveries = DevDeliveryDeduplicator(maximumDeliveredEvents = 1)
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        assertThat(deliveries.begin(first)).isEqualTo(DeliveryStart.STARTED)
        assertThat(deliveries.begin(first)).isEqualTo(DeliveryStart.IN_PROGRESS)
        deliveries.complete(first)
        assertThat(deliveries.begin(first)).isEqualTo(DeliveryStart.ALREADY_DELIVERED)

        assertThat(deliveries.begin(second)).isEqualTo(DeliveryStart.STARTED)
        deliveries.complete(second)

        assertThat(deliveries.begin(first)).isEqualTo(DeliveryStart.STARTED)
        assertThat(deliveries.begin(second)).isEqualTo(DeliveryStart.ALREADY_DELIVERED)
    }

    private fun sink(sender: VerificationEmailSender): DevNotificationSink {
        val privateKey = Files.writeString(tempDirectory.resolve("notification-private.jwk"), key.toJSONString())
        return DevNotificationSink(
            configuration = NotificationSinkProperties(privateKeyPath = privateKey.toString()),
            objectMapper = objectMapper,
            emailSender = sender,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
    }

    private fun message(eventId: UUID, expiresAt: Instant): ByteArray {
        val delivery = objectMapper.createObjectNode()
            .put("recipientEmail", "user@example.ru")
            .put("token", "verification-token")
            .put("expiresAt", expiresAt.toString())
        val jwe = JWEObject(
            JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM)
                .keyID(key.keyID)
                .build(),
            Payload(objectMapper.writeValueAsString(delivery)),
        ).apply { encrypt(RSAEncrypter(key.toPublicJWK())) }
        val payload = objectMapper.createObjectNode().put("encryptedPayload", jwe.serialize())
        val envelope = objectMapper.createObjectNode()
            .put("event_id", eventId.toString())
            .set("payload", payload)
        return objectMapper.writeValueAsBytes(envelope)
    }
}
