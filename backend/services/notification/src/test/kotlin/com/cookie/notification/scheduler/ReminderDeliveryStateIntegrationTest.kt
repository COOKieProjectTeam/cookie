package com.cookie.notification.scheduler

import com.cookie.notification.delivery.JdbcDeliveryRepository
import com.cookie.notification.domain.DevicePlatform
import com.cookie.notification.domain.RegisterDevice
import com.cookie.notification.domain.ReminderKind
import com.cookie.notification.domain.SettingsPatch
import com.cookie.notification.messaging.NotificationEventHandler
import com.cookie.notification.persistence.JdbcNotificationRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@Tag("integration")
@Testcontainers
class ReminderDeliveryStateIntegrationTest {
    private lateinit var jdbc: JdbcTemplate
    private lateinit var repository: JdbcNotificationRepository
    private lateinit var deliveries: JdbcDeliveryRepository
    private lateinit var scheduler: ReminderScheduler
    private lateinit var events: NotificationEventHandler
    private lateinit var eligibility: ProjectedPushEligibility
    private lateinit var transactions: TransactionTemplate
    private val clock = AtomicReference(Instant.now().truncatedTo(ChronoUnit.MICROS))
    private val registry = SimpleMeterRegistry()
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @BeforeEach
    fun setup() {
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).load().migrate()
        jdbc = JdbcTemplate(source)
        jdbc.execute("""TRUNCATE delivery_attempts,delivery_jobs,settings,push_devices,inbox_events,
            event_projections,reminder_deadlines,reminder_occurrences,account_tombstones,message_quarantine""")
        val manager = DataSourceTransactionManager(source)
        val tx = TransactionTemplate(manager)
        transactions = tx
        repository = spy(JdbcNotificationRepository(jdbc, tx))
        doAnswer { clock.get() }.`when`(repository).now()
        deliveries = JdbcDeliveryRepository(jdbc, manager)
        val index = RedisDeadlineIndex(mock(StringRedisTemplate::class.java), jdbc, registry)
        scheduler = ReminderScheduler(jdbc, repository, tx, deliveries, index, mapper, registry, true)
        events = NotificationEventHandler(jdbc, tx, repository, deliveries, mapper)
        eligibility = ProjectedPushEligibility(jdbc, repository, tx, mapper)
    }

    @AfterEach
    fun close() { registry.close() }

    @Test
    fun `corrected meal rearms an entirely unsent FOOD occurrence at its new deadline only`() {
        val originalNow = clock.get()
        val user = user(food = true, water = false)
        val meal = UUID.randomUUID()
        meal(user, meal, originalNow.minusSeconds(14_400), 1)
        scheduler.process(user)
        val occurrence = jdbc.queryForObject("SELECT id FROM reminder_occurrences WHERE kind='FOOD'", UUID::class.java)!!
        val first = deliveries.claim(1).single()

        meal(user, meal, originalNow.minusSeconds(3_600), 2)
        assertThat(eligibility.isEligible(user, "FOOD", occurrence, originalNow)).isFalse()
        deliveries.finish(first, "CANCELLED", "NO_LONGER_ELIGIBLE")
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(1))
        scheduler.process(user)
        assertThat(status()).isEqualTo("CANCELLED")
        assertThat(jdbc.queryForObject("SELECT due_at FROM reminder_deadlines WHERE user_id=?", Timestamp::class.java, user)!!.toInstant())
            .isEqualTo(minOf(originalNow.plusSeconds(10_800), nextDay(clock.get())))

        clock.set(originalNow.plusSeconds(10_800))
        scheduler.process(user)
        assertThat(status()).isEqualTo("PENDING")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reminder_occurrences", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_jobs", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT occurrence_id FROM delivery_jobs", UUID::class.java)).isEqualTo(occurrence)
        assertThat(eligibility.isEligible(user, "FOOD", occurrence, clock.get())).isTrue()

        // Once submitted, another edit and delivery metadata retention cannot consume the cycle twice.
        val resumed = deliveries.claim(1).single()
        deliveries.withPushSubmission(resumed) {
            deliveries.markPushSubmission(resumed)
            deliveries.finish(resumed, "ACCEPTED", "PROVIDER_ACCEPTED")
        }
        meal(user, meal, originalNow.minusSeconds(7_200), 3)
        scheduler.process(user)
        assertThat(status()).isEqualTo("ACCEPTED")
        jdbc.update("DELETE FROM delivery_jobs")
        transactions.executeWithoutResult { repository.dirty(user, clock.get()) }
        scheduler.process(user)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_jobs", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT last_submitted_at IS NOT NULL FROM reminder_occurrences", Boolean::class.java)).isTrue()
    }

    @Test
    fun `delayed water submission advances scheduler two hours from actual dispatch`() {
        val current = Instant.now().truncatedTo(ChronoUnit.MICROS)
        clock.set(current.minusSeconds(7_140))
        val user = user(food = false, water = true)
        scheduler.process(user)
        val job = deliveries.claim(1).single()
        val reservation = jdbc.queryForObject("SELECT last_water_reminder_at FROM settings WHERE user_id=?", Timestamp::class.java, user)!!.toInstant()
        assertThat(reservation).isEqualTo(clock.get())

        clock.set(current)
        deliveries.withPushSubmission(job) {
            deliveries.markPushSubmission(job)
            deliveries.finish(job, "ACCEPTED", "PROVIDER_ACCEPTED")
        }
        val submission = jdbc.queryForObject("SELECT last_water_submission_at FROM settings WHERE user_id=?", Timestamp::class.java, user)!!.toInstant()
        assertThat(submission).isAfter(reservation.plusSeconds(7_000))
        assertThat(jdbc.queryForObject("SELECT last_water_submission_occurrence_id FROM settings WHERE user_id=?", UUID::class.java, user))
            .isEqualTo(job.occurrenceId)
        // The mark dirties the durable deadline even if the enqueue-based wakeup was already in the past.
        clock.set(submission.plusSeconds(60))
        scheduler.process(user)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reminder_occurrences WHERE kind='WATER'", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT due_at FROM reminder_deadlines WHERE user_id=?", Timestamp::class.java, user)!!.toInstant())
            .isEqualTo(minOf(submission.plusSeconds(7_200), nextDay(clock.get())))
    }

    @ParameterizedTest
    @EnumSource(ReminderKind::class, names = ["WEIGH", "CALORIES_EXCEEDED", "CALORIES_UNDER_TARGET"])
    fun `corrected daily projection rearms the same entirely unsent occurrence and jobs`(kind: ReminderKind) {
        clock.set(nextDay(Instant.now()).plusSeconds(21 * 3_600))
        val user = dailyUser(kind)
        dailyCondition(user, kind, eligible = true, version = 1)
        scheduler.process(user)
        val first = deliveries.claim(1).single()
        val occurrence = requireNotNull(first.occurrenceId)

        dailyCondition(user, kind, eligible = false, version = 2)
        assertThat(eligibility.isEligible(user, kind.name, occurrence, clock.get())).isFalse()
        deliveries.finish(first, "CANCELLED", "NO_LONGER_ELIGIBLE")
        scheduler.process(user)
        assertThat(status()).isEqualTo("CANCELLED")

        dailyCondition(user, kind, eligible = true, version = 3)
        scheduler.process(user)
        assertThat(status()).isEqualTo("PENDING")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reminder_occurrences", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_jobs", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT id FROM delivery_jobs", UUID::class.java)).isEqualTo(first.id)
        assertThat(jdbc.queryForObject("SELECT occurrence_id FROM delivery_jobs", UUID::class.java)).isEqualTo(occurrence)
        assertThat(jdbc.queryForObject("SELECT expires_at FROM delivery_jobs", Timestamp::class.java)!!.toInstant())
            .isEqualTo(nextDay(clock.get()))
        assertThat(eligibility.isEligible(user, kind.name, occurrence, clock.get())).isTrue()

        val resumed = deliveries.claim(1).single()
        deliveries.withPushSubmission(resumed) {
            deliveries.markPushSubmission(resumed)
            deliveries.finish(resumed, "ACCEPTED", "PROVIDER_ACCEPTED")
        }
        dailyCondition(user, kind, eligible = false, version = 4)
        dailyCondition(user, kind, eligible = true, version = 5)
        scheduler.process(user)
        assertThat(status()).isEqualTo("ACCEPTED")

        // Submission evidence, not retained job rows, consumes this daily cycle.
        jdbc.update("DELETE FROM delivery_jobs")
        transactions.executeWithoutResult { repository.dirty(user, clock.get()) }
        scheduler.process(user)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_jobs", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT last_submitted_at IS NOT NULL FROM reminder_occurrences", Boolean::class.java)).isTrue()
    }

    @ParameterizedTest
    @EnumSource(ReminderKind::class, names = ["WEIGH", "CALORIES_EXCEEDED", "CALORIES_UNDER_TARGET"])
    fun `cancelled daily occurrence never resumes on the next local date`(kind: ReminderKind) {
        clock.set(nextDay(Instant.now()).plusSeconds(21 * 3_600))
        val user = dailyUser(kind)
        dailyCondition(user, kind, eligible = true, version = 1)
        scheduler.process(user)
        val first = deliveries.claim(1).single()
        val occurrence = requireNotNull(first.occurrenceId)
        val originalDate = clock.get().atZone(ZoneOffset.UTC).toLocalDate().toString()

        dailyCondition(user, kind, eligible = false, version = 2)
        deliveries.finish(first, "CANCELLED", "NO_LONGER_ELIGIBLE")
        clock.set(clock.get().plusSeconds(86_400))
        dailyCondition(user, kind, eligible = true, version = 3)
        scheduler.process(user)

        assertThat(eligibility.isEligible(user, kind.name, occurrence, clock.get())).isFalse()
        assertThat(jdbc.queryForObject("SELECT status FROM delivery_jobs WHERE id=?", String::class.java, first.id))
            .isEqualTo("CANCELLED")
        assertThat(jdbc.queryForObject("SELECT cycle_key FROM reminder_occurrences WHERE id=?", String::class.java, occurrence))
            .isEqualTo(originalDate)
        val next = deliveries.claim(1).single()
        assertThat(next.id).isNotEqualTo(first.id)
        assertThat(next.occurrenceId).isNotEqualTo(occurrence)
        assertThat(jdbc.queryForObject("SELECT cycle_key FROM reminder_occurrences WHERE id=?", String::class.java, next.occurrenceId))
            .isEqualTo(clock.get().atZone(ZoneOffset.UTC).toLocalDate().toString())
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reminder_occurrences", Long::class.java)).isEqualTo(2)
    }

    @ParameterizedTest
    @EnumSource(ReminderKind::class, names = ["WEIGH", "CALORIES_EXCEEDED", "CALORIES_UNDER_TARGET"])
    fun `daily rearms only cancellations and never another terminal result or submitted fanout`(kind: ReminderKind) {
        clock.set(nextDay(Instant.now()).plusSeconds(21 * 3_600))
        val user = dailyUser(kind)
        dailyCondition(user, kind, eligible = true, version = 1)
        scheduler.process(user)
        val first = deliveries.claim(1).single()
        deliveries.finish(first, "FAILED", "PERMANENT_FAILURE")

        // Even without a submission marker, other terminal results must not regain a retry budget.
        listOf("FAILED", "UNKNOWN", "ACCEPTED", "EXPIRED").forEach { terminal ->
            jdbc.update("UPDATE delivery_jobs SET status=? WHERE id=?", terminal, first.id)
            transactions.executeWithoutResult { repository.dirty(user, clock.get()) }
            scheduler.process(user)
            assertThat(status()).isEqualTo(terminal)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_jobs", Long::class.java)).isEqualTo(1)
        }

        // One submitted target consumes the occurrence even if all remaining device jobs cancel.
        jdbc.update("UPDATE reminder_occurrences SET last_submitted_at=clock_timestamp() WHERE id=?", first.occurrenceId)
        jdbc.update("UPDATE delivery_jobs SET status='CANCELLED' WHERE id=?", first.id)
        transactions.executeWithoutResult { repository.dirty(user, clock.get()) }
        scheduler.process(user)
        assertThat(status()).isEqualTo("CANCELLED")
    }

    private fun status() = jdbc.queryForObject("SELECT status FROM delivery_jobs", String::class.java)
    private fun nextDay(now: Instant) = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()

    private fun user(food: Boolean, water: Boolean, weigh: Boolean = false, goals: Boolean = false): UUID {
        val user = UUID.randomUUID()
        repository.updateSettings(user, SettingsPatch(timezone = "UTC", foodEnabled = food, waterEnabled = water,
            weighEnabled = weigh, goalWarningEnabled = goals, quietStart = LocalTime.MIDNIGHT, quietEnd = LocalTime.MIDNIGHT))
        val started = Timestamp.from(clock.get().minusSeconds(28_800))
        jdbc.update("UPDATE settings SET scheduling_started_at=?,food_started_at=?,water_started_at=? WHERE user_id=?",
            started, started, started, user)
        repository.registerDevice(user, RegisterDevice(DevicePlatform.IOS, "installation", "token-$user"))
        return user
    }

    private fun dailyUser(kind: ReminderKind): UUID {
        val user = user(food = false, water = false, weigh = kind == ReminderKind.WEIGH, goals = kind != ReminderKind.WEIGH)
        val started = clock.get().atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
        jdbc.update("UPDATE settings SET scheduling_started_at=? WHERE user_id=?", Timestamp.from(started), user)
        return user
    }

    private fun dailyCondition(user: UUID, kind: ReminderKind, eligible: Boolean, version: Int) {
        val type: String
        val aggregate: String
        val payload: Map<String, Any?>
        if (kind == ReminderKind.WEIGH) {
            type = "health.weight.changed"
            aggregate = "weight"
            payload = if (eligible) mapOf("userId" to user, "measurementId" to user, "deleted" to true)
                else mapOf("userId" to user, "measurementId" to user, "deleted" to false,
                    "measuredAt" to clock.get().minusSeconds(60).toString(), "valueKg" to 70)
        } else {
            type = "progress.day.updated"
            aggregate = "progress_day"
            val consumed = if (kind == ReminderKind.CALORIES_EXCEEDED) {
                if (eligible) 2100 else 1900
            } else {
                if (eligible) 1500 else 1700
            }
            payload = mapOf("userId" to user, "localDate" to clock.get().atZone(ZoneOffset.UTC).toLocalDate().toString(),
                "timezone" to "UTC", "calories" to mapOf("consumed" to consumed, "target" to 2000, "unlimitedCalories" to false),
                "water" to mapOf("consumedMl" to null, "targetMl" to null))
        }
        events.handle("cookie.events.$type.v1", mapper.writeValueAsBytes(mapOf(
            "event_id" to UUID.randomUUID(), "event_type" to type, "event_version" to 1,
            "producer" to NotificationEventHandler.PRODUCERS.getValue(type), "aggregate_type" to aggregate,
            "aggregate_id" to user, "aggregate_version" to version, "occurred_at" to clock.get().toString(),
            "payload" to payload)))
    }

    private fun meal(user: UUID, meal: UUID, eatenAt: Instant, version: Int) {
        val type = "nutrition.meal.updated"
        events.handle("cookie.events.$type.v1", mapper.writeValueAsBytes(mapOf(
            "event_id" to UUID.randomUUID(), "event_type" to type, "event_version" to 1,
            "producer" to NotificationEventHandler.PRODUCERS.getValue(type), "aggregate_type" to "meal",
            "aggregate_id" to meal, "aggregate_version" to version, "occurred_at" to clock.get().toString(),
            "payload" to mapOf("userId" to user, "mealId" to meal, "eatenAt" to eatenAt.toString()))))
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:18.6-alpine3.24")
    }
}
