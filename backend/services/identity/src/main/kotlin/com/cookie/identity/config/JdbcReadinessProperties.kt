package com.cookie.identity.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.convert.DurationUnit
import java.time.Duration
import java.time.temporal.ChronoUnit

@ConfigurationProperties("spring.datasource.hikari")
class JdbcReadinessProperties(
    @param:DurationUnit(ChronoUnit.MILLIS)
    val validationTimeout: Duration = Duration.ofSeconds(5),
) {
    val validationTimeoutSeconds: Int

    init {
        require(!validationTimeout.isZero && !validationTimeout.isNegative) {
            "JDBC validation timeout must be positive"
        }
        require(validationTimeout <= Duration.ofSeconds(Int.MAX_VALUE.toLong())) {
            "JDBC validation timeout is too large"
        }
        val roundedSeconds = validationTimeout.seconds + if (validationTimeout.nano == 0) 0 else 1
        validationTimeoutSeconds = roundedSeconds.toInt()
    }
}
