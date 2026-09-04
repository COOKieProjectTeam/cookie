package com.cookie.identity.messaging

import com.cookie.identity.persistence.JdbcOutboxRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@Component
class OutboxMetrics(
    private val repository: JdbcOutboxRepository,
    private val registry: MeterRegistry,
) {
    private val pendingCount = AtomicLong()
    private val oldestPendingAgeMillis = AtomicLong()

    init {
        Gauge.builder("cookie.identity.outbox.pending", pendingCount) { it.get().toDouble() }
            .description("Number of unpublished Identity outbox events")
            .register(registry)
        Gauge.builder("cookie.identity.outbox.oldest.age.seconds", oldestPendingAgeMillis) {
            it.get().toDouble() / MILLIS_PER_SECOND
        }
            .description("Age in seconds of the oldest unpublished Identity outbox event")
            .register(registry)
    }

    @Scheduled(fixedDelayString = "\${cookie.identity.outbox-metrics-poll-delay:10000}")
    fun refreshBacklog() {
        runCatching { repository.backlogSnapshot() }
            .onSuccess { snapshot ->
                pendingCount.set(snapshot.pendingCount)
                oldestPendingAgeMillis.set((snapshot.oldestPendingAgeSeconds * MILLIS_PER_SECOND).toLong())
            }
            .onFailure { exception -> logger.warn("Could not refresh Identity outbox backlog metrics", exception) }
    }

    fun recordPublishAttempt(eventType: String) = counter(eventType, "attempt").increment()

    fun recordPublishSuccess(eventType: String) = counter(eventType, "success").increment()

    fun recordPublishFailure(eventType: String) = counter(eventType, "failure").increment()

    fun recordStaleCompletion(eventType: String) = counter(eventType, "stale_completion").increment()

    private fun counter(eventType: String, outcome: String) = registry.counter(
        "cookie.identity.outbox.publish",
        "event.type",
        eventType,
        "outcome",
        outcome,
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
        val logger = LoggerFactory.getLogger(OutboxMetrics::class.java)
    }
}
