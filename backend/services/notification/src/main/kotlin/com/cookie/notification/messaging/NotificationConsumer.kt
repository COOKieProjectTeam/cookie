package com.cookie.notification.messaging

import com.cookie.platform.messaging.JetStreamPullConsumer
import io.micrometer.core.instrument.MeterRegistry
import io.nats.client.Message
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.context.annotation.Profile
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.UUID

@Component
@Profile("!notification-replay")
class NotificationConsumer(
    private val nats:NotificationNatsConnection,
    private val properties:NotificationMessagingProperties,
    private val handler:NotificationEventHandler,
    private val quarantine:NotificationQuarantine,
    registry:MeterRegistry,
) {
    private val processed=registry.counter("notification.inbox.processed")
    private val failures=registry.counter("notification.inbox.failures")
    private val quarantined=registry.counter("notification.inbox.quarantined")
    private val runner = JetStreamPullConsumer(
        connection = nats::connection,
        stream = NotificationNatsConnection.STREAM,
        durable = NotificationEventHandler.CONSUMER,
        handle = { handler.handle(it.subject, it.data) },
        invalidReason = { (it as? InvalidNotificationEvent)?.reason },
        quarantine = { message, reason -> quarantine.record(message.subject, message.data,
            message.metaData().streamSequence(), reason) },
        processed = { processed.increment() },
        quarantined = { quarantined.increment() },
        failed = { failures.increment() },
    )
    @Scheduled(fixedDelayString="\${cookie.notification.consumer-poll-millis:100}")
    fun poll() {
        if (properties.consumerEnabled) runner.poll()
    }
    fun consume(message: Message) = runner.consume(message)
}

@Component
class NotificationQuarantine(private val jdbc:JdbcTemplate,private val mapper:ObjectMapper) {
    fun record(subject:String,data:ByteArray,sequence:Long,reason:String) {
        require(reason.matches(Regex("[a-zA-Z0-9_]{1,100}")))
        val eventId=runCatching { UUID.fromString(mapper.readTree(data).path("event_id").stringValue()) }.getOrNull()
        jdbc.update("""INSERT INTO message_quarantine(id,message_hash,event_id,subject,reason,stream_sequence)
            VALUES (?,?,?,?,?,?) ON CONFLICT(message_hash) DO NOTHING""",UUID.randomUUID(),digest(data),eventId,subject,reason,sequence)
    }
    companion object { fun digest(data:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) } }
}
