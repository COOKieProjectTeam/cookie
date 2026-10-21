package com.cookie.notification.delivery

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@Component
class DeliveryMetrics(private val jdbc: JdbcTemplate, private val registry: MeterRegistry) {
    private val pending = AtomicLong()
    private val oldestSeconds = AtomicLong()
    private val unknown = AtomicLong()
    private val failures = AtomicLong()
    private val snapshotHealthy = AtomicLong()
    init {
        Gauge.builder("cookie.notification.delivery.pending", pending) { it.get().toDouble() }.register(registry)
        Gauge.builder("cookie.notification.delivery.oldest.age.seconds", oldestSeconds) { it.get().toDouble() }.register(registry)
        Gauge.builder("cookie.notification.delivery.unknown", unknown) { it.get().toDouble() }.register(registry)
        Gauge.builder("cookie.notification.delivery.failed", failures) { it.get().toDouble() }.register(registry)
        Gauge.builder("cookie.notification.delivery.metrics.healthy", snapshotHealthy) { it.get().toDouble() }.register(registry)
    }
    @Scheduled(fixedDelay = 10_000)
    fun refresh() {
        try {
            jdbc.query("""SELECT count(*) FILTER(WHERE status IN ('PENDING','CLAIMED','SMTP_SUBMITTING')) AS pending,
                coalesce(extract(epoch FROM clock_timestamp()-(min(created_at)
                  FILTER(WHERE status IN ('PENDING','CLAIMED','SMTP_SUBMITTING')))),0) AS age,
                count(*) FILTER(WHERE status='UNKNOWN') AS unknown,
                count(*) FILTER(WHERE status='FAILED') AS failed FROM delivery_jobs""", RowCallbackHandler { row ->
                pending.set(row.getLong("pending"))
                oldestSeconds.set(row.getLong("age").coerceAtLeast(0))
                unknown.set(row.getLong("unknown"))
                failures.set(row.getLong("failed"))
            })
            snapshotHealthy.set(1)
        } catch (_: Exception) {
            snapshotHealthy.set(0)
            logger.warn("Notification delivery metrics refresh failed")
        }
    }
    fun outcome(channel: String, outcome: String) {
        registry.counter("cookie.notification.delivery.attempt", "channel", channel, "outcome", outcome).increment()
    }
    fun workerFailure(channel: String) = outcome(channel, "worker_failure")
    private companion object { val logger = LoggerFactory.getLogger(DeliveryMetrics::class.java) }
}
