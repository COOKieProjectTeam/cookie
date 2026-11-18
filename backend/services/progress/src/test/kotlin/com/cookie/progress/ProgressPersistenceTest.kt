package com.cookie.progress

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.Clock
import java.util.UUID

@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProgressPersistenceTest {
    private val mapper = JsonMapper.builder().findAndAddModules().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
    private lateinit var store: ProgressStore
    private lateinit var inbox: ProgressInbox

    @BeforeAll
    fun migrate() {
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        JdbcTemplate(source).execute("CREATE ROLE progress_app LOGIN PASSWORD 'local-integration'")
        Flyway.configure().dataSource(source).load().migrate()
        val runtime = DriverManagerDataSource(postgres.jdbcUrl, "progress_app", "local-integration")
        store = ProgressStore(JdbcTemplate(runtime), TransactionTemplate(DataSourceTransactionManager(runtime)), mapper)
        inbox = ProgressInbox(store, mapper)
    }

    @Test
    fun `complete source groups atomically in sequence without blocking independent owners`() {
        val user = UUID.randomUUID()
        val other = UUID.randomUUID()
        receive(user, "nutrition.progress_commit.recorded", "nutrition", data = mapOf(
            "firstSeq" to 1, "lastSeq" to 2, "committedAt" to "2026-11-18T20:50:00Z", "endLsn" to "0/A100",
        ))
        val lateId = UUID.randomUUID()
        food(user, "2026-11-19", 2, 1, lateId)
        assertEquals(0L, store.cursor(user, "nutrition"))
        assertNull(store.latest(user, "nutrition.day.changed", "2026-11-19"))
        receive(user, "health.connection_summary.changed", "health", revision = 1, data = mapOf("activeConnectionCount" to 0))
        assertNotNull(store.latest(user, "health.connection_summary.changed", user.toString()))
        assertNull(store.latest(other, "health.connection_summary.changed", user.toString()))
        food(user, "2026-11-18", 1, 2)
        assertEquals(2L, store.cursor(user, "nutrition"))
        food(user, "2026-11-19", 2, 1, lateId)
        assertEquals("9000000000000.001", store.latest(user, "nutrition.day.changed", "2026-11-19")!!
            .payload.path("nutritionTotals").quantity("calories")!!.toPlainString())
        assertThrows(DataAccessException::class.java) { store.jdbc.update("UPDATE source_facts SET payload='{}'") }
        assertEquals(0, store.jdbc.update("DELETE FROM progress_accounts WHERE user_id=?", user))
        receive(user, "account.deletion.requested", "identity", data = mapOf(
            "accountId" to user, "deletionRequestId" to UUID.randomUUID(), "requestedAt" to "2026-11-18T21:00:00Z",
        ))
        food(user, "2026-11-19", 3, 2)
        assertTrue(store.deleted(user))
        assertNull(store.latest(user, "nutrition.day.changed", "2026-11-19"))
        assertEquals(0L, store.cursor(user, "nutrition"))
    }

    @Test
    fun `midnight waits for both cuts and late corrections do not rewrite achievements`() {
        val user = UUID.randomUUID()
        val date = LocalDate.parse("2026-11-18")
        val boundary = Instant.parse("2026-11-18T21:00:00Z")
        val calendar = ProgressCalendar(store, mapper, Clock.systemUTC())
        userBaseline(user)
        group(user, "user", 1, 2, "2026-11-18T09:00:00Z", "0/B100")
        calendar.run(user, boundary.minusSeconds(30))
        assertEquals("OPEN", calendar.active(user)?.state)
        assertEquals(boundary, calendar.active(user)?.closesAt)
        food(user, date.toString(), 2, 2, calories = "2490.000", protein = "100")
        group(user, "nutrition", 2, 2, boundary.toString(), "0/B102")
        cut(user, "nutrition", boundary, 1, 2, "0/B103")
        cut(user, "user", boundary, 2, 2, "0/B104")
        calendar.run(user, boundary.plusSeconds(5))
        assertEquals("CLOSING", calendar.active(user)?.state)
        assertNull(calendar.confirmed(user, date))
        food(user, date.toString(), 1, 1, calories = "2000.000", protein = "100")
        group(user, "nutrition", 1, 1, "2026-11-18T20:59:58Z", "0/B101")
        calendar.run(user, boundary.plusSeconds(6))
        val result = calendar.confirmed(user, date)!!
        assertEquals("MACROS_MET", result.result.outcome.name)
        assertEquals(1L, result.after)
        assertEquals("2490.000", store.latest(user, "nutrition.day.changed", date.toString()).food().calories!!.toPlainString())
        assertEquals("2000.000", result.facts.path("nutritionTotals").quantity("calories")!!.toPlainString())
        calendar.run(user, boundary.plusSeconds(7))
        assertEquals(1L, calendar.streak(user).first.best)
        assertEquals(date.plusDays(1), calendar.active(user)?.date)
        assertEquals(1L, calendar.confirmed(user, date)?.after)
    }

    @Test
    fun `westward travel retains the fixed closing and does not open a repeated date`() {
        val user = UUID.randomUUID()
        val date = LocalDate.parse("2026-11-18")
        val boundary = Instant.parse("2026-11-18T21:00:00Z")
        val calendar = ProgressCalendar(store, mapper, Clock.systemUTC())
        userBaseline(user)
        group(user, "user", 1, 2, "2026-11-18T09:00:00Z", "0/C100")
        calendar.run(user, boundary.minusSeconds(200))
        receive(user, "user.profile.updated", "user", revision = 3, data = mapOf(
            "sourceSeq" to 3, "timezone" to "Europe/Berlin", "baseline" to false, "hasAcceptedGoal" to true, "goalRevision" to 1,
        ))
        group(user, "user", 3, 3, "2026-11-18T20:58:00Z", "0/C101")
        cut(user, "user", boundary, 3, 3, "0/C102")
        cut(user, "nutrition", boundary, 0, 0, "0/C103")
        calendar.run(user, boundary.plusSeconds(1))
        val berlinOpening = boundary.plusSeconds(7200)
        calendar.run(user, berlinOpening.minusSeconds(1))
        assertEquals("CLOSED", calendar.day(user, date)?.state)
        assertEquals("Europe/Moscow", calendar.day(user, date)?.zone?.id)
        assertNull(calendar.active(user))
        cut(user, "user", berlinOpening, 3, 3, "0/C104")
        calendar.run(user, berlinOpening.plusSeconds(1))
        val next = calendar.active(user)!!
        assertEquals(date.plusDays(1), next.date)
        assertEquals("Europe/Berlin", next.zone.id)
        assertEquals(berlinOpening, next.openedAt)
    }

    @Test
    fun `local midnight follows the opening timezone across daylight saving time`() {
        listOf(
            Triple("2026-03-08T05:00:00Z", "2026-03-09T04:00:00Z", 23L),
            Triple("2026-11-01T04:00:00Z", "2026-11-02T05:00:00Z", 25L),
        ).forEach { (start, end, hours) ->
            val user = UUID.randomUUID()
            val calendar = ProgressCalendar(store, mapper, Clock.systemUTC())
            receive(user, "user.profile.updated", "user", revision = 1, data = mapOf(
                "sourceSeq" to 1, "timezone" to "America/New_York", "baseline" to true, "hasAcceptedGoal" to false, "goalRevision" to 0,
            ))
            group(user, "user", 1, 1, start, "0/D100")
            calendar.run(user, Instant.parse(start))
            val day = calendar.active(user)!!
            assertEquals(Instant.parse(end), day.closesAt)
            assertEquals(hours, java.time.Duration.between(day.openedAt, day.closesAt).toHours())
        }
    }

    private fun userBaseline(user: UUID) {
        receive(user, "user.profile.updated", "user", revision = 1, data = mapOf(
            "sourceSeq" to 1, "timezone" to "Europe/Moscow", "baseline" to true, "hasAcceptedGoal" to false, "goalRevision" to 0,
        ))
        fun band(low: Int, high: Int) = mapOf("kind" to "RANGE", "low" to low, "high" to high)
        receive(user, "user.goal.changed", "user", wire = 2, revision = 2, data = mapOf(
            "sourceSeq" to 2,
            "nutritionGoalVersion" to mapOf("id" to UUID.randomUUID(), "revision" to 1,
                "outcome" to "CALCULATED", "effectiveFrom" to "2026-11-18",
                "calories" to band(1800, 2200), "proteinG" to band(90, 120), "fatG" to band(8, 15), "carbsG" to band(10, 20)),
        ))
    }

    private fun group(user: UUID, owner: String, first: Long, last: Long, at: String, lsn: String) {
        receive(user, "$owner.progress_commit.recorded", owner,
            data = mapOf("firstSeq" to first, "lastSeq" to last, "committedAt" to at, "endLsn" to lsn))
    }

    private fun cut(user: UUID, owner: String, at: Instant, before: Long, through: Long, lsn: String) {
        receive(user, "$owner.progress_cut.confirmed", owner,
            data = mapOf("boundary" to at.toString(), "beforeSeq" to before, "throughSeq" to through, "endLsn" to lsn))
    }

    private fun food(
        user: UUID, day: String, seq: Long, revision: Long, id: UUID = UUID.randomUUID(),
        calories: String = "9000000000000.001", protein: String? = null,
    ) {
        receive(user, "nutrition.day.changed", "nutrition", wire = 3, revision = revision, id = id, data = mapOf(
            "localDate" to day, "mealCount" to 1, "sourceSeq" to seq,
            "zoneProvenance" to mapOf("state" to "SINGLE", "timezone" to "Europe/Moscow"),
            "nutritionTotals" to mapOf("calories" to calories.toBigDecimal(), "proteinG" to protein?.toBigDecimal(),
                "fatG" to 10, "carbsG" to 15, "fiberG" to null),
        ))
    }

    private fun receive(
        user: UUID, type: String, producer: String, wire: Int = 1, revision: Long? = null, id: UUID = UUID.randomUUID(),
        data: Map<String, Any?>,
    ) {
        val envelope = mapper.writeValueAsBytes(mapOf(
            "event_id" to id, "event_type" to type, "event_version" to wire, "occurred_at" to Instant.now().toString(),
            "producer" to producer, "aggregate_type" to if (producer == "identity") "account" else "progress_source",
            "aggregate_id" to user, "aggregate_version" to revision, "payload" to data + ("userId" to user),
        ))
        inbox.receive("cookie.events.$type.v$wire", envelope)
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:18-alpine")
    }
}
