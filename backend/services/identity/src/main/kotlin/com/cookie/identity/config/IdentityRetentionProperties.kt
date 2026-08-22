package com.cookie.identity.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("cookie.identity.retention")
class IdentityRetentionProperties(
    val rateLimitGrace: Duration = Duration.ofDays(1),
    val verificationChallengeAudit: Duration = Duration.ofDays(30),
    val refreshSessionAudit: Duration = Duration.ofDays(90),
    val publishedOutbox: Duration = Duration.ofDays(7),
    val batchSize: Int = 500,
) {
    init {
        listOf(rateLimitGrace, verificationChallengeAudit, refreshSessionAudit, publishedOutbox).forEach {
            require(!it.isNegative) { "Identity retention durations must not be negative" }
        }
        require(batchSize in 1..10_000) { "Identity cleanup batch size must be between 1 and 10000" }
    }
}
