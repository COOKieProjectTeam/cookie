package com.cookie.identity.persistence

import com.cookie.identity.config.IdentityRetentionProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class IdentityDataRetentionJob(
    private val maintenance: JdbcMaintenanceRepository,
    private val outbox: JdbcOutboxRepository,
    private val properties: IdentityRetentionProperties,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${cookie.identity.retention.poll-delay:PT1H}")
    fun deleteExpiredData() {
        val now = clock.instant()
        val rateLimits = drain {
            maintenance.deleteExpiredRateLimitBuckets(now.minus(properties.rateLimitGrace), properties.batchSize)
        }
        val challenges = drain {
            maintenance.deleteUnusableVerificationChallengesBefore(
                now.minus(properties.verificationChallengeAudit),
                properties.batchSize,
            )
        }
        val sessions = drain {
            maintenance.deleteRefreshFamiliesExpiredBefore(
                now.minus(properties.refreshSessionAudit),
                properties.batchSize,
            )
        }
        val outboxEvents = drain {
            outbox.deletePublishedBefore(now.minus(properties.publishedOutbox), properties.batchSize)
        }
        if (rateLimits + challenges + sessions + outboxEvents > 0) {
            logger.info(
                "Identity retention removed rateLimits={} challenges={} sessions={} outbox={}",
                rateLimits,
                challenges,
                sessions,
                outboxEvents,
            )
        }
    }

    private fun drain(deleteBatch: () -> Int): Int {
        var total = 0
        repeat(MAX_BATCHES_PER_RUN) {
            val deleted = deleteBatch()
            total += deleted
            if (deleted == 0) return total
        }
        return total
    }

    private companion object {
        const val MAX_BATCHES_PER_RUN = 10
        val logger = LoggerFactory.getLogger(IdentityDataRetentionJob::class.java)
    }
}
