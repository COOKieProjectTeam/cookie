package com.cookie.notification.config

import com.cookie.notification.messaging.NotificationEventHandler
import com.cookie.notification.messaging.NotificationMessagingProperties
import com.cookie.notification.messaging.NotificationNatsConnection
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/** Fixed-cardinality metadata only; infrastructure failures must never disclose event contents. */
@Component
@Profile("!notification-replay")
class NotificationRuntimeMetrics(
    private val jdbc: JdbcTemplate,
    private val nats: NotificationNatsConnection,
    private val properties: NotificationMessagingProperties,
    registry: MeterRegistry,
) {
    private val overdue = AtomicLong()
    private val oldestSeconds = AtomicLong()
    private val databaseHealthy = AtomicLong()
    private val natsHealthy = AtomicLong()
    private val pending = AtomicLong()
    private val unacknowledged = AtomicLong()

    init {
        registry.gauge("cookie.notification.scheduler.overdue", overdue)
        registry.gauge("cookie.notification.scheduler.oldest.age.seconds", oldestSeconds)
        registry.gauge("cookie.notification.scheduler.metrics.healthy", databaseHealthy)
        registry.gauge("cookie.notification.nats.healthy", natsHealthy)
        registry.gauge("cookie.notification.nats.pending", pending)
        registry.gauge("cookie.notification.nats.unacknowledged", unacknowledged)
    }

    @Scheduled(fixedDelay = 10_000)
    fun refresh() {
        try {
            jdbc.query("""SELECT count(*) AS overdue,
                COALESCE(GREATEST(0,EXTRACT(EPOCH FROM (clock_timestamp()-min(due_at)))),0)::bigint AS age
                FROM reminder_deadlines WHERE due_at<=clock_timestamp()""", { row, _ ->
                overdue.set(row.getLong("overdue"))
                oldestSeconds.set(row.getLong("age"))
            })
            databaseHealthy.set(1)
        } catch (_: Exception) { databaseHealthy.set(0) }
        if (!properties.consumerEnabled) return
        try {
            val consumer = nats.connection().jetStreamManagement()
                .getConsumerInfo(NotificationNatsConnection.STREAM, NotificationEventHandler.CONSUMER)
            pending.set(consumer.numPending)
            unacknowledged.set(consumer.numAckPending)
            natsHealthy.set(1)
        } catch (_: Exception) { natsHealthy.set(0) }
    }
}
