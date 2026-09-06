package com.cookie.identity.persistence

import com.cookie.identity.application.ports.CurrentTimeProvider
import com.cookie.identity.config.IdentityRetentionProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class IdentityDataRetentionJob(
    private val maintenance: JdbcMaintenanceRepository,
    private val outbox: JdbcOutboxRepository,
    private val properties: IdentityRetentionProperties,
    private val currentTime: CurrentTimeProvider,
) {
    @Scheduled(fixedDelayString = "\${cookie.identity.retention.poll-delay:PT1M}")
    fun deleteExpiredData() {
        val now = currentTime.now()
        val removed = FairBatchDrainer(
            batchSize = properties.batchSize,
            maxBatches = properties.maxBatchesPerRun,
            maxRunDurationNanos = properties.maxRunDuration.toNanos(),
        ).drain(
            listOf(
                { maintenance.deleteExpiredRateLimitBuckets(now.minus(properties.rateLimitGrace), properties.batchSize) },
                { maintenance.abandonExpiredRegistrationAttempts(now, properties.batchSize) },
                {
                    maintenance.deleteRegistrationAttemptTombstones(
                        abandonedBefore = now.minus(properties.abandonedRegistrationAttemptAudit),
                        completedBefore = now.minus(properties.completedRegistrationAttemptAudit),
                        properties.batchSize,
                    )
                },
                {
                    maintenance.deleteRefreshFamiliesExpiredBefore(
                        now.minus(properties.refreshFamilyAudit),
                        properties.batchSize,
                    )
                },
                { outbox.deletePublishedBefore(now.minus(properties.publishedOutbox), properties.batchSize) },
            ),
        )
        val (rateLimits, abandonedAttempts, removedAttemptTombstones, refreshFamilies, outboxEvents) = removed
        if (rateLimits + abandonedAttempts + removedAttemptTombstones + refreshFamilies + outboxEvents > 0) {
            logger.info(
                "Identity retention processed rateLimitsRemoved={} registrationAttemptsAbandoned={} " +
                    "registrationAttemptTombstonesRemoved={} refreshFamiliesRemoved={} outboxEventsRemoved={}",
                rateLimits,
                abandonedAttempts,
                removedAttemptTombstones,
                refreshFamilies,
                outboxEvents,
            )
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(IdentityDataRetentionJob::class.java)
    }
}

/** Fairly drains independent tables without letting one backlog starve the rest. */
internal class FairBatchDrainer(
    private val batchSize: Int,
    private val maxBatches: Int,
    private val maxRunDurationNanos: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(batchSize > 0) { "Retention batch size must be positive" }
        require(maxBatches > 0) { "Retention batch count must be positive" }
        require(maxRunDurationNanos > 0) { "Retention time budget must be positive" }
    }

    fun drain(cleaners: List<() -> Int>): List<Int> {
        require(cleaners.isNotEmpty()) { "At least one retention cleaner is required" }
        require(maxBatches >= cleaners.size) { "Retention budget must give every cleaner at least one batch" }
        val startedAt = nanoTime()
        val active = BooleanArray(cleaners.size) { true }
        val totals = IntArray(cleaners.size)
        var executedBatches = 0

        while (active.any { it } && executedBatches < maxBatches && nanoTime() - startedAt < maxRunDurationNanos) {
            cleaners.forEachIndexed { index, cleaner ->
                if (!active[index] || executedBatches >= maxBatches || nanoTime() - startedAt >= maxRunDurationNanos) {
                    return@forEachIndexed
                }
                val deleted = cleaner()
                check(deleted >= 0) { "Retention cleaner returned a negative row count" }
                totals[index] += deleted
                executedBatches += 1
                if (deleted < batchSize) active[index] = false
            }
        }
        return totals.toList()
    }
}
