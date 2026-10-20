package com.cookie.notification

import com.cookie.notification.messaging.NotificationEventHandler
import io.nats.client.Nats
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Runs the actual producer and consumer JARs, migrations, transport and SMTP adapter. */
@Tag("integration")
@Testcontainers
class IdentityToNotificationIntegrationTest {
    @Test
    fun `registration delivers encrypted email then authenticated preferences and deletion work`() {
        val attempt = UUID.randomUUID()
        val email = "notification-${attempt.toString().take(8)}@example.ru"
        val password = "Integration-Strong-Password-2026"
        val proof = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        val registration = mapOf("registrationAttemptId" to attempt.toString(), "email" to email,
            "password" to password, "registrationProof" to proof, "locale" to "en")
        // Fail after the handler's inbox INSERT: neither its transaction nor the broker ACK may survive.
        notificationSql("""CREATE FUNCTION test_queue_failure() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN RAISE EXCEPTION ''synthetic queue failure''; END'""")
        notificationSql("CREATE TRIGGER test_queue_failure BEFORE INSERT ON delivery_jobs FOR EACH ROW EXECUTE FUNCTION test_queue_failure()")
        try {
            assertThat(post(identityPort, "/v1/auth/email/register", registration).statusCode()).isEqualTo(202)
            Nats.connect("nats://${nats.host}:${nats.getMappedPort(4222)}").use { connection ->
                await().atMost(Duration.ofSeconds(20)).until {
                    val consumer = connection.jetStreamManagement().getConsumerInfo("COOKIE_EVENTS", NotificationEventHandler.CONSUMER)
                    consumer.numAckPending > 0 && consumer.redelivered > 0
                }
            }
            assertThat(notificationCount("inbox_events")).isZero()
            assertThat(notificationCount("delivery_jobs")).isZero()
            assertThat(mailCount()).isZero()
        } finally {
            notificationSql("DROP TRIGGER test_queue_failure ON delivery_jobs")
            notificationSql("DROP FUNCTION test_queue_failure()")
        }
        awaitMailCount(1)
        val mailId = mapper.readTree(get(mailpit.getMappedPort(8025), "/api/v1/messages").body()).path("messages")[0].path("ID").stringValue()
        val mail = mapper.readTree(get(mailpit.getMappedPort(8025), "/api/v1/message/$mailId").body())
        val text = mail.path("Text").stringValue()
        assertThat(text).contains("https://cookie.invalid/verify-email#token=")
        assertThat(text).doesNotContain(proof)
        val token = URLDecoder.decode(text.lineSequence().first { it.contains("#token=") }.substringAfter("#token="), StandardCharsets.UTF_8)
        assertThat(post(identityPort, "/v1/auth/email/verification/confirm", mapOf("token" to token, "registrationProof" to proof)).statusCode())
            .isEqualTo(204)
        val login = post(identityPort, "/v1/auth/email/login", mapOf("email" to email, "password" to password, "deviceId" to "notification-integration"))
        assertThat(login.statusCode()).isEqualTo(200)
        val jwt = mapper.readTree(login.body()).path("accessToken").stringValue()
        val initial = get(notificationPort, "/v1/notifications/settings", jwt)
        assertThat(initial.statusCode()).isEqualTo(200)
        assertThat(mapper.readTree(initial.body()).hasNonNull("timezone")).isFalse()

        assertThat(request(notificationPort, "PATCH", "/v1/notifications/settings", """{"timezone":"Europe/London","locale":"en","food":{"enabled":false}}""", jwt).statusCode())
            .isEqualTo(200)
        val patch = request(notificationPort, "PATCH", "/v1/notifications/settings", """{"water":{"enabled":false}}""", jwt)
        assertThat(mapper.readTree(patch.body()).path("locale").stringValue()).isEqualTo("en")
        assertThat(mapper.readTree(patch.body()).path("food").path("enabled").booleanValue()).isFalse()
        assertThat(request(notificationPort, "PATCH", "/v1/notifications/settings", """{"timezone":null,"water":{"enabled":true}}""", jwt).statusCode())
            .isEqualTo(400)
        assertThat(request(notificationPort, "PATCH", "/v1/notifications/settings", """{"food":{}}""", jwt).statusCode()).isEqualTo(400)
        assertThat(request(notificationPort, "PATCH", "/v1/notifications/settings", """{"timezone":"fake/zone"}""", jwt).statusCode()).isEqualTo(400)
        for (invalid in listOf("""{"food":{"inactiveForMinutes":1.5}}""", """{"water":{"enabled":"false"}}""",
            """{"water":{"enabled":true},"userId":"untrusted"}""")) {
            assertThat(request(notificationPort, "PATCH", "/v1/notifications/settings", invalid, jwt).statusCode()).isEqualTo(400)
        }
        assertThat(get(notificationPort, "/v1/notifications/settings").statusCode()).isEqualTo(401)
        assertThat(get(notificationPort, "/v1/healthz").statusCode()).isEqualTo(404)

        val device = mapOf("platform" to "ANDROID", "deviceId" to "test-device", "token" to "synthetic-fcm-token")
        assertThat(post(notificationPort, "/v1/notifications/devices", device + ("token" to 123), jwt).statusCode()).isEqualTo(400)
        assertThat(post(notificationPort, "/v1/notifications/devices", device, jwt).statusCode()).isEqualTo(201)
        assertThat(post(notificationPort, "/v1/notifications/devices", device, jwt).statusCode()).isEqualTo(200)

        val secondJwt = registerAnotherAccount(password)
        val secondSettings = get(notificationPort, "/v1/notifications/settings", secondJwt)
        assertThat(mapper.readTree(secondSettings.body()).path("food").path("enabled").booleanValue()).isTrue()
        val movedDevice = device + ("deviceId" to "second-device")
        assertThat(post(notificationPort, "/v1/notifications/devices", movedDevice, secondJwt).statusCode()).isEqualTo(201)
        assertThat(request(notificationPort, "DELETE", "/v1/notifications/devices/second-device", "", jwt).statusCode()).isEqualTo(404)
        assertThat(request(notificationPort, "DELETE", "/v1/notifications/devices/test-device", "", jwt).statusCode()).isEqualTo(204)
        assertThat(request(notificationPort, "DELETE", "/v1/notifications/devices/second-device", "", secondJwt).statusCode()).isEqualTo(204)

        // Re-publish the exact real producer envelope: inbox + delivery dedup survive broker replay.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { db ->
            db.prepareStatement("SELECT event_id,payload::text FROM outbox_events WHERE event_type='notification.email.requested'").use { statement ->
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    val payload = rows.getString(2)
                    assertThat(payload).doesNotContain(email).doesNotContain(token)
                }
            }
        }
        Nats.connect("nats://${nats.host}:${nats.getMappedPort(4222)}").use { connection ->
            val manager = connection.jetStreamManagement()
            val original = manager.getFirstMessage("COOKIE_EVENTS", "cookie.events.notification.email.requested.v1")
            val replay = connection.jetStream().publish(original.subject, original.data)
            await().atMost(Duration.ofSeconds(15)).until {
                manager.getConsumerInfo("COOKIE_EVENTS", NotificationEventHandler.CONSUMER).ackFloor.streamSequence >= replay.seqno
            }
        }
        val deletion = request(identityPort, "POST", "/v1/auth/account-deletion-requests", mapper.writeValueAsString(mapOf("currentPassword" to password)), jwt,
            mapOf("Idempotency-Key" to UUID.randomUUID().toString()))
        assertThat(deletion.statusCode()).isEqualTo(202)
        await().atMost(Duration.ofSeconds(20)).until { get(notificationPort, "/v1/notifications/settings", jwt).statusCode() == 403 }
        assertThat(get(notificationPort, "/v1/notifications/settings", secondJwt).statusCode()).isEqualTo(200)
        assertThat(mailCount()).isEqualTo(2)
    }

    private fun registerAnotherAccount(password: String): String {
        val attempt = UUID.randomUUID()
        val email = "isolation-${attempt.toString().take(8)}@example.ru"
        val proof = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        assertThat(post(identityPort, "/v1/auth/email/register", mapOf("registrationAttemptId" to attempt,
            "email" to email, "password" to password, "registrationProof" to proof)).statusCode()).isEqualTo(202)
        awaitMailCount(2)
        val messages = mapper.readTree(get(mailpit.getMappedPort(8025), "/api/v1/messages").body()).path("messages")
        val id = messages.first { message -> message.path("To").any { it.path("Address").stringValue() == email } }.path("ID").stringValue()
        val text = mapper.readTree(get(mailpit.getMappedPort(8025), "/api/v1/message/$id").body()).path("Text").stringValue()
        val token = URLDecoder.decode(text.lineSequence().first { it.contains("#token=") }.substringAfter("#token="), StandardCharsets.UTF_8)
        assertThat(post(identityPort, "/v1/auth/email/verification/confirm", mapOf("token" to token, "registrationProof" to proof)).statusCode())
            .isEqualTo(204)
        val login = post(identityPort, "/v1/auth/email/login", mapOf("email" to email, "password" to password, "deviceId" to "second-device"))
        assertThat(login.statusCode()).isEqualTo(200)
        return mapper.readTree(login.body()).path("accessToken").stringValue()
    }

    private fun mailCount(): Int = mapper.readTree(get(mailpit.getMappedPort(8025), "/api/v1/messages").body()).path("total").intValue()

    private fun awaitMailCount(expected: Int) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            assertThat(mailCount()).withFailMessage("Expected %s messages; delivery states: %s", expected, deliveryStates())
                .isEqualTo(expected)
        }
    }

    private fun deliveryStates(): List<String> =
        DriverManager.getConnection(postgres.jdbcUrl.replace("/identity", "/notification"), postgres.username, postgres.password).use { db ->
            db.createStatement().use { statement ->
                statement.executeQuery("SELECT status, last_code FROM delivery_jobs ORDER BY created_at").use { rows ->
                    buildList { while (rows.next()) add("${rows.getString(1)}:${rows.getString(2)}") }
                }
            }
        }

    private fun notificationSql(sql: String) {
        DriverManager.getConnection(postgres.jdbcUrl.replace("/identity", "/notification"), postgres.username, postgres.password).use { db ->
            db.createStatement().use { it.execute(sql) }
        }
    }

    private fun notificationCount(table: String): Long =
        DriverManager.getConnection(postgres.jdbcUrl.replace("/identity", "/notification"), postgres.username, postgres.password).use { db ->
            db.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use { rows -> check(rows.next()); rows.getLong(1) }
            }
        }

    companion object {
        class ServiceContainer(image: String) : GenericContainer<ServiceContainer>(image)
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:18.6-alpine3.24").withDatabaseName("identity")
        @Container @JvmStatic val nats = ServiceContainer("nats:2.14.6-alpine3.22").withCommand("-js").withExposedPorts(4222)
        @Container @JvmStatic val redis = ServiceContainer("redis:8.2-alpine").withExposedPorts(6379)
        @Container @JvmStatic val mailpit = ServiceContainer("axllent/mailpit:v1.31.0").withExposedPorts(1025, 8025)
        private val mapper = JsonMapper.builder().findAndAddModules().build()
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        private val processes = mutableListOf<Process>()
        private val temporary = Files.createTempDirectory("cookie-notification-flow-")
        private var identityPort = 0
        private var notificationPort = 0

        @BeforeAll @JvmStatic fun startServices() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { db ->
                db.createStatement().use { it.execute("CREATE DATABASE notification") }
            }
            identityPort = freePort()
            val key = temporary.resolve("notification-private.jwk")
            val common = mapOf("SPRING_PROFILES_ACTIVE" to "test", "COOKIE_NATS_URL" to "nats://${nats.host}:${nats.getMappedPort(4222)}")
            start("identity", common + mapOf("PORT" to identityPort.toString(),
                "COOKIE_IDENTITY_DATABASE_URL" to postgres.jdbcUrl, "COOKIE_IDENTITY_DATABASE_USER" to postgres.username,
                "COOKIE_IDENTITY_DATABASE_PASSWORD" to postgres.password,
                "COOKIE_DEV_NOTIFICATION_PRIVATE_KEY_OUTPUT_PATH" to key.toString()))
            awaitService(identityPort)
            val migrationLog = temporary.resolve("notification-migration.log")
            val migration = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-jar", requireNotNull(System.getProperty("cookie.test.notification-jar")),
                "--cookie.notification.mode=migrate").apply {
                environment().putAll(mapOf(
                    "SPRING_FLYWAY_URL" to postgres.jdbcUrl.replace("/identity", "/notification"),
                    "SPRING_FLYWAY_USER" to postgres.username, "SPRING_FLYWAY_PASSWORD" to postgres.password))
                redirectErrorStream(true)
                redirectOutput(migrationLog.toFile())
            }.start()
            processes += migration
            assertThat(migration.waitFor(60, TimeUnit.SECONDS)).withFailMessage("Migration did not terminate").isTrue()
            assertThat(migration.exitValue()).withFailMessage("%s", Files.readString(migrationLog)).isZero()
            // Only long-running services participate in the readiness liveness check.
            processes.remove(migration)
            notificationPort = freePort()
            start("notification", common + mapOf("PORT" to notificationPort.toString(),
                "COOKIE_NOTIFICATION_DATABASE_URL" to postgres.jdbcUrl.replace("/identity", "/notification"),
                "COOKIE_NOTIFICATION_DATABASE_USER" to postgres.username, "COOKIE_NOTIFICATION_DATABASE_PASSWORD" to postgres.password,
                "COOKIE_NOTIFICATION_PRIVATE_KEY_PATH" to key.toString(), "COOKIE_IDENTITY_JWKS_URL" to "http://localhost:$identityPort/v1/auth/jwks",
                "SPRING_DATA_REDIS_HOST" to redis.host, "SPRING_DATA_REDIS_PORT" to redis.getMappedPort(6379).toString(),
                "COOKIE_SMTP_HOST" to mailpit.host, "COOKIE_SMTP_PORT" to mailpit.getMappedPort(1025).toString(),
                "COOKIE_SMTP_STARTTLS_REQUIRED" to "false", "COOKIE_NOTIFICATION_VERIFICATION_LINK_BASE_URL" to "https://cookie.invalid/verify-email"))
            awaitService(notificationPort)
        }

        private fun start(service: String, environment: Map<String, String>) {
            val jar = requireNotNull(System.getProperty("cookie.test.$service-jar"))
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx384m", "-jar", jar).apply {
                environment().putAll(environment)
                redirectErrorStream(true)
                redirectOutput(temporary.resolve("$service.log").toFile())
            }.start()
            processes += process
        }

        private fun awaitService(port: Int) {
            await().atMost(Duration.ofSeconds(75)).pollInterval(Duration.ofMillis(250)).until {
                check(processes.all { it.isAlive }) { "A service exited; inspect synthetic-test logs at $temporary" }
                runCatching { get(port, "/readyz").statusCode() == 200 }.getOrDefault(false)
            }
        }

        @AfterAll @JvmStatic fun stopServices() {
            processes.reversed().forEach { it.destroy(); if (!it.waitFor(10, TimeUnit.SECONDS)) it.destroyForcibly() }
        }
        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
        private fun get(port: Int, path: String, token: String? = null) = request(port, "GET", path, "", token)
        private fun post(port: Int, path: String, body: Any, token: String? = null) = request(port, "POST", path, mapper.writeValueAsString(body), token)
        private fun request(port: Int, method: String, path: String, body: String, token: String? = null,
                            headers: Map<String, String> = emptyMap()): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body))
            token?.let { builder.header("Authorization", "Bearer $it") }
            headers.forEach { (name, value) -> builder.header(name, value) }
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
    }
}
