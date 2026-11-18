package com.cookie.health

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.Transferable
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

import io.nats.client.Nats
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Runs actual Identity and domain JARs with PostgreSQL, JetStream and SMTP. */
@Tag("integration")
@Testcontainers
class GoalAcceptanceIntegrationTest {
    @Test
    fun identityUserHealthRestartE2E() {
        assertThat(get(gateway.getMappedPort(8085),"/healthz").statusCode()).isEqualTo(404)
        assertThat(get(gateway.getMappedPort(8085),"/readyz").statusCode()).isEqualTo(404)
        val accountToken = createAccount()
        val otherToken = createAccount()
        var input = input(accountToken, "INITIAL", 0L, false)
        val healthPreview = preview(accountToken, input)
        assertThat(healthPreview.path("outcome").asString()).isEqualTo("NO_CALORIE_GOAL")
        assertThat(count("health","weight_measurements")).isZero()
        val receipt = healthPreview.path("receipt").asString()
        val acceptance = mapOf("receipt" to receipt, "input" to input)
        val key = UUID.randomUUID()
        val wrongAccount = request(userPort, "POST", "/v1/me/onboarding", mapper.writeValueAsString(acceptance),
            otherToken, keyed(UUID.randomUUID()))
        assertThat(wrongAccount.statusCode()).isEqualTo(409)

        healthProcess.destroyForcibly()
        assertThat(healthProcess.waitFor(15,TimeUnit.SECONDS)).isTrue()
        processes.remove(healthProcess)
        val accepted = request(userPort, "POST", "/v1/me/onboarding", mapper.writeValueAsString(acceptance), accountToken,keyed(key))
        assertThat(accepted.statusCode()).withFailMessage(accepted.body()).isEqualTo(202)
        val statusUrl = accepted.headers().firstValue("Location").orElseThrow()
        assertThat(get(userPort,statusUrl,otherToken).statusCode()).isEqualTo(404)
        assertThat(get(userPort,statusUrl,accountToken).body()).contains("PENDING")
        val replay = request(userPort, "POST", "/v1/me/onboarding", mapper.writeValueAsString(acceptance), accountToken,keyed(key))
        assertThat(mapper.readTree(replay.body()).path("onboardingId")).isEqualTo(mapper.readTree(accepted.body()).path("onboardingId"))
        val secondKey = request(userPort, "POST", "/v1/me/onboarding", mapper.writeValueAsString(acceptance), accountToken,keyed(UUID.randomUUID()))
        assertThat(secondKey.statusCode()).isEqualTo(202)
        assertThat(secondKey.headers().firstValue("Location").orElseThrow()).isEqualTo(statusUrl)
        assertThat(count("user_service","goal_attempts")).isEqualTo(1)
        // The command must be durable in JetStream before the producer stops.
        Nats.connect("nats://${nats.host}:${nats.getMappedPort(4222)}").use { connection ->
            val manager = connection.jetStreamManagement()
            await().atMost(Duration.ofSeconds(15)).until {
                runCatching {
                    manager.getLastMessage("COOKIE_EVENTS", "cookie.events.user.goal_assessment.requested.v1").seq > 0
                }.getOrDefault(false)
            }
        }
        userProcess.destroyForcibly()
        assertThat(userProcess.waitFor(15,TimeUnit.SECONDS)).isTrue()
        processes.remove(userProcess)
        healthProcess = start("health",healthEnvironment)
        awaitService(healthPort)
        // Simulate the result leaving broker retention while User is down.
        Nats.connect("nats://${nats.host}:${nats.getMappedPort(4222)}").use { connection ->
            val manager = connection.jetStreamManagement()
            val subject = "cookie.events.health.goal_assessment.resolved.v1"
            await().atMost(Duration.ofSeconds(25)).until {
                runCatching { manager.getLastMessage("COOKIE_EVENTS", subject).seq > 0 }.getOrDefault(false)
            }
            val message = manager.getLastMessage("COOKIE_EVENTS", subject)
            manager.deleteMessage("COOKIE_EVENTS", message.seq)
        }
        assertThat(count("health","assessment_executions")).isEqualTo(1)
        DriverManager.getConnection(database("user_service"),postgres.username,postgres.password).use { connection ->
            connection.createStatement().use { it.executeUpdate("UPDATE goal_attempts SET next_retry_at=now() WHERE status='PENDING'") }
        }
        userProcess = start("user", userEnvironment)
        awaitService(userPort)
        val completed = awaitAttempt(statusUrl,accountToken)
        assertThat(completed.path("outcome").asString()).isEqualTo("NO_CALORIE_GOAL")
        assertThat(count("health","weight_measurements")).isEqualTo(1)
        val measurement = mapper.readTree(get(healthPort, "/v1/health/weight", accountToken).body()).path("items")[0]
        val localDate = Instant.parse(input.getValue("measuredAt").toString()).atOffset(java.time.ZoneOffset.ofHours(3)).toLocalDate()
        assertThat(measurement.path("measurementLocalDate").stringValue()).isEqualTo(localDate.toString())
        assertThat(count("user_service","accepted_goal_versions")).isEqualTo(1)
        assertThat(count("user_service","hydration_goal_versions")).isEqualTo(1)

        val water = request(userPort, "PUT", "/v1/me/goals/hydration", """{"targetMl":2100}""",accountToken,keyed(UUID.randomUUID()))
        assertThat(water.statusCode()).withFailMessage(water.body()).isEqualTo(200)
        input = input(accountToken, "RECALC", 1L, true)
        val recalculated = preview(accountToken,input)
        val recalc = request(userPort,"POST","/v1/me/goals/acceptances",mapper.writeValueAsString(mapOf(
            "input" to input,"receipt" to recalculated.path("receipt").asString())),accountToken,keyed(UUID.randomUUID()))
        assertThat(recalc.statusCode()).withFailMessage(recalc.body()).isEqualTo(202)
        val recalcStatus = awaitAttempt(recalc.headers().firstValue("Location").orElseThrow(),accountToken)
        assertThat(recalcStatus.path("outcome").asString()).isEqualTo("CALCULATED")
        assertThat(count("health","weight_measurements")).isEqualTo(1)
        assertThat(count("user_service","accepted_goal_versions")).isEqualTo(2)
        assertThat(count("user_service","hydration_goal_versions")).isEqualTo(2)
        assertThat(mapper.readTree(get(userPort,"/v1/me/goals/hydration",accountToken).body()).path("targetMl").asInt()).isEqualTo(2100)

        val deletion = request(identityPort,"POST","/v1/auth/account-deletion-requests",
            mapper.writeValueAsString(mapOf("currentPassword" to PASSWORD)),accountToken,keyed(UUID.randomUUID()))
        assertThat(deletion.statusCode()).isEqualTo(202)
        await().atMost(Duration.ofSeconds(20)).until { get(healthPort,"/v1/health/weight",accountToken).statusCode()==404 }
        assertThat(count("health","weight_measurements")).isZero()
        await().atMost(Duration.ofSeconds(20)).until { count("user_service","accepted_goal_versions")==0 }
        assertThat(get(healthPort,"/v1/health/weight",otherToken).statusCode()).isEqualTo(200)
    }

