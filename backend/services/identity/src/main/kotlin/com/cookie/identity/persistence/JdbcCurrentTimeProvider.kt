package com.cookie.identity.persistence

import com.cookie.identity.application.ports.CurrentTimeProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class JdbcCurrentTimeProvider(
    private val jdbcTemplate: JdbcTemplate,
) : CurrentTimeProvider {
    override fun now(): Instant = requireNotNull(
        jdbcTemplate.queryForObject(CURRENT_TIME_SQL) { resultSet, _ ->
            resultSet.getTimestamp(1).toInstant()
        },
    ) { "Database did not return the current time" }

    companion object {
        private const val CURRENT_TIME_SQL = "SELECT clock_timestamp()"
    }
}
