package com.cookie.notification.messaging

import io.micrometer.core.instrument.MeterRegistry
import io.nats.client.JetStreamSubscription
import io.nats.client.Message
import io.nats.client.PullSubscribeOptions
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.context.annotation.Profile
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Duration
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
    private var subscription:JetStreamSubscription?=null
    private val processed=registry.counter("notification.inbox.processed")
    private val failures=registry.counter("notification.inbox.failures")
    private val recoveryFailures=registry.counter("notification.inbox.recovery.failures")
    private val quarantined=registry.counter("notification.inbox.quarantined")
    @Scheduled(fixedDelayString="\${cookie.notification.consumer-poll-millis:100}")
    fun poll() {
        if(!properties.consumerEnabled || Thread.currentThread().isInterrupted) return
        try {
            val active=subscription?.takeIf { it.isActive }?:nats.connection().jetStream()
                .subscribe(null,PullSubscribeOptions.bind(NotificationNatsConnection.STREAM,NotificationEventHandler.CONSUMER)).also { subscription=it }
            for(message in active.fetch(20,Duration.ofSeconds(1))) {
                consume(message)
                if(Thread.currentThread().isInterrupted) return
            }
        } catch (_:InterruptedException) { Thread.currentThread().interrupt() }
        catch (_:Exception) { failures.increment() }
    }
    fun consume(message:Message) {
        if(Thread.currentThread().isInterrupted) return
        try {
            try {
                handler.handle(message.subject,message.data)
                message.ack()
                processed.increment()
            } catch (invalid:InvalidNotificationEvent) {
                // Durable quarantine must succeed before ACK; otherwise preserve broker redelivery.
                quarantine.record(message.subject,message.data,message.metaData().streamSequence(),invalid.reason)
                message.ack()
                quarantined.increment()
            } catch (interrupted:InterruptedException) { throw interrupted }
            catch (_:Exception) {
                failures.increment()
                // Only explicit semantic invalidity is poison. Infrastructure failures retry indefinitely.
                val seconds=(1L shl message.metaData().deliveredCount().coerceIn(0,8).toInt()).coerceAtMost(300)
                message.nakWithDelay(Duration.ofSeconds(seconds))
            }
        } catch (_:InterruptedException) { Thread.currentThread().interrupt() }
        catch (_:Exception) {
            recoveryFailures.increment()
            // No recovery ACK: leave this message unacknowledged and continue independent work.
        }
    }
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
