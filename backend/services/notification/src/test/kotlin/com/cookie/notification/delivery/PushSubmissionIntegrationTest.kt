package com.cookie.notification.delivery

import com.cookie.notification.application.ports.PushEligibility
import com.cookie.notification.config.ApnsProperties
import com.cookie.notification.config.DeliveryProperties
import com.cookie.notification.config.FcmProperties
import com.cookie.notification.domain.DevicePlatform
import com.cookie.notification.domain.RegisterDevice
import com.cookie.notification.domain.SettingsPatch
import com.cookie.notification.messaging.NotificationEventHandler
import com.cookie.notification.persistence.JdbcNotificationRepository
import com.cookie.notification.scheduler.ProjectedPushEligibility
import com.cookie.notification.security.BoundedHttpExchange
import com.cookie.notification.security.VerificationPayloadDecryptor
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Tag("integration")
@Testcontainers
class PushSubmissionIntegrationTest {
    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private lateinit var notifications: JdbcNotificationRepository
    private lateinit var deliveries: JdbcDeliveryRepository
    private lateinit var events: NotificationEventHandler
    private lateinit var eligibility: ProjectedPushEligibility
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val executor = Executors.newFixedThreadPool(3)
    private val workers = mutableListOf<DeliveryWorker>()
    private val servers = mutableListOf<HttpServer>()
    private val meters = SimpleMeterRegistry()
    private val requests = AtomicInteger()