    private fun createAccount(): String {
        val attempt = UUID.randomUUID()
        val email = "health-${attempt.toString().take(8)}@example.ru"
        val proof = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        val registration = post(identityPort,"/v1/auth/email/register",mapOf(
            "registrationAttemptId" to attempt,"email" to email,"password" to PASSWORD,"registrationProof" to proof,"locale" to "en"))
        assertThat(registration.statusCode()).isEqualTo(202)
        fun inbox() = mapper.readTree(get(mailpit.getMappedPort(8025),"/api/v1/messages").body()).path("messages")
        fun message() = inbox().firstOrNull { m -> m.path("To").any { it.path("Address").asString() == email } }
        await().atMost(Duration.ofSeconds(40)).until { message()!=null }
        val id = requireNotNull(message()).path("ID").asString()
        val text = mapper.readTree(get(mailpit.getMappedPort(8025),"/api/v1/message/$id").body()).path("Text").asString()
        val token = URLDecoder.decode(text.lineSequence().first { it.contains("#token=") }.substringAfter("#token="),StandardCharsets.UTF_8)
        assertThat(post(identityPort,"/v1/auth/email/verification/confirm",mapOf("token" to token,"registrationProof" to proof)).statusCode()).isEqualTo(204)
        val login = post(identityPort,"/v1/auth/email/login",mapOf("email" to email,"password" to PASSWORD,"deviceId" to "health-e2e"))
        assertThat(login.statusCode()).isEqualTo(200)
        return mapper.readTree(login.body()).path("accessToken").asString()
    }

