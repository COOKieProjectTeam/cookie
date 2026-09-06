package com.cookie.identity.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("cookie.identity.database")
class IdentityDatabaseProperties(
    val transactionTimeout: Duration = Duration.ofSeconds(5),
    val lockTimeout: Duration = Duration.ofSeconds(2),
) {
    init {
        require(transactionTimeout.nano == 0 && transactionTimeout in Duration.ofSeconds(1)..Duration.ofSeconds(30)) {
            "Identity transaction timeout must be a whole number of seconds between 1 and 30"
        }
        require(lockTimeout.toMillis() > 0 && lockTimeout < transactionTimeout) {
            "Identity lock timeout must be positive and shorter than the transaction timeout"
        }
    }

    val transactionTimeoutSeconds: Int = transactionTimeout.seconds.toInt()
    val lockTimeoutMilliseconds: Long = lockTimeout.toMillis()
    val statementTimeoutMilliseconds: Long = transactionTimeout.toMillis()
}
