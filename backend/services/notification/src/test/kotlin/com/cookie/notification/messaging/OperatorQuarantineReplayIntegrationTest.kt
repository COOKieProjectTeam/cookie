package com.cookie.notification.messaging

import com.cookie.notification.delivery.JdbcDeliveryRepository
import io.nats.client.Connection
import io.nats.client.ErrorListener
import io.nats.client.NKey
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.MessageInfo
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import io.nats.client.support.JsonSerializable
import io.nats.client.support.JwtUtils
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The real operator JAR/profile and broker authorization, not a republished duplicate envelope. */
@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperatorQuarantineReplayIntegrationTest {
    private lateinit var jdbc: JdbcTemplate
    private lateinit var quarantine: NotificationQuarantine
    private lateinit var deliveries: JdbcDeliveryRepository
    private lateinit var admin: Connection
    private val processes = mutableListOf<Process>()

    @BeforeAll
    fun migrateAndCreateStream() {
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).load().migrate()
        jdbc = JdbcTemplate(source)
        quarantine = NotificationQuarantine(jdbc, mapper)
        deliveries = JdbcDeliveryRepository(jdbc, DataSourceTransactionManager(source))
        admin = Nats.connect(options(credentials.admin, "_INBOX.fixture.admin"))
        admin.jetStreamManagement().addStream(StreamConfiguration.builder()
            .name(NotificationNatsConnection.STREAM).subjects("cookie.events.>")
            .storageType(StorageType.File).maxAge(Duration.ofDays(7)).build())
        admin.jetStreamManagement().addOrUpdateConsumer(NotificationNatsConnection.STREAM,
            ConsumerConfiguration.builder().durable(NotificationEventHandler.CONSUMER)
                .ackPolicy(AckPolicy.Explicit).filterSubjects(
                    NotificationEventHandler.PRODUCERS.keys.map { "cookie.events.$it.v1" }).build())
    }

    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute("""TRUNCATE delivery_attempts,delivery_jobs,settings,push_devices,inbox_events,
            event_projections,reminder_deadlines,reminder_occurrences,account_tombstones,message_quarantine""")
    }

    @Test
    fun `operator process retrieves original and exits without starting delivery or consuming other events`() {
        val original = email()
        val id = quarantined(original)
        // A second retained event must not be consumed by a replay process.
        admin.jetStream().publish(SUBJECT, email().bytes)
        val before = admin.jetStreamManagement()
            .getConsumerInfo(NotificationNatsConnection.STREAM, NotificationEventHandler.CONSUMER).delivered.streamSequence

        val result = replay(id)

        assertThat(result.exitCode).withFailMessage("%s", result.log).isZero()
        assertThat(count("inbox_events")).isEqualTo(1)
        assertThat(count("delivery_jobs")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT event_id FROM inbox_events", UUID::class.java)).isEqualTo(original.id)
        assertThat(jdbc.queryForObject("SELECT encrypted_payload FROM delivery_jobs", String::class.java)).isEqualTo(original.ciphertext)
        assertThat(jdbc.queryForObject("SELECT status FROM delivery_jobs", String::class.java)).isEqualTo("PENDING")
        assertThat(count("delivery_attempts")).isZero()
        assertThat(replayed(id)).isTrue()
        assertThat(attempts(id)).isEqualTo(1)
        assertThat(admin.jetStreamManagement()
            .getConsumerInfo(NotificationNatsConnection.STREAM, NotificationEventHandler.CONSUMER).delivered.streamSequence)
            .isEqualTo(before)
        assertThat(result.log).doesNotContain(original.ciphertext)

        val repeated = replay(id)
        assertThat(repeated.exitCode).isNotZero()
        assertThat(repeated.log).contains("Replay record not found or already replayed").doesNotContain(original.ciphertext)
        assertThat(attempts(id)).isEqualTo(1)
        assertThat(count("delivery_jobs")).isEqualTo(1)
    }

    @Test
    fun `replay cannot reset terminal UNKNOWN even when inbox deduplication is unavailable`() {
        val original = email()
        val id = quarantined(original)
        val now = Instant.now()
        deliveries.enqueueEmail(original.id, original.attempt, original.ciphertext, now.plusSeconds(600), now)
        val submitted = deliveries.claim(1).single()
        assertThat(deliveries.beginSmtpSubmission(submitted)).isTrue()
        jdbc.update("UPDATE delivery_jobs SET lease_until=clock_timestamp()-interval '1 second'")
        deliveries.maintain()
        // No inbox entry exists: the durable delivery dedup key is independently protective.
        assertThat(count("inbox_events")).isZero()

        val result = replay(id)

        assertThat(result.exitCode).withFailMessage("%s", result.log).isZero()
        assertThat(replayed(id)).isTrue()
        assertThat(count("inbox_events")).isEqualTo(1)
        assertThat(count("delivery_jobs")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT status FROM delivery_jobs", String::class.java)).isEqualTo("UNKNOWN")
        assertThat(jdbc.queryForObject("SELECT encrypted_payload FROM delivery_jobs", String::class.java)).isNull()
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM delivery_jobs", Int::class.java)).isEqualTo(1)
        assertThat(deliveries.claim(1)).isEmpty()
    }

    @Test
    fun `failed handler transaction leaves replay retryable without partial inbox commit`() {
        val original = email()
        val id = quarantined(original)
        jdbc.execute("""CREATE FUNCTION replay_queue_failure() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN RAISE EXCEPTION ''synthetic replay queue failure: %'', NEW.encrypted_payload; END'""")
        jdbc.execute("""CREATE TRIGGER replay_queue_failure BEFORE INSERT ON delivery_jobs
            FOR EACH ROW EXECUTE FUNCTION replay_queue_failure()""")
        try {
            val failed = replay(id)
            assertThat(failed.exitCode).isNotZero()
            assertThat(failed.log).contains("Replay failed due to broker or database error")
                .doesNotContain(original.ciphertext)
            assertFailedAttempt(id)
        } finally {
            jdbc.execute("DROP TRIGGER replay_queue_failure ON delivery_jobs")
            jdbc.execute("DROP FUNCTION replay_queue_failure()")
        }

        val recovered = replay(id)
        assertThat(recovered.exitCode).withFailMessage("%s", recovered.log).isZero()
        assertThat(attempts(id)).isEqualTo(2)
        assertThat(replayed(id)).isTrue()
        assertThat(count("inbox_events")).isEqualTo(1)
        assertThat(count("delivery_jobs")).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["subject", "hash"])
    fun `replay rejects mismatching stored evidence without changing application state`(mismatch: String) {
        val original = email()
        val id = quarantined(original)
        if (mismatch == "subject") jdbc.update("UPDATE message_quarantine SET subject=? WHERE id=?",
            "cookie.events.account.activated.v1", id)
        else jdbc.update("UPDATE message_quarantine SET message_hash=? WHERE id=?", "0".repeat(64), id)

        val result = replay(id)

        assertThat(result.exitCode).isNotZero()
        assertThat(result.log).contains("Original message does not match quarantine record").doesNotContain(original.ciphertext)
        assertFailedAttempt(id)
    }

    @Test
    fun `deleted original reports irrecoverable retention failure and preserves quarantine`() {
        val original = email()
        val id = quarantined(original)
        val sequence = jdbc.queryForObject("SELECT stream_sequence FROM message_quarantine WHERE id=?", Long::class.java, id)!!
        admin.jetStreamManagement().deleteMessage(NotificationNatsConnection.STREAM, sequence)

        val result = replay(id)

        assertThat(result.exitCode).isNotZero()
        assertThat(result.log).contains("Original message no longer retained; cannot recover or replay")
            .doesNotContain(original.ciphertext)
        assertFailedAttempt(id)
    }

    @Test
    fun `still malformed original remains quarantined with safe diagnostic and is never marked replayed`() {
        val sensitive = "synthetic-sensitive-invalid-payload-${UUID.randomUUID()}"
        val bytes = sensitive.toByteArray()
        val sequence = admin.jetStream().publish(SUBJECT, bytes).seqno
        quarantine.record(SUBJECT, bytes, sequence, "invalid_json")
        val id = jdbc.queryForObject("SELECT id FROM message_quarantine", UUID::class.java)!!

        val result = replay(id)

        assertThat(result.exitCode).isNotZero()
        assertThat(result.log).contains("invalid_json").doesNotContain(sensitive)
        assertThat(jdbc.queryForObject("SELECT row_to_json(q)::text FROM message_quarantine q", String::class.java))
            .doesNotContain(sensitive)
        assertFailedAttempt(id)
    }

    @Test
    fun `missing explicit operator credential cannot fall back to workload credential`() {
        val original = email()
        val id = quarantined(original)

        val result = replay(id, "")

        assertThat(result.exitCode).isNotZero()
        assertThat(result.log).contains("Separate operator NATS credentials are required")
        assertThat(attempts(id)).isZero()
        assertThat(replayed(id)).isFalse()
        assertThat(count("inbox_events")).isZero()
    }

    @Test
    fun `runtime cannot fetch arbitrary stored messages and operator cannot publish or consume`() {
        val original = email()
        val sequence = admin.jetStream().publish(SUBJECT, original.bytes).seqno
        val runtimeErrors = LinkedBlockingQueue<String>()
        Nats.connect(options(credentials.runtime, "_INBOX.cookie.notification", runtimeErrors)).use { connection ->
            // Prove the production runtime grants are installed and functional.
            assertThat(connection.jetStreamManagement()
                .getConsumerInfo(NotificationNatsConnection.STREAM, NotificationEventHandler.CONSUMER).name)
                .isEqualTo(NotificationEventHandler.CONSUMER)
            val reply = connection.request(FETCH, mapper.writeValueAsBytes(mapOf("seq" to sequence)), Duration.ofMillis(500))
            assertThat(reply).isNull()
            assertThat(runtimeErrors.poll(5, TimeUnit.SECONDS)).contains("Permissions Violation").contains(FETCH)
        }
        val operatorErrors = LinkedBlockingQueue<String>()
        Nats.connect(options(credentials.operator, "_INBOX.cookie.notification_operator", operatorErrors)).use { connection ->
            val response = connection.request(FETCH, mapper.writeValueAsBytes(mapOf("seq" to sequence)), Duration.ofSeconds(2))
            val fetched = MessageInfo(requireNotNull(response), NotificationNatsConnection.STREAM, false).throwOnHasError()
            assertThat(fetched.data).isEqualTo(original.bytes)
            connection.publish(SUBJECT, original.bytes)
            connection.flush(Duration.ofSeconds(2))
            assertThat(operatorErrors.poll(5, TimeUnit.SECONDS)).contains("Permissions Violation").contains(SUBJECT)
            val next = "\$JS.API.CONSUMER.MSG.NEXT.${NotificationNatsConnection.STREAM}.${NotificationEventHandler.CONSUMER}"
            assertThat(connection.request(next, """{"batch":1,"no_wait":true}""".toByteArray(), Duration.ofMillis(500))).isNull()
            assertThat(operatorErrors.poll(5, TimeUnit.SECONDS)).contains("Permissions Violation").contains(next)
        }
    }

    private fun assertFailedAttempt(id: UUID) {
        assertThat(replayed(id)).isFalse()
        assertThat(attempts(id)).isEqualTo(1)
        assertThat(count("inbox_events")).isZero()
        assertThat(count("delivery_jobs")).isZero()
    }

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!
    private fun attempts(id: UUID) = jdbc.queryForObject("SELECT replay_attempts FROM message_quarantine WHERE id=?", Int::class.java, id)!!
    private fun replayed(id: UUID) = jdbc.queryForObject("SELECT replayed_at IS NOT NULL FROM message_quarantine WHERE id=?", Boolean::class.java, id)!!

    private fun quarantined(original: EmailFixture): UUID {
        val sequence = admin.jetStream().publish(SUBJECT, original.bytes).seqno
        quarantine.record(SUBJECT, original.bytes, sequence, "unsupported_template")
        return jdbc.queryForObject("SELECT id FROM message_quarantine WHERE stream_sequence=?", UUID::class.java, sequence)!!
    }

    private fun email(): EmailFixture {
        val id = UUID.randomUUID()
        val attempt = UUID.randomUUID()
        val ciphertext = "opaque-jwe-fixture-${UUID.randomUUID()}"
        return EmailFixture(id, attempt, ciphertext, mapper.writeValueAsBytes(mapOf(
            "event_id" to id, "event_type" to "notification.email.requested", "event_version" to 1,
            "producer" to "identity", "aggregate_type" to "registration_attempt", "aggregate_id" to attempt,
            "occurred_at" to Instant.now().toString(), "payload" to mapOf("template" to "EMAIL_VERIFICATION",
                "encryptedPayload" to ciphertext, "expiresAt" to Instant.now().plusSeconds(900).toString()))))
    }

    private fun replay(id: UUID, operatorPath: String = credentials.operator.toString()): ProcessResult {
        val log = credentials.directory.resolve("replay-${UUID.randomUUID()}.log")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx256m",
            "-jar", requireNotNull(System.getProperty("cookie.test.notification-jar")),
            "--spring.profiles.active=test,notification-replay",
            "--cookie.notification.operator-nats-credentials-path=$operatorPath",
            "--notification-replay-id=$id",
        ).apply {
            environment().putAll(mapOf(
                "COOKIE_NOTIFICATION_DATABASE_URL" to postgres.jdbcUrl,
                "COOKIE_NOTIFICATION_DATABASE_USER" to postgres.username,
                "COOKIE_NOTIFICATION_DATABASE_PASSWORD" to postgres.password,
                "COOKIE_NATS_URL" to "nats://${nats.host}:${nats.getMappedPort(4222)}",
                "COOKIE_NATS_CREDENTIALS_PATH" to credentials.runtime.toString(),
                "COOKIE_IDENTITY_JWKS_URL" to "http://127.0.0.1:1/v1/auth/jwks",
                "COOKIE_APNS_ENABLED" to "false", "COOKIE_FCM_ENABLED" to "false",
            ))
            redirectErrorStream(true)
            redirectOutput(log.toFile())
        }.start()
        processes += process
        val exited = process.waitFor(60, TimeUnit.SECONDS)
        if (!exited) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        assertThat(exited).withFailMessage("Operator failed to terminate; synthetic-test log: %s", log).isTrue()
        return ProcessResult(process.exitValue(), Files.readString(log))
    }

    private fun options(path: Path, inbox: String, errors: LinkedBlockingQueue<String>? = null): Options =
        Options.Builder().server("nats://${nats.host}:${nats.getMappedPort(4222)}")
            .credentialPath(path.toString()).inboxPrefix(inbox)
            .connectionTimeout(Duration.ofSeconds(2)).maxReconnects(0)
            .apply {
                if (errors != null) errorListener(object : ErrorListener {
                    override fun errorOccurred(connection: Connection, error: String) { errors.add(error) }
                })
            }.build()

    @AfterAll
    fun close() {
        processes.filter(Process::isAlive).forEach {
            it.destroy()
            if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly()
        }
        if (::admin.isInitialized) admin.close()
        // All credentials are generated test fixtures. Do not retain private seeds after the run.
        listOf(credentials.admin, credentials.runtime, credentials.operator).forEach(Files::deleteIfExists)
    }

    private data class EmailFixture(val id: UUID, val attempt: UUID, val ciphertext: String, val bytes: ByteArray)
    private data class ProcessResult(val exitCode: Int, val log: String)
    class NatsContainer(image: String) : GenericContainer<NatsContainer>(image)

    companion object {
        private val mapper = JsonMapper.builder().findAndAddModules().build()
        private const val SUBJECT = "cookie.events.notification.email.requested.v1"
        private const val FETCH = "\$JS.API.STREAM.MSG.GET.COOKIE_EVENTS"
        private val credentials = ReplayBrokerCredentials()
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:18.6-alpine3.24")
        @Container @JvmStatic val nats = NatsContainer("nats:2.14.6-alpine3.22")
            .withCopyToContainer(Transferable.of(credentials.serverConfiguration), "/etc/nats/replay.conf")
            .withCommand("-c", "/etc/nats/replay.conf").withExposedPorts(4222)
    }
}

