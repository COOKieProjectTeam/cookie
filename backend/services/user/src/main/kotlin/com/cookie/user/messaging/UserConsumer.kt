package com.cookie.user.messaging

import com.cookie.platform.messaging.JetStreamPullConsumer
import com.cookie.platform.messaging.ProgressSourceRequest
import com.cookie.user.config.UserProperties
import com.cookie.user.persistence.JdbcUserStore
import com.cookie.user.persistence.GoalAttempts
import com.cookie.user.persistence.LifecycleEvents
import com.cookie.user.persistence.InvalidUserEvent
import com.cookie.user.persistence.UserProgressEvents
import io.micrometer.core.instrument.MeterRegistry
import io.nats.client.Message
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component
class UserConsumer(broker: UserBroker, private val properties: UserProperties,
                   events: LifecycleEvents, goals: GoalAttempts, store: JdbcUserStore, registry: MeterRegistry) {
    private val failures = registry.counter("cookie.user.inbox.failures")
    private val processed = registry.counter("cookie.user.inbox.processed")
    private val quarantined = registry.counter("cookie.user.inbox.quarantined")
    private val progress = UserProgressEvents(store)
    private val progressSubjects = ProgressSourceRequest.subjects("user")
    private val runner = JetStreamPullConsumer(
        connection = broker::connection,
        stream = UserBroker.STREAM,
        durable = LifecycleEvents.CONSUMER,
        handle = {
            when (it.subject) {
                GoalAttempts.SUBJECT -> goals.consume(it.subject, it.data)
                in progressSubjects -> progress.handle(it.subject, it.data)
                else -> events.handle(it.subject, it.data)
            }
        },
        invalidReason = { (it as? InvalidUserEvent)?.reason },
        quarantine = { message, reason ->
            val hash = MessageDigest.getInstance("SHA-256").digest(message.data).joinToString("") { "%02x".format(it) }
            store.jdbc.update("INSERT INTO message_quarantine(message_hash,subject,reason,stream_sequence) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                hash, message.subject.take(255), reason, message.metaData().streamSequence())
        },
        processed = { processed.increment() },
        quarantined = { quarantined.increment() },
        failed = { failures.increment() },
    )

    @Scheduled(fixedDelay = 100)
    fun poll() {
        if (properties.messagingEnabled) runner.poll()
    }

    fun consume(message: Message) = runner.consume(message)
}
