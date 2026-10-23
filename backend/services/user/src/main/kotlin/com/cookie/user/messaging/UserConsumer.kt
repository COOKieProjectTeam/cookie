package com.cookie.user.messaging

import com.cookie.user.config.UserProperties
import com.cookie.user.persistence.JdbcUserStore
import com.cookie.user.persistence.LifecycleEvents
import com.cookie.user.persistence.InvalidUserEvent
import io.micrometer.core.instrument.MeterRegistry
import io.nats.client.*
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Duration

@Component
class UserConsumer(private val broker:UserBroker,private val properties:UserProperties,
                   private val events:LifecycleEvents,private val store:JdbcUserStore,registry:MeterRegistry) {
    private var subscription:JetStreamSubscription?=null
    private var owner:Connection?=null
    private val failures=registry.counter("cookie.user.inbox.failures")
    private val processed=registry.counter("cookie.user.inbox.processed")
    private val quarantined=registry.counter("cookie.user.inbox.quarantined")
    @Scheduled(fixedDelay=100)
    fun poll() {
        if(!properties.messagingEnabled) return
        try {
            val connection=broker.connection()
            val active=subscription?.takeIf { it.isActive && owner===connection } ?: connection.jetStream()
                .subscribe(null,PullSubscribeOptions.bind(UserBroker.STREAM,LifecycleEvents.CONSUMER)).also { subscription=it;owner=connection }
            active.fetch(20,Duration.ofSeconds(1)).forEach(::consume)
        } catch (_:Exception) {
            failures.increment()
            runCatching { subscription?.unsubscribe() };subscription=null;owner=null
        }
    }
    fun consume(message:Message) {
        try {
            events.handle(message.subject,message.data)
            message.ack();processed.increment()
        } catch (invalid:InvalidUserEvent) {
            try {
                val hash=MessageDigest.getInstance("SHA-256").digest(message.data).joinToString("") { "%02x".format(it) }
                store.jdbc.update("INSERT INTO message_quarantine(message_hash,subject,reason,stream_sequence) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                    hash,message.subject.take(255),invalid.reason,message.metaData().streamSequence())
                message.ack();quarantined.increment()
            } catch (_:Exception) { retry(message) }
        } catch (_:Exception) { retry(message) }
    }
    private fun retry(message:Message) {
        failures.increment()
        // A failing NAK or quarantine write never aborts the fetched batch.
        runCatching { val attempt=message.metaData().deliveredCount().coerceIn(0,8).toInt()
            message.nakWithDelay(Duration.ofSeconds((1L shl attempt).coerceAtMost(300))) }
    }
}
