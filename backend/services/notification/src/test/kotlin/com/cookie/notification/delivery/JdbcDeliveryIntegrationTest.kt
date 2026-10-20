package com.cookie.notification.delivery

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import java.sql.Timestamp
import java.util.UUID

@Tag("integration")
@Testcontainers
class JdbcDeliveryIntegrationTest {
    private lateinit var jdbc: JdbcTemplate
    private lateinit var repository: JdbcDeliveryRepository
    @BeforeEach fun setup() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(dataSource).load().migrate()
        jdbc = JdbcTemplate(dataSource)
        jdbc.update("TRUNCATE delivery_attempts,delivery_jobs")
        repository = JdbcDeliveryRepository(jdbc, DataSourceTransactionManager(dataSource))
    }

    @Test fun `expired SMTP submission becomes terminal unknown and duplicate event never resets it`() {
        val event = UUID.randomUUID(); val attempt = UUID.randomUUID(); val now = Instant.now()
        repository.enqueueEmail(event, attempt, "ciphertext", now.plusSeconds(600), now)
        val job = repository.claim(1).single()
        assertThat(repository.beginSmtpSubmission(job)).isTrue()
        jdbc.update("UPDATE delivery_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", event)
        repository.maintain()
        repository.enqueueEmail(event, attempt, "ciphertext", now.plusSeconds(600), now)
        assertThat(repository.claim(1)).isEmpty()
        assertThat(jdbc.queryForObject("SELECT status FROM delivery_jobs WHERE id=?", String::class.java, event)).isEqualTo("UNKNOWN")
        assertThat(jdbc.queryForObject("SELECT encrypted_payload FROM delivery_jobs WHERE id=?", String::class.java, event)).isNull()
        repository.retry(job, "SMTP_REJECTED_TEMPORARY", Duration.ZERO)
        assertThat(repository.claim(1)).isEmpty()
        val meters = SimpleMeterRegistry()
        try {
            DeliveryMetrics(jdbc, meters).refresh()
            assertThat(meters.get("cookie.notification.delivery.metrics.healthy").gauge().value()).isEqualTo(1.0)
            assertThat(meters.get("cookie.notification.delivery.unknown").gauge().value()).isEqualTo(1.0)
            assertThat(meters.get("cookie.notification.delivery.pending").gauge().value()).isEqualTo(0.0)
        } finally {
            meters.close()
        }
    }

    @Test fun `preparation lease may be reclaimed but stale worker cannot submit or complete`() {
        val now = Instant.now(); val event = UUID.randomUUID()
        repository.enqueueEmail(event, UUID.randomUUID(), "ciphertext", now.plusSeconds(600), now)
        val old = repository.claim(1).single()
        jdbc.update("UPDATE delivery_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", event)
        repository.maintain()
        val current = repository.claim(1).single()
        assertThat(repository.beginSmtpSubmission(old)).isFalse()
        repository.finish(old, "ACCEPTED", "PROVIDER_ACCEPTED")
        assertThat(repository.beginSmtpSubmission(current)).isTrue()
        repository.finish(current, "ACCEPTED", "PROVIDER_ACCEPTED", "message-id")
        assertThat(jdbc.queryForObject("SELECT status FROM delivery_jobs WHERE id=?", String::class.java, event)).isEqualTo("ACCEPTED")
    }

    @ParameterizedTest(name = "same token registered again: {0}")
    @ValueSource(booleans = [true, false])
    fun `late invalid token response cannot disable a newer registration`(sameToken: Boolean) {
        val userId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val before = Instant.parse("2026-09-17T12:00:00Z")
        val after = before.plusSeconds(1)
        val oldToken = "old-token-$deviceId"
        val newToken = if (sameToken) oldToken else "new-token-$deviceId"
        jdbc.update("INSERT INTO settings(user_id) VALUES (?)", userId)
        jdbc.update("""INSERT INTO push_devices(id,user_id,platform,device_id,push_token,created_at,updated_at)
            VALUES (?,?,'ANDROID','installation',?,?,?)""", deviceId, userId, oldToken,
            Timestamp.from(before), Timestamp.from(before))
        val original = PushTarget(deviceId, "ANDROID", oldToken, before)
        jdbc.update("UPDATE push_devices SET push_token=?,updated_at=?,enabled=true WHERE id=?",
            newToken, Timestamp.from(after), deviceId)

        repository.invalidateDevice(original)
        assertThat(jdbc.queryForObject("SELECT enabled FROM push_devices WHERE id=?", Boolean::class.java, deviceId)).isTrue()
        assertThat(jdbc.queryForObject("SELECT push_token FROM push_devices WHERE id=?", String::class.java, deviceId)).isEqualTo(newToken)

        repository.invalidateDevice(PushTarget(deviceId, "ANDROID", newToken, after))
        assertThat(jdbc.queryForObject("SELECT enabled FROM push_devices WHERE id=?", Boolean::class.java, deviceId)).isFalse()
    }
    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.6-alpine3.24")
    }
}
