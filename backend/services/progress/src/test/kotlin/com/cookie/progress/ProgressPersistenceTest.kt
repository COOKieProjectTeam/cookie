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
