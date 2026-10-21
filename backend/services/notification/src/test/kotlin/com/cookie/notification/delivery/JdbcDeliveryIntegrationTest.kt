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
import java.util.Properties
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
        repository.retry(job, "SMTP_REJECTED_TEMPORARY", Duration.ZERO, providerFailure = true)
        assertThat(repository.claim(1)).isEmpty()
        assertThat(jdbc.queryForObject("SELECT provider_failure_count FROM delivery_jobs WHERE id=?", Int::class.java, event)).isZero()
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

    @Test fun `provider failure budget survives repository recreation and stale workers cannot spend it`() {
        val now = Instant.now()
        val event = UUID.randomUUID()
        repository.enqueueEmail(event, UUID.randomUUID(), "ciphertext", now.plusSeconds(7200), now)
        repeat(12) { index ->
            val deferred = repository.claim(1).single()
            assertThat(deferred.attemptCount).isEqualTo(index + 1)
            assertThat(deferred.providerFailureCount).isZero()
            repository.retry(deferred, "DECRYPTION_KEY_UNAVAILABLE", Duration.ZERO)
        }
        val firstFailure = repository.claim(1).single()
        assertThat(repository.beginSmtpSubmission(firstFailure)).isTrue()
        repository.retry(firstFailure, "SMTP_REJECTED_TEMPORARY", Duration.ZERO, providerFailure = true)

        val restartedSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        repository = JdbcDeliveryRepository(JdbcTemplate(restartedSource), DataSourceTransactionManager(restartedSource))
        val resumed = repository.claim(1).single()
        assertThat(resumed.attemptCount).isEqualTo(14)
        assertThat(resumed.providerFailureCount).isEqualTo(1)
        repository.retry(firstFailure, "SMTP_REJECTED_TEMPORARY", Duration.ZERO, providerFailure = true)
        repository.finish(firstFailure, "FAILED", "RETRIES_EXHAUSTED", providerFailure = true)
        assertThat(jdbc.queryForObject("SELECT provider_failure_count FROM delivery_jobs WHERE id=?", Int::class.java, event)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT status FROM delivery_jobs WHERE id=?", String::class.java, event)).isEqualTo("CLAIMED")

        // A temporary channel outage after a real failure also leaves that failure count intact.
        repository.retry(resumed, "CHANNEL_PAUSED", Duration.ZERO)
        val accepted = repository.claim(1).single()
        assertThat(accepted.providerFailureCount).isEqualTo(1)
        assertThat(repository.beginSmtpSubmission(accepted)).isTrue()
        repository.finish(accepted, "ACCEPTED", "PROVIDER_ACCEPTED")
        assertThat(jdbc.queryForObject("SELECT status FROM delivery_jobs WHERE id=?", String::class.java, event)).isEqualTo("ACCEPTED")
        assertThat(jdbc.queryForObject("SELECT provider_failure_count FROM delivery_jobs WHERE id=?", Int::class.java, event)).isEqualTo(1)
    }

    @Test fun `terminal retry outcome counts its final provider failure exactly once`() {
        val now = Instant.now()
        val event = UUID.randomUUID()
        repository.enqueueEmail(event, UUID.randomUUID(), "ciphertext", now.plusSeconds(7200), now)
        repeat(9) { index ->
            val job = repository.claim(1).single()
            assertThat(job.providerFailureCount).isEqualTo(index)
            repository.retry(job, "SMTP_CONNECT", Duration.ZERO, providerFailure = true)
        }
        val final = repository.claim(1).single()
        assertThat(final.providerFailureCount).isEqualTo(9)
        repository.finish(final, "FAILED", "RETRIES_EXHAUSTED", providerFailure = true)
        repository.finish(final, "FAILED", "RETRIES_EXHAUSTED", providerFailure = true)
        assertThat(jdbc.queryForObject("SELECT provider_failure_count FROM delivery_jobs WHERE id=?", Int::class.java, event)).isEqualTo(10)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_attempts WHERE delivery_id=? AND finished_at IS NOT NULL",
            Int::class.java, event)).isEqualTo(10)
        assertThat(repository.claim(1)).isEmpty()
    }

    @Test fun `upgrade derives failures from known provider outcomes without reopening ambiguous SMTP`() {
        val schema = "retry_budget_${UUID.randomUUID().toString().replace("-", "")}"
        jdbc.execute("CREATE SCHEMA $schema")
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).apply {
            connectionProperties = Properties().apply { setProperty("currentSchema", schema) }
        }
        try {
            Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema).target("4").load().migrate()
            val previous = JdbcTemplate(source)
            val active = UUID.randomUUID()
            val unknown = UUID.randomUUID()
            previous.update("""INSERT INTO delivery_jobs(id,dedup_key,channel,registration_attempt_id,kind,expires_at,attempt_count)
                VALUES (?,?,'EMAIL',?,'EMAIL_VERIFICATION',clock_timestamp()+interval '1 hour',8)""",
                active, "email:$active", UUID.randomUUID())
            val codes = listOf("FCM_AUTH", "CHANNEL_PAUSED", "QUIET_HOURS", "WATER_INTERVAL",
                "DECRYPTION_KEY_UNAVAILABLE", "APNS_DISABLED", "SMTP_CONNECT", "SMTP_REJECTED_TEMPORARY")
            codes.forEach { code ->
                previous.update("""INSERT INTO delivery_attempts(id,delivery_id,finished_at,outcome,safe_code)
                    VALUES (?,?,clock_timestamp(),'RETRY',?)""", UUID.randomUUID(), active, code)
            }
            previous.update("""INSERT INTO delivery_jobs(id,dedup_key,channel,registration_attempt_id,kind,expires_at,
                attempt_count,status,last_code,finished_at)
                VALUES (?,?,'EMAIL',?,'EMAIL_VERIFICATION',clock_timestamp()+interval '1 hour',
                1,'UNKNOWN','SMTP_AMBIGUOUS',clock_timestamp())""", unknown, "email:$unknown", UUID.randomUUID())
            previous.update("""INSERT INTO delivery_attempts(id,delivery_id,finished_at,outcome,safe_code)
                VALUES (?,?,clock_timestamp(),'UNKNOWN','SMTP_AMBIGUOUS')""", UUID.randomUUID(), unknown)

            Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema).load().migrate()
            assertThat(previous.queryForObject("SELECT provider_failure_count FROM delivery_jobs WHERE id=?", Int::class.java, active)).isEqualTo(2)
            assertThat(previous.queryForObject("SELECT attempt_count FROM delivery_jobs WHERE id=?", Int::class.java, active)).isEqualTo(8)
            assertThat(previous.queryForObject("SELECT status FROM delivery_jobs WHERE id=?", String::class.java, unknown)).isEqualTo("UNKNOWN")
            assertThat(previous.queryForObject("SELECT provider_failure_count FROM delivery_jobs WHERE id=?", Int::class.java, unknown)).isZero()
            assertThat(previous.queryForObject("SELECT encrypted_payload FROM delivery_jobs WHERE id=?", String::class.java, unknown)).isNull()
        } finally {
            jdbc.execute("DROP SCHEMA $schema CASCADE")
        }
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
