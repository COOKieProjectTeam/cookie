package com.cookie.identity.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("cookie.identity.retention")
class IdentityRetentionProperties(
    val rateLimitGrace: Duration = Duration.ofDays(1),
    val completedRegistrationAttemptAudit: Duration = Duration.ofDays(30),
    val abandonedRegistrationAttemptAudit: Duration = Duration.ofDays(30),
    val refreshFamilyAudit: Duration = Duration.ofDays(90),
    val publishedOutbox: Duration = Duration.ofDays(7),
    val batchSize: Int = 500,
    val maxBatchesPerRun: Int = 400,
    val maxRunDuration: Duration = Duration.ofSeconds(30),
) {
    init {
        listOf(
            rateLimitGrace,
            completedRegistrationAttemptAudit,
            abandonedRegistrationAttemptAudit,
            refreshFamilyAudit,
            publishedOutbox,
        ).forEach {
            require(!it.isNegative) { "Identity retention durations must not be negative" }
        }
        require(completedRegistrationAttemptAudit >= MIN_CONFIRMATION_RETRY_RETENTION) {
            "Completed registration attempt retention must be at least $MIN_CONFIRMATION_RETRY_RETENTION"
        }
        require(abandonedRegistrationAttemptAudit >= MIN_ABANDONED_ATTEMPT_RETENTION) {
            "Abandoned registration attempt retention must be at least $MIN_ABANDONED_ATTEMPT_RETENTION"
        }
        require(batchSize in 1..10_000) { "Identity cleanup batch size must be between 1 and 10000" }
        require(maxBatchesPerRun in CLEANER_COUNT..10_000) {
            "Identity cleanup max batches must be between $CLEANER_COUNT and 10000"
        }
        require(maxRunDuration in Duration.ofSeconds(1)..Duration.ofMinutes(5)) {
            "Identity cleanup max run duration must be between 1 second and 5 minutes"
        }
    }

    companion object {
        /** Minimum public idempotency window for an already completed registration attempt. */
        val MIN_CONFIRMATION_RETRY_RETENTION: Duration = Duration.ofDays(30)
        /** Minimum window in which expiry/competition cannot change an exact register retry. */
        val MIN_ABANDONED_ATTEMPT_RETENTION: Duration = Duration.ofDays(30)
        private const val CLEANER_COUNT = 5
    }
}
