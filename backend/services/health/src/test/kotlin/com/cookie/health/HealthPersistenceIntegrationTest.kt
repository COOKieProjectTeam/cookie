package com.cookie.health

import com.cookie.health.config.HealthProperties
import com.cookie.health.messaging.HealthBroker
import com.cookie.health.messaging.HealthDeletion
import com.cookie.health.messaging.HealthPublisher
import com.cookie.health.persistence.HealthDatabase
import com.cookie.health.persistence.HealthImports
import com.cookie.health.persistence.HealthMeasurements
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.nats.client.JetStreamOptions
import io.nats.client.Nats
import io.nats.client.PullSubscribeOptions
import com.cookie.platform.messaging.DevelopmentJetStreamTopology
import org.testcontainers.images.builder.Transferable
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mock.env.MockEnvironment
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HealthPersistenceIntegrationTest {
    private lateinit var owner: JdbcTemplate
    private lateinit var db: HealthDatabase
    private lateinit var measurements: HealthMeasurements
    private lateinit var imports: HealthImports
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val instant = Instant.parse("2026-11-18T12:00:00Z")
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)

    @BeforeAll
    fun init() {
        val admin = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        owner = JdbcTemplate(admin)
        owner.execute("CREATE ROLE health_app LOGIN PASSWORD 'test-only-runtime'")
        Flyway.configure().dataSource(admin).load().migrate()
        val runtime = DriverManagerDataSource(postgres.jdbcUrl, "health_app", "test-only-runtime")
        db = HealthDatabase(JdbcTemplate(runtime), TransactionTemplate(DataSourceTransactionManager(runtime)))
        measurements = HealthMeasurements(db, mapper, clock)
        imports = HealthImports(measurements, clock)
    }

    @BeforeEach
    fun cleanup() {
        owner.execute("TRUNCATE account_tombstones,inbox_events,connections,command_receipts,outbox_events,assessment_executions CASCADE")
    }

    @Test
    fun batchAtomicityAndFencing() {
        val user = UUID.randomUUID()
        val id = connection(user, "HEALTH_CONNECT")
        val key = UUID.randomUUID()
        val first = batch(1, 1, activity(6000), weight(72))
        val result = imports.applyBatch(user, id, key, first)
        assertThat(measurements.fingerprint(imports.applyBatch(user, id, key, first))).isEqualTo(measurements.fingerprint(result))
        assertThatThrownBy { imports.applyBatch(user, id, UUID.randomUUID(), first) }.hasMessage("IDEMPOTENCY_CONFLICT")

        val invalid = batch(1, 2, activity(7400), weight(-1))
        assertThatThrownBy { imports.applyBatch(user, id, UUID.randomUUID(), invalid) }.isInstanceOfSatisfying(HealthFailure::class.java) {
            assertThat(it.index).isEqualTo(1)
        }
        assertThat(owner.queryForObject("SELECT steps FROM activity_source_days", Int::class.java)).isEqualTo(6000)
        assertThat(owner.queryForObject("SELECT next_seq FROM connections", Long::class.java)).isEqualTo(2)
        val second = batch(1, 2, activity(7400), weight(71))
        val corrected = imports.applyBatch(user, id, UUID.randomUUID(), second)
        assertThat(corrected.path("items")[1].path("measurementId")).isEqualTo(result.path("items")[1].path("measurementId"))
        imports.applyBatch(user, id, UUID.randomUUID(), batch(1,3,activity(6100)))

        imports.applyBatch(user, id, key, first)
        assertThat(owner.queryForObject("SELECT steps FROM activity_source_days", Int::class.java)).isEqualTo(6100)
        assertThatThrownBy { imports.applyBatch(UUID.randomUUID(), id, key, first) }.hasMessage("NOT_FOUND")
        imports.changeConnection(user, id, 1, UUID.randomUUID(), resume = true)
        assertThatThrownBy { imports.applyBatch(user, id, key, first) }.hasMessage("STALE_GENERATION")
    }

    @Test
    fun appleReplacementDoesNotResurrectDeletedUuid() {
        val user = UUID.randomUUID()
        val id = connection(user, "APPLE_HEALTH")
        val oldUuid = "00000000-0000-4000-8000-000000000010"
        val newUuid = "00000000-0000-4000-8000-000000000011"
        val old = read("""{"op":"WEIGHT_UPSERT","externalId":"$oldUuid","valueKg":70,"measuredAt":"2026-11-18T11:30:00Z","measurementOffsetMinutes":0}""")
        val first = imports.applyBatch(user, id, UUID.randomUUID(), batch(1, 1, old))
        val deletion = read("""{"op":"WEIGHT_DELETE","externalId":"$oldUuid"}""")
        val replacement = read("""{"op":"WEIGHT_UPSERT","externalId":"$newUuid","valueKg":71,"measuredAt":"2026-11-18T11:30:00Z","measurementOffsetMinutes":0}""")
        val second = imports.applyBatch(user, id, UUID.randomUUID(), batch(1, 2, deletion, replacement))
        assertThat(second.path("items")[0].path("measurementId")).isEqualTo(first.path("items")[0].path("measurementId"))
        assertThat(second.path("items")[0].path("version").asLong()).isEqualTo(2)
        assertThat(second.path("items")[1].path("measurementId")).isNotEqualTo(first.path("items")[0].path("measurementId"))
        assertThat(imports.applyBatch(user, id, UUID.randomUUID(), batch(1, 3, old, deletion))
            .path("items").toList().map { it.path("status").asString() }).containsOnly("ALREADY_DELETED")
        val page = measurements.page(user,null,null,null,null,20)
        assertThat(page.path("items").size()).isEqualTo(1)
        assertThat(page.path("items")[0].path("measurementId")).isEqualTo(second.path("items")[1].path("measurementId"))
    }

    @Test
    fun initialWindowAllowsAnExplicitCorrectionOfAKnownOldMeasurement() {
        val user = UUID.randomUUID()
        val id = connection(user, "HEALTH_CONNECT")
        val historical = read(weight(71).toString().replace("2026-11-18","2026-09-18"))
        assertThatThrownBy { imports.applyBatch(user, id, UUID.randomUUID(), batch(1, 1, historical)) }
            .isInstanceOfSatisfying(HealthFailure::class.java) { assertThat(it.field).isEqualTo("measuredAt") }
        val first = imports.applyBatch(user, id, UUID.randomUUID(), batch(1, 1, weight(72)))
        val correction = imports.applyBatch(user, id, UUID.randomUUID(), batch(1, 2, historical))
        assertThat(correction.path("items")[0].path("measurementId")).isEqualTo(first.path("items")[0].path("measurementId"))
        assertThat(correction.path("items")[0].path("version").asLong()).isEqualTo(2)
    }

    @Test
    fun weightImportPersistsMeasurementDateAndRevisesOffsetAtSameInstant() {
        val user = UUID.randomUUID()
        val id = connection(user, "HEALTH_CONNECT")
        val original = weight(72, -720)
        val first = imports.applyBatch(user, id, UUID.randomUUID(), batch(1, 1, original)).path("items")[0]
        val stored = requireNotNull(measurements.byExternal(user, id, "hc-42"))
        assertThat(stored.measurementLocalDate.toString()).isEqualTo("2026-11-17")

        val corrected = weight(72, 840)
        val second = imports.applyBatch(user, id, UUID.randomUUID(), batch(1, 2, corrected)).path("items")[0]
        val revised = requireNotNull(measurements.byExternal(user, id, "hc-42"))
        assertThat(second.path("measurementId")).isEqualTo(first.path("measurementId"))
        assertThat(second.path("version").asLong()).isEqualTo(2)
        assertThat(revised.measuredAt?.toInstant()).isEqualTo(stored.measuredAt?.toInstant())
        assertThat(revised.measurementOffsetMinutes).isEqualTo(840)
        assertThat(revised.measurementLocalDate.toString()).isEqualTo("2026-11-19")
        val repeated = imports.applyBatch(user, id, UUID.randomUUID(), batch(1, 3, corrected)).path("items")[0]
        assertThat(repeated.path("status").asString()).isEqualTo("UNCHANGED")
        assertThat(repeated.path("version").asLong()).isEqualTo(2)
        assertThat(owner.queryForList(
            """SELECT payload->>'measurementLocalDate' FROM outbox_events
               WHERE user_id=? AND event_type='health.weight.changed' AND event_version=2 ORDER BY aggregate_version""",
            String::class.java, user,
        )).containsExactly("2026-11-17", "2026-11-19")
    }

    @Test
    fun weightImportRejectsMissingFractionalAndOutOfBoundsOffsets() {
        val user = UUID.randomUUID()
        val id = connection(user, "HEALTH_CONNECT")
        for (offset in listOf(null, "null", "1.5", "1081", "-1081", "4294967296")) {
            val item = weight(72) as ObjectNode
            if (offset == null) item.remove("measurementOffsetMinutes") else item.set("measurementOffsetMinutes", read(offset))
            assertThatThrownBy { imports.applyBatch(user, id, UUID.randomUUID(), batch(1, 1, item)) }
                .isInstanceOfSatisfying(HealthFailure::class.java) { assertThat(it.field).isEqualTo("measurementOffsetMinutes") }
        }
        val accepted = imports.applyBatch(user, id, UUID.randomUUID(), batch(1, 1, weight(72, 1080), weight(72, -1080)))
        assertThat(accepted.path("items").toList().map { it.path("status").asString() }).containsExactly("APPLIED", "APPLIED")
    }

    @Test
    fun connectionChangesPublishOnlyChangedActiveCounts() {
        val user = UUID.randomUUID()
        val id = connection(user, "HEALTH_CONNECT")
        val disconnect = UUID.randomUUID()
        imports.changeConnection(user, id, 1, disconnect, resume = false)
        imports.changeConnection(user, id, 1, disconnect, resume = false)
        imports.changeConnection(user, id, 2, UUID.randomUUID(), resume = false)
        imports.changeConnection(user, id, 2, UUID.randomUUID(), resume = true)
        val purge = UUID.randomUUID()
        imports.startPurge(user, id, purge, 3)
        imports.startPurge(user, id, purge, 3)
        val changes = owner.query(
            """SELECT aggregate_version,payload->>'activeConnectionCount' FROM outbox_events
               WHERE user_id=? AND event_type='health.connection_summary.changed' AND event_version=1 ORDER BY aggregate_version""",
            { row, _ -> row.getLong(1) to row.getLong(2) }, user,
        )
        assertThat(changes).containsExactly(1L to 1L, 2L to 0L, 3L to 1L, 4L to 0L)
    }

    @Test
    fun cleanupRetainsEvidenceAndRejectsEarlyPrimaryChange() {
        val user = UUID.randomUUID()
        val old = connection(user, "APPLE_HEALTH")
        val next = connection(user, "HEALTH_CONNECT")
        imports.setPrimary(user, UUID.randomUUID(), next, 1, "Pacific/Kiritimati")
        val premature = read("""{"op":"ACTIVITY_SNAPSHOT","localDate":"2026-11-19","timezone":"Pacific/Kiritimati","steps":42,"activeKcal":null}""")
        assertThatThrownBy { imports.applyBatch(user, next, UUID.randomUUID(), batch(1, 1, premature)) }.hasMessage("INVALID_REQUEST")

        val key = UUID.randomUUID()
        val appleWeight = read("""{"op":"WEIGHT_UPSERT","externalId":"00000000-0000-4000-8000-000000000010","valueKg":70,"measuredAt":"2026-11-18T11:30:00Z","measurementOffsetMinutes":0}""")
        val immutablePacket = batch(1, 1, appleWeight)
        imports.applyBatch(user, old, key, immutablePacket)
        owner.update("UPDATE import_batches SET created_at=now()-interval '31 days'")
        imports.scrubImportMetadata()
        assertThat(owner.queryForObject("SELECT response IS NULL FROM import_batches", Boolean::class.java)).isTrue()
        assertThat(imports.applyBatch(user, old, key, immutablePacket).path("status").asString()).isEqualTo("ALREADY_APPLIED")
        assertThat(db.jdbc.update("DELETE FROM import_batches")).isZero()
        assertThat(db.jdbc.update("DELETE FROM primary_connection_periods")).isZero()
        assertThat(db.jdbc.update("DELETE FROM weight_measurements")).isZero()
        assertThat(db.jdbc.update("DELETE FROM connections")).isZero()
        assertThatThrownBy { db.jdbc.update("UPDATE connections SET timezone='Europe/Paris'") }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        val purge = imports.startPurge(user, old, UUID.randomUUID(), 1)
        repeat(3) { imports.finishPurges() }
        assertThat(imports.purge(user, old, UUID.fromString(purge.path("purgeId").asString())).path("status").asString()).isEqualTo("COMPLETED")
        assertThat(owner.queryForObject("SELECT count(*) FROM weight_measurements", Int::class.java)).isZero()
        assertThat(owner.queryForObject("SELECT count(*) FROM outbox_events WHERE jsonb_exists(payload,'valueKg')", Int::class.java)).isZero()
    }

    @Test
    fun accountDeletionAndJetStreamOutbox() {
        val user = UUID.randomUUID()
        val other = UUID.randomUUID()
        val key = UUID.randomUUID()
        val measured = OffsetDateTime.parse("2026-11-18T11:00:00Z")
        val weight = measurements.addManual(user, key, 70.toBigDecimal(), measured, 0)
        assertThat(measurements.fingerprint(measurements.addManual(user, key, 70.toBigDecimal(), measured, 0))).isEqualTo(measurements.fingerprint(weight))
        val props = HealthProperties().apply { natsUrl = "nats://admin:fixture-only@${nats.host}:${nats.getMappedPort(4222)}" }
        val broker = HealthBroker(props, MockEnvironment().withProperty("spring.profiles.active", "test"))
        try {
            HealthPublisher(broker, db, props, SimpleMeterRegistry(), mapper).poll()
            val event = broker.connection().jetStreamManagement().getLastMessage(HealthBroker.STREAM, "cookie.events.health.weight.changed.v2")
            assertThat(mapper.readTree(event.data).path("payload").path("measurementId")).isEqualTo(weight.path("measurementId"))
        } finally { broker.close() }

        val deletion = mapper.writeValueAsBytes(mapOf(
            "event_id" to UUID.randomUUID(), "event_type" to HealthDeletion.TYPE, "event_version" to 1,
            "producer" to "identity", "aggregate_type" to "account", "aggregate_id" to user,
            "occurred_at" to instant, "payload" to mapOf("accountId" to user, "deletionRequestId" to UUID.randomUUID(), "requestedAt" to instant),
        ))
        val consumer = HealthDeletion(db, mapper)
        consumer.handle(HealthDeletion.SUBJECT, deletion)
        consumer.handle(HealthDeletion.SUBJECT, deletion)
        assertThat(owner.queryForObject("SELECT count(*) FROM inbox_events", Int::class.java)).isEqualTo(1)
        assertThat(owner.queryForObject("SELECT count(*) FROM outbox_events WHERE user_id=?", Int::class.java, user)).isZero()
        assertThatThrownBy { measurements.addManual(user, key, 70.toBigDecimal(), measured, 0) }.hasMessage("NOT_FOUND")
        assertThatThrownBy { db.jdbc.update("DELETE FROM account_tombstones") }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        assertThat(measurements.addManual(other, key, 70.toBigDecimal(), measured, 0).path("source").asString()).isEqualTo("MANUAL")
    }

    @Test
    fun deletionSerializesWithWeightWritesInBothOrders() {
        for (deleteFirst in listOf(false,true)) {
            val user = UUID.randomUUID()
            val payload = mapper.writeValueAsBytes(mapOf(
                "event_id" to UUID.randomUUID(), "event_type" to HealthDeletion.TYPE, "event_version" to 1,
                "producer" to "identity", "aggregate_type" to "account", "aggregate_id" to user, "occurred_at" to instant,
                "payload" to mapOf("accountId" to user, "deletionRequestId" to UUID.randomUUID(), "requestedAt" to instant),
            ))
            val lifecycle = HealthDeletion(db,mapper)
            fun delete() = lifecycle.handle(HealthDeletion.SUBJECT,payload)
            fun write() { measurements.addManual(user,UUID.randomUUID(),70.toBigDecimal(),instant.minusSeconds(1).atOffset(ZoneOffset.UTC), 0) }

            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val winner = CompletableFuture.runAsync {
                db.transactions.executeWithoutResult {
                    db.lock(user)
                    locked.countDown()
                    check(release.await(5,TimeUnit.SECONDS))
                    if (deleteFirst) delete() else write()
                }
            }
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue()
            val contender = CompletableFuture.supplyAsync { runCatching { if (deleteFirst) write() else delete() } }
            try {
                assertThatThrownBy { contender.get(200,TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
            } finally { release.countDown() }
            winner.get(10,TimeUnit.SECONDS)
            val result = contender.get(10,TimeUnit.SECONDS)
            if (deleteFirst) assertThat(result.exceptionOrNull()).hasMessage("NOT_FOUND")
            else assertThat(result.isSuccess).isTrue()
            assertThat(owner.queryForObject("SELECT count(*) FROM weight_measurements WHERE user_id=?",Int::class.java,user)).isZero()
            assertThat(owner.queryForObject("SELECT count(*) FROM outbox_events WHERE user_id=?",Int::class.java,user)).isZero()
            assertThat(db.deleted(user)).isTrue()
        }
    }

    @Test
    fun restrictedBrokerRoleCanBindAndPublishOwnedSubjectsOnly() {
        val host = "${nats.host}:${nats.getMappedPort(4222)}"
        Nats.connect("nats://admin:fixture-only@$host").use { admin ->
            DevelopmentJetStreamTopology.ensure(admin, HealthBroker.STREAM, HealthDeletion.CONSUMER,
                listOf(HealthDeletion.SUBJECT, HealthDeletion.ACTIVATION, "cookie.events.user.goal_assessment.requested.v1"))
        }
        val properties = HealthProperties().apply {
            natsUrl = "nats://health:fixture-only@$host"
            provisionLocalBroker = false
        }
        val broker = HealthBroker(properties, MockEnvironment().withProperty("spring.profiles.active", "test"))
        try {
            val connection = broker.connection()
            val options = JetStreamOptions.builder().requestTimeout(Duration.ofSeconds(2)).build()
            val admin = connection.jetStreamManagement(options)
            assertThat(admin.getConsumerInfo(HealthBroker.STREAM, HealthDeletion.CONSUMER).name).isEqualTo(HealthDeletion.CONSUMER)
            val js = connection.jetStream(options)
            val account = UUID.randomUUID()
            val deletion = mapper.writeValueAsBytes(mapOf(
                "event_id" to UUID.randomUUID(), "event_type" to HealthDeletion.TYPE, "event_version" to 1,
                "producer" to "identity", "aggregate_type" to "account", "aggregate_id" to account, "occurred_at" to instant,
                "payload" to mapOf("accountId" to account, "deletionRequestId" to UUID.randomUUID(), "requestedAt" to instant),
            ))
            Nats.connect("nats://admin:fixture-only@$host").use { publisher ->
                publisher.jetStream().publish(HealthDeletion.SUBJECT, deletion)
            }
            val durable = js.subscribe(null, PullSubscribeOptions.bind(HealthBroker.STREAM, HealthDeletion.CONSUMER))
            val first = durable.fetch(1, Duration.ofSeconds(5)).single()
            val handler = HealthDeletion(db, mapper)
            handler.handle(first.subject, first.data)
            // The first delivery committed without an ACK. Request redelivery and ACK only after processing it again.
            first.nak()
            val duplicate = durable.fetch(1, Duration.ofSeconds(5)).single()
            handler.handle(duplicate.subject, duplicate.data)
            duplicate.ackSync(Duration.ofSeconds(3))
            assertThat(owner.queryForObject("SELECT count(*) FROM inbox_events", Int::class.java)).isEqualTo(1)
            assertThat(db.deleted(account)).isTrue()
            assertThat(js.publish("cookie.events.health.weight.changed.v2", "{}".toByteArray()).seqno).isPositive()
            assertThat(js.publish("cookie.events.health.connection_summary.changed.v1", "{}".toByteArray()).seqno).isPositive()
            assertThatThrownBy { js.publish("cookie.events.account.activated.v1", "{}".toByteArray()) }
                .isInstanceOf(Exception::class.java)
            assertThatThrownBy { admin.getLastMessage(HealthBroker.STREAM, "cookie.events.health.weight.changed.v2") }
                .isInstanceOf(Exception::class.java)
        } finally { broker.close() }
    }

    private fun connection(user: UUID, platform: String) = UUID.fromString(
        imports.register(user, UUID.randomUUID(), platform, "UTC").path("connectionId").asString(),
    )

    private fun batch(generation: Int, sequence: Int, vararg items: JsonNode): JsonNode =
        mapper.valueToTree(mapOf("generation" to generation, "seq" to sequence, "items" to items))
    private fun activity(steps: Int) = read("""{"op":"ACTIVITY_SNAPSHOT","localDate":"2026-11-18","timezone":"UTC","steps":$steps,"activeKcal":null}""")
    private fun weight(kg: Int, offset: Int = 0) = read("""{"op":"WEIGHT_UPSERT","externalId":"hc-42","valueKg":$kg,"measuredAt":"2026-11-18T11:30:00Z","measurementOffsetMinutes":$offset}""")
    private fun read(value: String): JsonNode = mapper.readTree(value)

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:18.6-alpine3.24")
        private val brokerConfig = """
            jetstream {}
            authorization {
              users = [
                {user: admin, password: fixture-only},
                {user: health, password: fixture-only, permissions: {
                  publish: {allow: [
                    "cookie.events.health.weight.changed.v2",
                    "cookie.events.health.connection_summary.changed.v1",
                    "cookie.events.health.activity.changed.v2",
                    "cookie.events.health.goal_assessment.resolved.v1",
                    "${'$'}JS.API.CONSUMER.INFO.COOKIE_EVENTS.health-lifecycle-v1",
                    "${'$'}JS.API.CONSUMER.MSG.NEXT.COOKIE_EVENTS.health-lifecycle-v1",
                    "${'$'}JS.ACK.COOKIE_EVENTS.health-lifecycle-v1.>"
                  ]},
                  subscribe: {allow: ["_INBOX.cookie.health.>"]}
                }}
              ]
            }
        """.trimIndent()
        @Container @JvmStatic val nats = GenericContainer("nats:2.12-alpine").withExposedPorts(4222)
            .withCopyToContainer(Transferable.of(brokerConfig), "/etc/nats/test.conf").withCommand("-c", "/etc/nats/test.conf")
    }
}