    @BeforeEach fun setup() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(dataSource).load().migrate()
        jdbc = JdbcTemplate(dataSource)
        jdbc.update("""TRUNCATE delivery_attempts,delivery_jobs,settings,push_devices,account_tombstones,
            reminder_occurrences,reminder_deadlines,event_projections,inbox_events""")
        val manager = DataSourceTransactionManager(dataSource)
        transactions = TransactionTemplate(manager).apply { timeout = 5 }
        notifications = JdbcNotificationRepository(jdbc, transactions)
        deliveries = JdbcDeliveryRepository(jdbc, manager, notifications)
        events = NotificationEventHandler(jdbc, transactions, notifications, deliveries, mapper)
        eligibility = ProjectedPushEligibility(jdbc, notifications, transactions, mapper)
    }

    @AfterEach fun close() {
        workers.forEach(DeliveryWorker::close)
        servers.forEach { it.stop(0) }
        executor.shutdownNow()
        meters.close()
    }

    @ParameterizedTest(name = "concurrent mutation during eligibility: {0}")
    @ValueSource(strings = ["transfer", "delete", "disable"])
    fun `ownership changes cannot complete between target validation and provider submission`(mutation: String) {
        val user = UUID.randomUUID()
        val token = "token-$user"
        val job = createJob(user, token)
        val enteredEligibility = CountDownLatch(1)
        val resumeEligibility = CountDownLatch(1)
        val worker = worker(PushEligibility { account, kind, occurrence, now ->
            enteredEligibility.countDown()
            check(resumeEligibility.await(5, TimeUnit.SECONDS))
            eligibility.isEligible(account, kind, occurrence, now)
        })
        val delivery = executor.submit { worker.process(job) }
        check(enteredEligibility.await(5, TimeUnit.SECONDS))
        val mutationPid = AtomicInteger()
        val change = executor.submit {
            transactions.executeWithoutResult {
                mutationPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                when (mutation) {
                    "transfer" -> notifications.registerDevice(UUID.randomUUID(), RegisterDevice(DevicePlatform.ANDROID, "new-installation", token))
                    "delete" -> deleteAccount(user)
                    "disable" -> notifications.updateSettings(user, SettingsPatch(foodEnabled = false))
                }
            }
        }
        try {
            awaitAdvisoryWait(mutationPid)
            assertThat(change.isDone).isFalse()
            assertThat(requests.get()).isZero()
        } finally { resumeEligibility.countDown() }
        delivery.get(5, TimeUnit.SECONDS)
        change.get(5, TimeUnit.SECONDS)
        assertThat(requests.get()).isEqualTo(1)
        if (mutation == "delete") assertThat(notifications.isDeleted(user)).isTrue()
        else assertThat(status(job)).isEqualTo("ACCEPTED")
    }

    @ParameterizedTest(name = "mutation completed before submission: {0}")
    @ValueSource(strings = ["transfer", "delete", "unregister", "disable"])
    fun `completed ownership and preference changes suppress even a previously cached target`(mutation: String) {
        val user = UUID.randomUUID()
        val token = "token-$user"
        val job = createJob(user, token)
        assertThat(deliveries.currentPushTarget(job)).isNotNull()
        when (mutation) {
            "transfer" -> notifications.registerDevice(UUID.randomUUID(), RegisterDevice(DevicePlatform.ANDROID, "new-installation", token))
            "delete" -> deleteAccount(user)
            "unregister" -> notifications.unregisterDevice(user, "installation")
            "disable" -> notifications.updateSettings(user, SettingsPatch(foodEnabled = false))
        }
        worker(eligibility).process(job)
        assertThat(requests.get()).isZero()
        if (mutation == "delete") assertThat(status(job)).isNull()
        else assertThat(status(job)).isEqualTo("CANCELLED")
    }

    @Test fun `transfer holding the token lock wins before submission and target is revalidated after wait`() {
        val user = UUID.randomUUID()
        val token = "token-$user"
        val job = createJob(user, token)
        val transferReady = CountDownLatch(1)
        val commitTransfer = CountDownLatch(1)
        val moved = executor.submit {
            transactions.executeWithoutResult {
                notifications.registerDevice(UUID.randomUUID(), RegisterDevice(DevicePlatform.ANDROID, "new-installation", token))
                transferReady.countDown()
                check(commitTransfer.await(5, TimeUnit.SECONDS))
            }
        }
        check(transferReady.await(5, TimeUnit.SECONDS))
        val delivery = executor.submit { worker(eligibility).process(job) }
        try {
            awaitAdvisoryWait(null)
            assertThat(delivery.isDone).isFalse()
            assertThat(requests.get()).isZero()
        } finally { commitTransfer.countDown() }
        moved.get(5, TimeUnit.SECONDS)
        delivery.get(5, TimeUnit.SECONDS)
        assertThat(requests.get()).isZero()
        assertThat(status(job)).isEqualTo("CANCELLED")
    }

    @Test fun `provider acceptance and durable result complete before a waiting token transfer`() {
        val user = UUID.randomUUID()
        val token = "token-$user"
        val job = createJob(user, token)
        val providerReceived = CountDownLatch(1)
        val providerReply = CountDownLatch(1)
        val worker = worker(eligibility) {
            providerReceived.countDown()
            check(providerReply.await(5, TimeUnit.SECONDS))
        }
        val delivery = executor.submit { worker.process(job) }
        check(providerReceived.await(5, TimeUnit.SECONDS))
        val mutationPid = AtomicInteger()
        val transfer = executor.submit {
            transactions.executeWithoutResult {
                mutationPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                notifications.registerDevice(UUID.randomUUID(), RegisterDevice(DevicePlatform.ANDROID, "new-installation", token))
            }
        }
        try {
            awaitAdvisoryWait(mutationPid)
            assertThat(transfer.isDone).isFalse()
            assertThat(status(job)).isEqualTo("CLAIMED")
        } finally { providerReply.countDown() }
        delivery.get(5, TimeUnit.SECONDS)
        transfer.get(5, TimeUnit.SECONDS)
        assertThat(status(job)).isEqualTo("ACCEPTED")
        assertThat(jdbc.queryForObject("SELECT last_submitted_at FROM reminder_occurrences WHERE id=?",
            Timestamp::class.java, job.occurrenceId)).isNotNull()
    }

    @Test fun `stale claimed worker cannot dispatch after lease recovery`() {
        val job = createJob(UUID.randomUUID(), "lease-token")
        jdbc.update("UPDATE delivery_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", job.id)
        deliveries.maintain()
        val current = deliveries.claim(1).single()
        worker(eligibility).process(job)
        assertThat(requests.get()).isZero()
        worker(eligibility).process(current)
        assertThat(requests.get()).isEqualTo(1)
        assertThat(status(current)).isEqualTo("ACCEPTED")
    }

    @Test fun `different water occurrence waits two hours after submission while same occurrence devices proceed`() {
        val user = UUID.randomUUID()
        val first = createJob(user, "water-first", "WATER")
        val secondDevice = notifications.registerDevice(user, RegisterDevice(DevicePlatform.ANDROID, "second", "water-second")).device
        deliveries.enqueuePush(requireNotNull(first.occurrenceId), user, secondDevice.id, "WATER", "ru", Instant.now().plusSeconds(7200))
        val sameOccurrence = deliveries.claim(1).single()
        val distinctOccurrence = UUID.randomUUID()
        jdbc.update("""INSERT INTO reminder_occurrences(id,user_id,kind,cycle_key,scheduled_at)
            VALUES (?,?,'WATER','later',clock_timestamp())""", distinctOccurrence, user)
        deliveries.enqueuePush(distinctOccurrence, user, secondDevice.id, "WATER", "ru", Instant.now().plusSeconds(7200))
        val later = deliveries.claim(1).single()
        worker(eligibility).process(first)
        val firstSubmission = jdbc.queryForObject("SELECT last_water_submission_at FROM settings WHERE user_id=?",
            Timestamp::class.java, user)!!.toInstant()
        worker(eligibility).process(sameOccurrence)
        assertThat(requests.get()).isEqualTo(2)
        worker(eligibility).process(later)
        assertThat(requests.get()).isEqualTo(2)
        assertThat(status(later)).isEqualTo("PENDING")
        assertThat(jdbc.queryForObject("SELECT last_code FROM delivery_jobs WHERE id=?", String::class.java, later.id))
            .isEqualTo("WATER_INTERVAL")
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM delivery_jobs WHERE id=?", Timestamp::class.java, later.id)!!.toInstant())
            .isAfterOrEqualTo(firstSubmission.plus(Duration.ofHours(2)))
    }

    private fun createJob(user: UUID, token: String, kind: String = "FOOD"): DeliveryJob {
        val baseline = Instant.now().minus(Duration.ofHours(6))
        val device = notifications.registerDevice(user, RegisterDevice(DevicePlatform.ANDROID, "installation", token)).device
        notifications.updateSettings(user, SettingsPatch(timezone = "UTC", quietStart = LocalTime.MIDNIGHT, quietEnd = LocalTime.MIDNIGHT))
        jdbc.update("""UPDATE settings SET scheduling_started_at=?,food_started_at=?,water_started_at=? WHERE user_id=?""",
            Timestamp.from(baseline), Timestamp.from(baseline), Timestamp.from(baseline), user)
        val storedBaseline = jdbc.queryForObject("SELECT food_started_at FROM settings WHERE user_id=?", Timestamp::class.java, user)!!.toInstant()
        val occurrence = UUID.randomUUID()
        jdbc.update("INSERT INTO reminder_occurrences(id,user_id,kind,cycle_key,scheduled_at) VALUES (?,?,?,?,?)",
            occurrence, user, kind, if (kind == "FOOD") "$storedBaseline:initial" else "initial",
            Timestamp.from(baseline.plusSeconds(if (kind == "FOOD") 14400 else 7200)))
        deliveries.enqueuePush(occurrence, user, device.id, kind, "ru", Instant.now().plusSeconds(7200))
        return deliveries.claim(1).single()
    }

    private fun worker(policy: PushEligibility, response: () -> Unit = {}): DeliveryWorker {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also(servers::add)
        server.createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            requests.incrementAndGet()
            response()
            val bytes = """{"name":"test-message"}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val http = BoundedHttpExchange(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build())
        val templates = NotificationTemplates(URI.create("https://example.test/verify"))
        val clock = Clock.systemUTC()
        return DeliveryWorker(deliveries, mock(VerificationPayloadDecryptor::class.java),
            VerificationSender { _, _ -> error("Only push is expected") },
            ApnsPushSender(ApnsProperties(), http, mapper, templates, clock),
            FcmPushSender(FcmProperties(true, "unused", "cookie-test", URI.create("http://127.0.0.1:${server.address.port}")),
                FcmAccessToken { "test-token" }, http, mapper, templates, clock),
            policy, clock, DeliveryProperties(), DeliveryMetrics(jdbc, meters)).also(workers::add)
    }

    private fun deleteAccount(user: UUID) {
        val payload = mapper.createObjectNode().put("event_id", UUID.randomUUID().toString())
            .put("event_type", "account.deletion.requested").put("event_version", 1).put("producer", "identity")
            .put("aggregate_type", "account").put("aggregate_id", user.toString()).put("occurred_at", Instant.now().toString())
        payload.putObject("payload").put("accountId", user.toString()).put("deletionRequestId", UUID.randomUUID().toString())
            .put("requestedAt", Instant.now().toString())
        events.handle("cookie.events.account.deletion.requested.v1", mapper.writeValueAsBytes(payload))
    }

    private fun status(job: DeliveryJob): String? =
        jdbc.query("SELECT status FROM delivery_jobs WHERE id=?", { row, _ -> row.getString(1) }, job.id).singleOrNull()

    private fun awaitAdvisoryWait(pid: AtomicInteger?) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        do {
            val blocked = if (pid == null) jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                WHERE wait_event='advisory' AND datname=current_database())""", Boolean::class.java)
            else jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                WHERE pid=? AND wait_event='advisory')""", Boolean::class.java, pid.get())
            if (blocked == true) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        error("Expected a competing transaction to wait on the shared advisory lock")
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:18.6-alpine3.24")
    }
}
