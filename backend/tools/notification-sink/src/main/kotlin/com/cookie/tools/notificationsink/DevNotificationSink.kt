package com.cookie.tools.notificationsink

import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.crypto.RSADecrypter
import com.nimbusds.jose.jwk.RSAKey
import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.PushSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.DeliverPolicy
import jakarta.annotation.PreDestroy
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties

@Component
class DevNotificationSink(
    private val configuration: NotificationSinkProperties,
    private val objectMapper: ObjectMapper,
) : ApplicationRunner {
    private var connection: Connection? = null

    override fun run(args: ApplicationArguments) {
        awaitPrivateKey()
        val options = Options.Builder()
            .server(configuration.natsUrl)
            .connectionName("cookie-dev-notification-sink")
            .connectionTimeout(Duration.ofSeconds(2))
            .maxReconnects(-1)
            .build()
        connection = Nats.connect(options).also(::subscribe)
        logger.info("Dev notification sink is connected; messages are delivered to Mailpit")
    }

    @PreDestroy
    fun close() {
        connection?.runCatching { close() }
    }

    private fun awaitPrivateKey(expectedKeyId: String? = null): RSAKey {
        val path = Path.of(configuration.privateKeyPath)
        repeat(120) {
            val key = runCatching {
                if (Files.isRegularFile(path)) RSAKey.parse(Files.readString(path)) else null
            }.getOrNull()
            if (key != null && (expectedKeyId == null || key.keyID == expectedKeyId)) return key
            Thread.sleep(500)
        }
        error("Notification private key${expectedKeyId?.let { " kid=$it" }.orEmpty()} was not available within 60 seconds")
    }

    private fun subscribe(nats: Connection) {
        val dispatcher = nats.createDispatcher()
        val consumer = ConsumerConfiguration.builder()
            .durable(DURABLE_CONSUMER)
            .deliverPolicy(DeliverPolicy.All)
            .ackPolicy(AckPolicy.Explicit)
            .ackWait(Duration.ofSeconds(30))
            .filterSubject(NOTIFICATION_SUBJECT)
            .build()
        val options = PushSubscribeOptions.builder()
            .stream(STREAM_NAME)
            .durable(DURABLE_CONSUMER)
            .configuration(consumer)
            .build()
        repeat(120) { attempt ->
            try {
                nats.jetStream().subscribe(
                    NOTIFICATION_SUBJECT,
                    dispatcher,
                    { message ->
                        if (handle(message.data)) message.ack() else message.nakWithDelay(Duration.ofSeconds(2))
                    },
                    false,
                    options,
                )
                return
            } catch (exception: Exception) {
                if (attempt == 119) throw exception
                Thread.sleep(500)
            }
        }
    }

    private fun handle(data: ByteArray): Boolean {
        var eventId = "unknown"
        return try {
            val envelope = objectMapper.readTree(data)
            eventId = envelope.path("event_id").stringValue("unknown")
            val compact = envelope.path("payload").path("encryptedPayload").stringValue()
            val jwe = JWEObject.parse(compact)
            val privateKey = awaitPrivateKey(jwe.header.keyID)
            jwe.decrypt(RSADecrypter(privateKey))
            val delivery = objectMapper.readTree(jwe.payload.toString())
            sendEmail(
                recipient = delivery.path("recipientEmail").stringValue(),
                token = delivery.path("token").stringValue(),
            )
            logger.info("Delivered verification email to Mailpit eventId={}", eventId)
            true
        } catch (exception: Exception) {
            logger.error("Failed to deliver verification email eventId={}", eventId, exception)
            false
        }
    }

    private fun sendEmail(recipient: String, token: String) {
        val properties = Properties().apply {
            put("mail.smtp.host", configuration.smtpHost)
            put("mail.smtp.port", configuration.smtpPort.toString())
        }
        val message = MimeMessage(Session.getInstance(properties)).apply {
            setFrom(InternetAddress("noreply@cookie.local", "COOKie"))
            setRecipient(Message.RecipientType.TO, InternetAddress(recipient))
            subject = "Подтвердите email в COOKie"
            setText("Код подтверждения: $token", Charsets.UTF_8.name())
        }
        Transport.send(message)
    }

    companion object {
        private const val NOTIFICATION_SUBJECT = "cookie.events.notification.email.requested.v1"
        private const val STREAM_NAME = "COOKIE_EVENTS"
        private const val DURABLE_CONSUMER = "dev-notification-sink"
        private val logger = LoggerFactory.getLogger(DevNotificationSink::class.java)
    }
}