    private fun input(token: String, purpose: String, revision: Long, standard: Boolean): Map<String,Any> {
        val profile = mapper.readTree(get(userPort,"/v1/me/profile",token).body())
        return mapOf("purpose" to purpose,"timezone" to "UTC","profileVersion" to profile.path("version").asLong(),
            "goalRevision" to revision,"sex" to "MALE","birthDate" to "1996-01-01","heightCm" to 170,"currentWeightKg" to 70,
            "weeklyChangeKg" to 0,"activity" to "ACTIVE","macroProfile" to "BALANCED","standardAdultPolicyApplies" to standard,
            "measuredAt" to Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MICROS).toString(),
            "measurementOffsetMinutes" to 180,
            "foodRestrictions" to listOf("VEGAN"),"dietStyles" to emptyList<String>(),"allergies" to emptyList<String>())
    }

    private fun preview(token: String, input: Map<String,Any>): JsonNode {
        val response = post(gateway.getMappedPort(8085),"/v1/health/nutrition-assessments",input,token)
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
        return mapper.readTree(response.body())
    }

    private fun awaitAttempt(url: String, token: String): JsonNode {
        fun current() = mapper.readTree(get(userPort,url,token).body())
        await().atMost(Duration.ofSeconds(35)).until { current().path("status").asString()=="COMPLETED" }
        return current()
    }

    private fun keyed(key: UUID) = mapOf("Idempotency-Key" to key.toString())

    private fun count(database: String, table: String): Int =
        DriverManager.getConnection(Companion.database(database),postgres.username,postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use { rows -> check(rows.next()); rows.getInt(1) }
            }
        }

    companion object {
        private const val PASSWORD = "Integration-Strong-Password-2026"
        class ServiceContainer(image: String) : GenericContainer<ServiceContainer>(image)
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:18.6-alpine3.24").withDatabaseName("identity")
        @Container @JvmStatic val nats = ServiceContainer("nats:2.14.6-alpine3.22").withCommand("-js").withExposedPorts(4222)
        @Container @JvmStatic val redis = ServiceContainer("redis:8.2-alpine").withExposedPorts(6379)
        @Container @JvmStatic val mailpit = ServiceContainer("axllent/mailpit:v1.31.0").withExposedPorts(1025, 8025)
        private val mapper = JsonMapper.builder().findAndAddModules().build()
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        private val processes = mutableListOf<Process>()
        private val temporary = Files.createTempDirectory("cookie-health-flow-")
        private var identityPort = 0
        private var notificationPort = 0
        private var userPort = 0
        private var healthPort = 0
        private lateinit var gateway: ServiceContainer
        private lateinit var userEnvironment: Map<String,String>
        private lateinit var userProcess: Process
        private lateinit var healthEnvironment: Map<String,String>
        private lateinit var healthProcess: Process

        @BeforeAll @JvmStatic fun startServices() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { db ->
                db.createStatement().use { it.execute("CREATE DATABASE notification")
                    it.execute("CREATE DATABASE health")
                    it.execute("CREATE DATABASE user_service")
                    it.execute("CREATE ROLE health_app LOGIN PASSWORD 'runtime-test-only'")
                    it.execute("CREATE ROLE user_app LOGIN PASSWORD 'runtime-test-only'") }
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
            val signing = ECKeyGenerator(Curve.P_256).keyID("health-receipt").generate()
            val healthCrypto = RSAKeyGenerator(2048).keyID("health-decrypt").generate()
            val userCrypto = RSAKeyGenerator(2048).keyID("user-decrypt").generate()
            fun keyFile(name: String, contents: String): String = Files.writeString(temporary.resolve(name), contents).toString()
            val assessment = common + mapOf(
                "COOKIE_IDENTITY_JWKS_URL" to "http://localhost:$identityPort/v1/auth/jwks",
                "COOKIE_HEALTH_RECEIPT_VERIFICATION_KEYS" to keyFile("signing-public.json", JWKSet(signing.toPublicJWK()).toString()),
                "COOKIE_HEALTH_ASSESSMENT_ENCRYPTION_KEYS" to keyFile("health-public.json", JWKSet(healthCrypto.toPublicJWK()).toString()),
                "COOKIE_USER_ASSESSMENT_ENCRYPTION_KEYS" to keyFile("user-public.json", JWKSet(userCrypto.toPublicJWK()).toString()),
            )
            userPort = freePort()
            healthPort = freePort()
            migrate("user","user_service")
            migrate("health","health")
            userEnvironment = assessment + mapOf(
                "PORT" to userPort.toString(), "COOKIE_USER_DATABASE_URL" to database("user_service"),
                "COOKIE_USER_DATABASE_USER" to "user_app", "COOKIE_USER_DATABASE_PASSWORD" to "runtime-test-only",
                "COOKIE_USER_ASSESSMENT_DECRYPTION_KEYS" to keyFile("user-private.json", JWKSet(userCrypto).toString(false)),
            )
            userProcess = start("user", userEnvironment)
            healthEnvironment = assessment + mapOf(
                "PORT" to healthPort.toString(), "COOKIE_HEALTH_DATABASE_URL" to database("health"),
                "COOKIE_HEALTH_DATABASE_USER" to "health_app", "COOKIE_HEALTH_DATABASE_PASSWORD" to "runtime-test-only",
                "COOKIE_HEALTH_RECEIPT_KEY_PATH" to keyFile("signing-private.json", signing.toJSONString()),
                "COOKIE_HEALTH_ASSESSMENT_DECRYPTION_KEYS" to keyFile("health-private.json", JWKSet(healthCrypto).toString(false)),
            )
            healthProcess = start("health",healthEnvironment)
            awaitService(userPort)
            awaitService(healthPort)
            val gatewayConfig = Files.readString(Path.of(requireNotNull(System.getProperty("cookie.test.health-gateway"))))
                .replace("host.docker.internal:8084","host.docker.internal:$healthPort")
                .replace("host.docker.internal:8083","host.docker.internal:$userPort")
                .replace("host.docker.internal:8080","host.docker.internal:$identityPort")
            gateway = ServiceContainer("caddy:2-alpine").withExposedPorts(8085)
                .withExtraHost("host.docker.internal","host-gateway")
                .withCopyToContainer(Transferable.of(gatewayConfig), "/etc/caddy/Caddyfile")
            gateway.start()
        }

        private fun database(name: String) = postgres.jdbcUrl.replace("/identity","/$name")

        private fun migrate(service: String, database: String) {
            val log = temporary.resolve("$service-migrate.log")
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx192m","-jar",
                requireNotNull(System.getProperty("cookie.test.$service-jar")), "--cookie.$service.mode=migrate").apply {
                environment().putAll(mapOf("SPRING_FLYWAY_URL" to database(database),
                    "SPRING_FLYWAY_USER" to postgres.username,"SPRING_FLYWAY_PASSWORD" to postgres.password))
                redirectErrorStream(true)
                redirectOutput(log.toFile())
            }.start()
            try {
                assertThat(process.waitFor(60,TimeUnit.SECONDS)).isTrue()
                assertThat(process.exitValue()).withFailMessage("%s",Files.readString(log)).isZero()
            } finally { if (process.isAlive) process.destroyForcibly() }
        }

        private fun start(service: String, environment: Map<String, String>): Process {
            val jar = requireNotNull(System.getProperty("cookie.test.$service-jar"))
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx384m", "-jar", jar).apply {
                environment().putAll(environment)
                redirectErrorStream(true)
                redirectOutput(temporary.resolve("$service.log").toFile())
            }.start()
            processes += process
            return process
        }

        private fun awaitService(port: Int) {
            await().atMost(Duration.ofSeconds(75)).pollInterval(Duration.ofMillis(250)).until {
                check(processes.all { it.isAlive }) { "A service exited; inspect synthetic-test logs at $temporary" }
                runCatching { get(port, "/readyz").statusCode() == 200 }.getOrDefault(false)
            }
        }

        @AfterAll @JvmStatic fun stopServices() {
            if (::gateway.isInitialized) gateway.stop()
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
