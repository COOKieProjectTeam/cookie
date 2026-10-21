package com.cookie.notification.persistence

import com.cookie.notification.delivery.JdbcDeliveryRepository
import com.cookie.notification.domain.*
import com.cookie.notification.messaging.NotificationQuarantine
import com.cookie.notification.scheduler.JdbcReminderStateLoader
import org.assertj.core.api.Assertions.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalTime
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Test-owned schemas and real login roles, including historical overbroad grants.
 * The optional URL must point at a disposable PostgreSQL where the test may create roles/schemas.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationReviewRegressionIntegrationTest {
    private var container: PostgreSQLContainer? = null
    private lateinit var url: String
    private lateinit var admin: JdbcTemplate
    private val schemas = mutableListOf<String>()
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @BeforeAll fun database() {
        val external = System.getenv("COOKIE_NOTIFICATION_TEST_DATABASE_URL")
        if (external == null) {
            container = PostgreSQLContainer("postgres:18-alpine").also { it.start() }
        }
        url = external ?: container!!.jdbcUrl
        admin = JdbcTemplate(DriverManagerDataSource(url,
            if (external == null) container!!.username else "postgres",
            if (external == null) container!!.password else ""))
        for (role in listOf("notification_migrator", "notification_app", "notification_replay", "notification_operator")) {
            admin.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='$role') THEN CREATE ROLE $role; END IF; END $$")
        }
        admin.execute("ALTER ROLE notification_migrator LOGIN PASSWORD 'review-migrator'")
        admin.execute("ALTER ROLE notification_app LOGIN PASSWORD 'review-app'")
        admin.execute("ALTER ROLE notification_replay NOLOGIN")
        admin.execute("ALTER ROLE notification_operator LOGIN PASSWORD 'review-operator'")
        admin.execute("GRANT notification_app TO notification_replay")
        admin.execute("GRANT notification_replay TO notification_operator")
    }

    @AfterEach fun clean() {
        schemas.forEach { admin.execute("DROP SCHEMA \"$it\" CASCADE") }
        schemas.clear()
    }
    @AfterAll fun stop() { container?.stop() }

    private class Fixture(val schema: String, val ownerSource: DriverManagerDataSource,
                          val source: DriverManagerDataSource) {
        val owner = JdbcTemplate(ownerSource)
        val jdbc = JdbcTemplate(source)
        val manager = DataSourceTransactionManager(source)
        val repository = JdbcNotificationRepository(jdbc, TransactionTemplate(manager))
    }
    private fun source(schema: String, role: String, password: String) =
        DriverManagerDataSource(url, role, password).apply {
            connectionProperties = Properties().apply { setProperty("currentSchema", schema) }
        }
    private fun fixture(target: String? = null): Fixture {
        val schema = "notification_review_" + UUID.randomUUID().toString().replace("-", "")
        schemas += schema
        admin.execute("CREATE SCHEMA \"$schema\" AUTHORIZATION notification_migrator")
        admin.execute("GRANT USAGE ON SCHEMA \"$schema\" TO notification_app")
        // This is the actual pre-fix default-grant behavior, including Flyway history.
        admin.execute("ALTER DEFAULT PRIVILEGES FOR ROLE notification_migrator IN SCHEMA \"$schema\" GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO notification_app")
        val owner = source(schema, "notification_migrator", "review-migrator")
        val config = Flyway.configure().dataSource(owner).schemas(schema)
        if (target != null) config.target(target)
        config.load().migrate()
        return Fixture(schema, owner, source(schema, "notification_app", "review-app"))
    }

    @ParameterizedTest @EnumSource(DevicePlatform::class)
    fun `cross platform registration transfers exclusive FCM ownership`(first: DevicePlatform) {
        val f = fixture()
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val second = if (first == DevicePlatform.IOS) DevicePlatform.ANDROID else DevicePlatform.IOS
        f.repository.registerDevice(a, RegisterDevice(first, "a", "same-fcm-token"))
        f.repository.registerDevice(b, RegisterDevice(second, "b", "same-fcm-token"))
        assertThat(f.repository.devices(a)).isEmpty()
        assertThat(f.repository.devices(b)).hasSize(1)
        assertThat(f.jdbc.queryForObject("SELECT count(*) FROM push_devices WHERE enabled", Int::class.java)).isEqualTo(1)
        val start = CountDownLatch(1)
        val transfers = (1..8).map { i -> CompletableFuture.runAsync {
            start.await()
            f.repository.registerDevice(UUID.randomUUID(), RegisterDevice(
                if (i % 2 == 0) first else second, "concurrent-$i", "same-fcm-token"))
        } }
        start.countDown()
        transfers.forEach { it.get(10, TimeUnit.SECONDS) }
        assertThat(f.jdbc.queryForObject("SELECT count(*) FROM push_devices WHERE enabled", Int::class.java)).isEqualTo(1)
    }

    @Test fun `upgrade merges cross platform owners preserving latest registration and terminal evidence`() {
        val f = fixture("6")
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        fun insert(user: UUID, platform: String, at: String) {
            f.owner.update("""INSERT INTO push_devices(id,user_id,platform,device_id,push_token,created_at,updated_at)
                VALUES (?,?,?,'device','same-fcm-token',?::timestamptz,?::timestamptz)""",
                UUID.randomUUID(), user, platform, at, at)
        }
        insert(a, "ANDROID", "2026-09-17T00:00:00Z")
        insert(b, "IOS", "2026-09-18T00:00:00Z")
        val email = UUID.randomUUID()
        JdbcDeliveryRepository(f.owner, DataSourceTransactionManager(f.ownerSource))
            .enqueueEmail(email, UUID.randomUUID(), "cipher", Instant.now().plusSeconds(600), Instant.now())
        f.owner.update("UPDATE delivery_jobs SET status='UNKNOWN',encrypted_payload=NULL WHERE id=?", email)
        Flyway.configure().dataSource(f.ownerSource).schemas(f.schema).load().migrate()
        assertThat(f.repository.devices(a)).isEmpty()
        assertThat(f.repository.devices(b)).hasSize(1)
        assertThat(f.jdbc.queryForObject("SELECT status FROM delivery_jobs WHERE id=?", String::class.java, email)).isEqualTo("UNKNOWN")
        assertThatThrownBy {
            f.owner.update("""INSERT INTO push_devices(id,user_id,platform,device_id,push_token,created_at,updated_at)
                VALUES (?,?,'ANDROID','new-device','same-fcm-token',now(),now())""", UUID.randomUUID(), a)
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test fun `in flight push holds the token fence against an opposite platform transfer`() {
        val f = fixture()
        val user = UUID.randomUUID()
        val device = f.repository.registerDevice(user, RegisterDevice(DevicePlatform.ANDROID, "a", "shared-token")).device
        f.repository.updateSettings(user, SettingsPatch(timezone="UTC", quietStart=LocalTime.MIDNIGHT, quietEnd=LocalTime.MIDNIGHT))
        val id = UUID.randomUUID()
        f.jdbc.update("INSERT INTO reminder_occurrences(id,user_id,kind,cycle_key,scheduled_at) VALUES (?,?,'FOOD','test',now())", id, user)
        val deliveries = JdbcDeliveryRepository(f.jdbc, f.manager)
        deliveries.enqueuePush(id, user, device.id, "FOOD", "ru", Instant.now().plusSeconds(600))
        val job = deliveries.claim(1).single()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val sending = CompletableFuture.runAsync {
            assertThat(deliveries.withPushSubmission(job) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                deliveries.markPushSubmission(job)
                deliveries.finish(job, "ACCEPTED", "PROVIDER_ACCEPTED")
            }).isTrue()
        }
        check(entered.await(5, TimeUnit.SECONDS))
        val moving = source(f.schema, "notification_app", "review-app")
        moving.connectionProperties = Properties().apply {
            setProperty("currentSchema", f.schema); setProperty("ApplicationName", f.schema)
        }
        val other = JdbcNotificationRepository(JdbcTemplate(moving), TransactionTemplate(DataSourceTransactionManager(moving)))
        val transfer = CompletableFuture.runAsync {
            other.registerDevice(UUID.randomUUID(), RegisterDevice(DevicePlatform.IOS, "b", "shared-token"))
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (admin.queryForObject("""SELECT EXISTS(SELECT FROM pg_stat_activity
                WHERE application_name=? AND wait_event='advisory')""", Boolean::class.java, f.schema) != true &&
                !transfer.isDone && System.nanoTime() < deadline) Thread.sleep(10)
            assertThat(transfer.isDone).isFalse()
            assertThat(admin.queryForObject("""SELECT EXISTS(SELECT FROM pg_stat_activity
                WHERE application_name=? AND wait_event='advisory')""", Boolean::class.java, f.schema)).isTrue()
        } finally { release.countDown() }
        sending.get(10, TimeUnit.SECONDS); transfer.get(10, TimeUnit.SECONDS)
        assertThat(f.repository.devices(user)).isEmpty()
        assertThat(f.jdbc.queryForObject("SELECT status FROM delivery_jobs WHERE id=?", String::class.java, job.id)).isEqualTo("ACCEPTED")
    }

    @Test fun `runtime cannot erase tombstones history or replay evidence after fresh migration and reconvergence`() {
        val f = fixture()
        repeat(2) {
            val user = UUID.randomUUID()
            f.jdbc.update("INSERT INTO account_tombstones VALUES (?,?,now())", user, UUID.randomUUID())
            assertThatThrownBy { f.jdbc.update("DELETE FROM account_tombstones WHERE user_id=?", user) }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy { f.jdbc.update("UPDATE account_tombstones SET deleted_at=now()") }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy { f.jdbc.update("DELETE FROM flyway_schema_history") }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy { f.repository.settings(user) }.isInstanceOf(NotificationAccountDeleted::class.java)
            NotificationQuarantine(f.jdbc, mapper).record("test.subject", UUID.randomUUID().toString().toByteArray(), 1, "invalid_json")
            assertThatThrownBy { f.jdbc.update("UPDATE message_quarantine SET replayed_at=now()") }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy { f.jdbc.update("UPDATE message_quarantine SET message_hash=repeat('0',64)") }.isInstanceOf(DataAccessException::class.java)
            assertThat(f.jdbc.update("DELETE FROM message_quarantine")).isZero()
            assertThatThrownBy { f.jdbc.execute("SELECT notification_apply_runtime_grants()") }.isInstanceOf(DataAccessException::class.java)
            val operator = JdbcTemplate(source(f.schema, "notification_operator", "review-operator"))
            assertThat(operator.update("UPDATE message_quarantine SET replayed_at=now(),replay_attempts=replay_attempts+1")).isPositive()
            // Owner-administered retention age; app cannot forge created_at.
            f.owner.update("UPDATE message_quarantine SET created_at=now()-interval '31 days'")
            assertThat(f.jdbc.update("DELETE FROM message_quarantine")).isPositive()
            f.owner.execute("SELECT notification_apply_runtime_grants()")
        }
        f.owner.execute("CREATE TABLE future_metadata(id integer)")
        assertThatThrownBy { f.jdbc.update("INSERT INTO future_metadata VALUES (1)") }.isInstanceOf(DataAccessException::class.java)
        f.repository.registerDevice(UUID.randomUUID(), RegisterDevice(DevicePlatform.IOS, "allowed", "token"))
    }

    @Test fun `maintenance cannot expire a job whose deadline is concurrently extended`() {
        val f = fixture()
        val id = UUID.randomUUID()
        f.jdbc.update("""INSERT INTO delivery_jobs(id,dedup_key,channel,user_id,device_id,kind,expires_at)
            VALUES (?,?,'PUSH',?,?,'WEIGH',now()-interval '1 second')""",
            id, id.toString(), UUID.randomUUID(), UUID.randomUUID())
        val maintenanceSource = source(f.schema, "notification_app", "review-app").apply {
            connectionProperties = Properties().apply {
                setProperty("currentSchema", f.schema); setProperty("ApplicationName", f.schema)
            }
        }
        val maintenance = JdbcDeliveryRepository(JdbcTemplate(maintenanceSource), DataSourceTransactionManager(maintenanceSource))
        var completion: CompletableFuture<Void>? = null
        TransactionTemplate(f.manager).executeWithoutResult {
            f.jdbc.update("UPDATE delivery_jobs SET expires_at=now()+interval '1 hour' WHERE id=?", id)
            completion = CompletableFuture.runAsync { maintenance.maintain() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!completion!!.isDone && System.nanoTime() < deadline &&
                admin.queryForObject("""SELECT EXISTS(SELECT FROM pg_stat_activity
                    WHERE application_name=? AND wait_event_type='Lock')""", Boolean::class.java, f.schema) != true) {
                Thread.sleep(10)
            }
        }
        completion!!.get(10, TimeUnit.SECONDS)
        assertThat(f.jdbc.queryForObject("SELECT status FROM delivery_jobs WHERE id=?", String::class.java, id)).isEqualTo("PENDING")
    }

    @ParameterizedTest @ValueSource(strings = ["PENDING", "CLAIMED", "EXPIRED", "ACCEPTED"])
    fun `timezone change reconciles daily expiry without reopening submitted work`(status: String) {
        val f = fixture()
        val user = UUID.randomUUID()
        val repository = spy(f.repository)
        doReturn(Instant.parse("2026-09-17T23:00:00Z")).`when`(repository).now()
        repository.settings(user)
        f.jdbc.update("""UPDATE settings SET timezone='UTC',scheduling_started_at='2026-09-16T00:00:00Z',
            food_enabled=false,water_enabled=false,goal_warning_enabled=false,quiet_start='00:00',quiet_end='00:00'
            WHERE user_id=?""", user)
        for (kind in listOf("WEIGH", "CALORIES_EXCEEDED", "CALORIES_UNDER_TARGET")) {
            val id = UUID.randomUUID()
            f.jdbc.update("""INSERT INTO reminder_occurrences(id,user_id,kind,cycle_key,scheduled_at,last_submitted_at)
                VALUES (?,?,?,'2026-09-17','2026-09-17T08:00:00Z',?::timestamptz)""",
                id, user, kind, if (status == "ACCEPTED") "2026-09-17T12:00:00Z" else null)
            f.jdbc.update("""INSERT INTO delivery_jobs(id,dedup_key,channel,user_id,device_id,occurrence_id,kind,
                expires_at,status,last_code,provider_failure_count)
                VALUES (?,?,'PUSH',?,?,?,?,'2026-09-18T00:00:00Z',?,'EXPIRED',3)""",
                UUID.randomUUID(), id.toString(), user, UUID.randomUUID(), id, kind, status)
        }
        repository.updateSettings(user, SettingsPatch(timezone="America/New_York"))
        val expected = if (status == "ACCEPTED") "2026-09-18T00:00:00Z" else "2026-09-18T04:00:00Z"
        assertThat(f.jdbc.queryForList("SELECT expires_at FROM delivery_jobs", Timestamp::class.java).map { requireNotNull(it).toInstant() })
            .containsOnly(Instant.parse(expected))
        assertThat(f.jdbc.queryForList("SELECT status FROM delivery_jobs", String::class.java))
            .containsOnly(if (status == "EXPIRED") "PENDING" else status)
        assertThat(f.jdbc.queryForList("SELECT provider_failure_count FROM delivery_jobs", Int::class.java)).containsOnly(3)
        val state = JdbcReminderStateLoader(f.jdbc, repository, mapper).load(user)!!
        assertThat(ReminderPlanner.isEligible(state, ReminderKind.WEIGH, "2026-09-17",
            Instant.parse("2026-09-17T08:00:00Z"), Instant.parse("2026-09-18T01:00:00Z"))).isTrue()
        repository.updateSettings(user, SettingsPatch(timezone="Europe/Moscow"))
        if (status != "ACCEPTED") assertThat(f.jdbc.queryForList("SELECT expires_at FROM delivery_jobs", Timestamp::class.java)
            .map { requireNotNull(it).toInstant() }).containsOnly(Instant.parse("2026-09-17T21:00:00Z"))
    }
}
