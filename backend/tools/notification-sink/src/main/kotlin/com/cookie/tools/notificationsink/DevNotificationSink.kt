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
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.UUID

@Component
class DevNotificationSink(
    private val configuration: NotificationSinkProperties,
    private val objectMapper: ObjectMapper,
    private val emailSender: VerificationEmailSender,
    private val clock: Clock,
) : ApplicationRunner {
    private val deliveries = DevDeliveryDeduplicator()

    @Volatile
    private var connection: Connection? = null

    override fun run(args: ApplicationArguments) {
        awaitPrivateKey()
        val options = Options.Builder()
            .server(configuration.natsUrl)
            .connectionName("cookie-dev-notification-sink")
            .connectionTimeout(Duration.ofSeconds(2))
            .maxReconnects(-1)
            .build()
        val connected = try {
            Nats.connect(options)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        }
        try {
            subscribe(connected)
            connection = connected
        } catch (exception: Exception) {
            connected.runCatching { close() }
            throw exception
        }
        logger.info("Dev notification sink is connected; messages are delivered to Mailpit")
    }

    @PreDestroy
    fun close() {
        connection?.runCatching { close() }
    }

    private fun awaitPrivateKey(expectedKeyId: String? = null, heartbeat: () -> Unit = {}): RSAKey {
        val path = Path.of(configuration.privateKeyPath)
        repeat(KEY_WAIT_ATTEMPTS) { attempt ->
            if (attempt % HEARTBEAT_EVERY_ATTEMPTS == 0) heartbeat()
            val key = runCatching {
                if (Files.isRegularFile(path)) RSAKey.parse(Files.readString(path)) else null
            }.getOrNull()
            if (key != null && (expectedKeyId == null || key.keyID == expectedKeyId)) return key
            try {
                Thread.sleep(KEY_WAIT_DELAY_MILLIS)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while waiting for the Notification private key", exception)
            }
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
                        if (handle(message.data) { message.inProgress() }) {
                            message.ack()
                        } else {
                            message.nakWithDelay(Duration.ofSeconds(2))
                        }
                    },
                    false,
                    options,
                )
                return
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while subscribing the development notification sink", interrupted)
            } catch (exception: Exception) {
                if (attempt == 119) throw exception
                try {
                    Thread.sleep(SUBSCRIPTION_RETRY_DELAY_MILLIS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Interrupted while subscribing the development notification sink", interrupted)
                }
            }
        }
    }

    internal fun handle(data: ByteArray, heartbeat: () -> Unit = {}): Boolean {
        var eventId: UUID? = null
        var ownsDelivery = false
        return try {
            val envelope = objectMapper.readTree(data)
            eventId = UUID.fromString(envelope.path("event_id").stringValue())
            when (deliveries.begin(requireNotNull(eventId))) {
                DeliveryStart.ALREADY_DELIVERED -> {
                    logger.info("Ignored duplicate verification email eventId={}", eventId)
                    return true
                }
                DeliveryStart.IN_PROGRESS -> return false
                DeliveryStart.STARTED -> ownsDelivery = true
            }
            val compact = envelope.path("payload").path("encryptedPayload").stringValue()
            val jwe = JWEObject.parse(compact)
            val privateKey = awaitPrivateKey(jwe.header.keyID, heartbeat)
            jwe.decrypt(RSADecrypter(privateKey))
            val delivery = objectMapper.readTree(jwe.payload.toString())
            val expiresAt = Instant.parse(delivery.path("expiresAt").stringValue())
            if (!expiresAt.isAfter(clock.instant())) {
                deliveries.complete(requireNotNull(eventId))
                logger.info("Discarded expired verification email eventId={} expiresAt={}", eventId, expiresAt)
                return true
            }
            heartbeat()
            emailSender.send(
                eventId = requireNotNull(eventId),
                recipient = delivery.path("recipientEmail").stringValue(),
                token = delivery.path("token").stringValue(),
            )
            deliveries.complete(requireNotNull(eventId))
            logger.info("Delivered verification email to Mailpit eventId={}", eventId)
            true
        } catch (exception: Exception) {
            if (ownsDelivery) eventId?.let(deliveries::fail)
            logger.error("Failed to deliver verification email eventId={}", eventId, exception)
            false
        }
    }

    companion object {
        private const val NOTIFICATION_SUBJECT = "cookie.events.notification.email.requested.v1"
        private const val STREAM_NAME = "COOKIE_EVENTS"
        private const val DURABLE_CONSUMER = "dev-notification-sink"
        private const val KEY_WAIT_ATTEMPTS = 120
        private const val KEY_WAIT_DELAY_MILLIS = 500L
        private const val SUBSCRIPTION_RETRY_DELAY_MILLIS = 500L
        private const val HEARTBEAT_EVERY_ATTEMPTS = 20
        private val logger = LoggerFactory.getLogger(DevNotificationSink::class.java)
    }
}

internal enum class DeliveryStart {
    STARTED,
    IN_PROGRESS,
    ALREADY_DELIVERED,
}

internal class DevDeliveryDeduplicator(
    private val maximumDeliveredEvents: Int = 10_000,
) {
    private val states = LinkedHashMap<UUID, DeliveryState>()

    init {
        require(maximumDeliveredEvents > 0) { "Delivery deduplication capacity must be positive" }
    }

    fun begin(eventId: UUID): DeliveryStart = synchronized(states) {
        when (states[eventId]) {
            DeliveryState.IN_PROGRESS -> DeliveryStart.IN_PROGRESS
            DeliveryState.DELIVERED -> DeliveryStart.ALREADY_DELIVERED
            null -> {
                states[eventId] = DeliveryState.IN_PROGRESS
                DeliveryStart.STARTED
            }
        }
    }

    fun complete(eventId: UUID) = synchronized(states) {
        check(states[eventId] == DeliveryState.IN_PROGRESS) { "Only an in-progress delivery can complete" }
        states[eventId] = DeliveryState.DELIVERED
        trimDeliveredEvents()
    }

    fun fail(eventId: UUID) {
        synchronized(states) {
            states.remove(eventId, DeliveryState.IN_PROGRESS)
        }
    }

    private fun trimDeliveredEvents() {
        while (states.values.count { it == DeliveryState.DELIVERED } > maximumDeliveredEvents) {
            val eldestDelivered = states.entries.first { it.value == DeliveryState.DELIVERED }
            states.remove(eldestDelivered.key)
        }
    }

    private enum class DeliveryState {
        IN_PROGRESS,
        DELIVERED,
    }
}