/** Ephemeral JWT-based NATS account: no fixture seed, password or production identifier is committed. */
private class ReplayBrokerCredentials {
    val directory: Path = Files.createTempDirectory("cookie-replay-test-")
    private val random = SecureRandom()
    private val mapper = JsonMapper.builder().build()
    private val operatorKey = NKey.createOperator(random)
    private val accountKey = NKey.createAccount(random)
    private val systemKey = NKey.createAccount(random)
    private val accountId = String(accountKey.publicKey)
    private val systemId = String(systemKey.publicKey)
    private val permissionsPath = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("deploy/production/config/notification-nats-permissions.json") }
        .first(Files::isRegularFile)
    private val runtimePermissions = mapper.readTree(Files.readString(permissionsPath))
    val runtime: Path = user("runtime",
        runtimePermissions.path("publish").path("allow").toList().map { it.stringValue() }.toTypedArray(),
        runtimePermissions.path("subscribe").path("allow").toList().map { it.stringValue() }.toTypedArray())
    val operator: Path = user("operator", arrayOf("\$JS.API.STREAM.MSG.GET.COOKIE_EVENTS"),
        arrayOf("_INBOX.cookie.notification_operator.>"))
    val admin: Path = user("admin", arrayOf(">"), arrayOf(">"))
    val serverConfiguration: String

    init {
        val operatorJwt = claim(operatorKey, String(operatorKey.publicKey), "operator",
            mapOf("type" to "operator", "version" to 2, "system_account" to systemId))
        val accountJwt = claim(operatorKey, accountId, "application", mapOf("type" to "account", "version" to 2,
            "limits" to mapOf("subs" to -1, "data" to -1, "payload" to -1, "conn" to -1,
                "imports" to -1, "exports" to -1, "wildcards" to true,
                "mem_storage" to 67_108_864, "disk_storage" to 134_217_728, "streams" to 4, "consumer" to 8)))
        val systemJwt = claim(operatorKey, systemId, "system", mapOf("type" to "account", "version" to 2))
        serverConfiguration = """
            listen: 0.0.0.0:4222
            jetstream { store_dir: /tmp/jetstream, max_memory_store: 64MiB, max_file_store: 128MiB }
            operator: "$operatorJwt"
            system_account: "$systemId"
            resolver: MEMORY
            resolver_preload: {
                "$accountId": "$accountJwt",
                "$systemId": "$systemJwt"
            }
        """.trimIndent()
        operatorKey.clear()
        accountKey.clear()
        systemKey.clear()
    }

    private fun claim(signer: NKey, subject: String, name: String, body: Map<String, Any>) =
        JwtUtils.issueJWT(signer, subject, name, Duration.ofHours(1), Instant.now().epochSecond,
            String(signer.publicKey), JsonSerializable { mapper.writeValueAsString(body) })

    private fun user(name: String, publish: Array<String>, subscribe: Array<String>): Path {
        val key = NKey.createUser(random)
        try {
            val claims = JwtUtils.UserClaim(accountId)
                .pub(JwtUtils.Permission().allow(*publish)).sub(JwtUtils.Permission().allow(*subscribe))
            val jwt = JwtUtils.issueUserJWT(accountKey, String(key.publicKey), name, Duration.ofHours(1),
                Instant.now().epochSecond, claims)
            return Files.writeString(directory.resolve("$name.creds"),
                JwtUtils.NATS_USER_JWT_FORMAT.format(jwt, String(key.seed)))
        } finally { key.clear() }
    }
}
