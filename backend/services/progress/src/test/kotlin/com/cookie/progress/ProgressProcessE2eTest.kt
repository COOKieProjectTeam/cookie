package com.cookie.progress

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
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
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

@Tag("process-e2e")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProgressProcessE2eTest {
    private val root = Path.of(requireNotNull(System.getProperty("cookie.test.root")))
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val processes = mutableMapOf<String, Process>()
    private val ports = mutableMapOf<String, Int>()
    private lateinit var work: Path

    @BeforeAll
    fun startServices(@TempDir directory: Path) {
        work = directory
        val services = listOf("identity", "notification", "user", "health", "nutrition", "progress")
        for (service in services) provision(service)
        for (service in services) migrate(service)
        for (owner in listOf("user", "nutrition")) {
            if (owner == "nutrition") runSqlFile(owner, "runtime-grants.sql")
            runSqlFile(owner, "progress-capture.sql")
            val prefix = "COOKIE_" + owner.uppercase() + "_"
            val mode = if (owner == "nutrition") "--progress-commit-worker" else "--cookie.user.mode=progress-capture"
            launch(owner, "$owner-capture", mapOf(
                prefix + "DATABASE_URL" to database(owner),
                prefix + "CAPTURE_USER" to owner + "_progress_capture",
                prefix + "CAPTURE_PASSWORD" to RUNTIME_PASSWORD,
                prefix + "REPLICATION_USER" to owner + "_progress_replication",
                prefix + "REPLICATION_PASSWORD" to RUNTIME_PASSWORD,
            ), mode)
        }
        val emailKey = directory.resolve("email-private.jwk")
        start("identity", mapOf(
            "SPRING_FLYWAY_ENABLED" to "false",
            "COOKIE_DEV_NOTIFICATION_PRIVATE_KEY_OUTPUT_PATH" to emailKey.toString(),
        ))
        start("notification", mapOf(
            "COOKIE_NOTIFICATION_PRIVATE_KEY_PATH" to emailKey.toString(),
            "SPRING_DATA_REDIS_HOST" to redis.host,
            "SPRING_DATA_REDIS_PORT" to redis.getMappedPort(6379).toString(),
            "COOKIE_SMTP_HOST" to mailpit.host,
            "COOKIE_SMTP_PORT" to mailpit.getMappedPort(1025).toString(),
            "COOKIE_SMTP_STARTTLS_REQUIRED" to "false",
            "COOKIE_FCM_ENABLED" to "false",
        ))
        val receipt = ECKeyGenerator(Curve.P_256).keyID("progress-test-receipt").generate()
        val healthKey = RSAKeyGenerator(2048).keyID("progress-test-health").generate()
        val userKey = RSAKeyGenerator(2048).keyID("progress-test-user").generate()
        fun key(name: String, value: String) = Files.writeString(directory.resolve("$name.json"), value).toString()
        val verificationKeys = mapOf(
            "COOKIE_HEALTH_RECEIPT_VERIFICATION_KEYS" to key("receipt-public", JWKSet(receipt.toPublicJWK()).toString()),
            "COOKIE_HEALTH_ASSESSMENT_ENCRYPTION_KEYS" to key("health-public", JWKSet(healthKey.toPublicJWK()).toString()),
            "COOKIE_USER_ASSESSMENT_ENCRYPTION_KEYS" to key("user-public", JWKSet(userKey.toPublicJWK()).toString()),
        )
        start("user", verificationKeys + ("COOKIE_USER_ASSESSMENT_DECRYPTION_KEYS" to key("user-private", JWKSet(userKey).toString(false))))
        start("health", verificationKeys + mapOf(
            "COOKIE_HEALTH_RECEIPT_KEY_PATH" to key("receipt-private", receipt.toJSONString()),
            "COOKIE_HEALTH_ASSESSMENT_DECRYPTION_KEYS" to key("health-private", JWKSet(healthKey).toString(false)),
        ))
        val keys = call("identity", "/v1/auth/jwks")
        expect(keys, 200)
        start("nutrition", mapOf("COOKIE_IDENTITY_JWKS_PATH" to key("identity-public", keys.body())))
        start("progress")
    }

    @AfterAll
    fun stopServices() {
        for (process in processes.values.reversed()) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun acceptedGoalAndDiaryReachProgressAndDriveNotification() {
        val token = registerAndLogin()
        val account = UUID.fromString(SignedJWT.parse(token).jwtClaimsSet.subject)
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val day = now.atOffset(ZoneOffset.UTC).toLocalDate()
        val profile = json(call("user", "/v1/me/profile", token))
        val input = mapOf(
            "purpose" to "INITIAL", "profileVersion" to profile.path("version").longValue(), "goalRevision" to 0,
            "timezone" to "UTC", "sex" to "MALE", "birthDate" to "1996-01-01", "heightCm" to 170,
            "currentWeightKg" to 70, "weeklyChangeKg" to 0, "activity" to "ACTIVE", "macroProfile" to "BALANCED",
            "standardAdultPolicyApplies" to true, "measuredAt" to now.minusSeconds(1).toString(),
            "measurementOffsetMinutes" to 0, "foodRestrictions" to emptyList<String>(),
            "dietStyles" to emptyList<String>(), "allergies" to emptyList<String>(),
        )
        val preview = json(call("health", "/v1/health/nutrition-assessments", token, "POST", input))
        assertThat(preview.path("outcome").stringValue()).isEqualTo("CALCULATED")
        fun center(field: String) = preview.path(field).path("center").decimalValue()
        val kcal = center("calories")
        postMeal(token, day, mapOf("calories" to kcal, "proteinG" to center("proteinG"), "fatG" to center("fatG"),
            "totalCarbohydrateG" to center("carbsG") + BigDecimal.ONE, "fiberG" to BigDecimal.ONE))

        val accepted = call("user", "/v1/me/onboarding", token, "POST",
            mapOf("input" to input, "receipt" to preview.path("receipt").stringValue()), keyed = true)
        expect(accepted, 202)
        val location = accepted.headers().firstValue("Location").orElseThrow()
        eventually("User commits the Health result") {
            assertThat(json(call("user", location, token)).path("status").stringValue()).isEqualTo("COMPLETED")
        }
        val dayPath = "/v1/progress/days/$day"
        eventually("Progress applies the first accepted goal to the existing meal") {
            val row = json(call("progress", dayPath, token))
            assertThat(row.path("calendar").path("state").stringValue()).isEqualTo("OPEN")
            assertThat(row.path("goal").path("state").stringValue()).isEqualTo("CALCULATED")
            assertThat(row.path("goal").path("revision").longValue()).isEqualTo(1)
            assertThat(row.path("facts").path("mealCount").longValue()).isEqualTo(1)
            assertThat(row.path("facts").path("caloriesKcal").stringValue().toBigDecimal()).isEqualByComparingTo(kcal)
            assertThat(row.path("achievement").path("stage").stringValue()).isEqualTo("PROVISIONAL")
            assertThat(row.path("achievement").path("outcome").stringValue()).isEqualTo("MACROS_MET")
            assertThat(row.path("streak").path("current").longValue()).isEqualTo(1)
            assertThat(row.path("streak").path("confirmedBest").longValue()).isZero()
            db("progress") { connection ->
                connection.prepareStatement("""SELECT DISTINCT f.owner FROM source_facts f
                    JOIN source_groups g ON g.user_id=f.user_id AND g.owner=f.owner
                      AND f.source_seq BETWEEN g.first_seq AND g.last_seq
                    JOIN source_cursors c ON c.user_id=f.user_id AND c.owner=f.owner AND c.applied_through>=f.source_seq
                    WHERE f.user_id=? AND f.kind IN ('user.goal.changed','nutrition.day.changed')""").use { statement ->
                    statement.setObject(1, account)
                    val certifiedOwners = statement.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
                    assertThat(certifiedOwners).containsExactlyInAnyOrder("user", "nutrition")
                }
            }
        }

        val overridePath = "/v1/me/goals/overrides/$day"
        expect(call("user", overridePath, token, "PUT", mapOf("type" to "CHEAT_DAY", "unlimitedCalories" to true), keyed = true), 200)
        val extra = BigDecimal("301")
        postMeal(token, day, mapOf("calories" to extra, "proteinG" to BigDecimal.ZERO, "fatG" to BigDecimal.ZERO,
            "totalCarbohydrateG" to BigDecimal.ZERO, "fiberG" to BigDecimal.ZERO))
        eventually("Notification consumes the protected over-target v2 snapshot") {
            val row = json(call("progress", dayPath, token))
            assertThat(row.path("projectionState").stringValue()).isEqualTo("READY")
            assertThat(row.path("achievement").path("outcome").asString("")).isEqualTo("CHEAT_DAY_CREDIT")
            assertThat(row.path("streak").path("current").longValue()).isEqualTo(1)
            val snapshot = notificationSnapshot(account, day)
            assertThat(snapshot.path("wireVersion").intValue()).isEqualTo(2)
            val calories = snapshot.path("calories")
            assertThat(calories.path("goalId").stringValue()).isEqualTo(row.path("goal").path("id").stringValue())
            assertThat(calories.path("consumed").decimalValue()).isEqualByComparingTo(kcal + extra)
            assertThat(calories.path("consumed").decimalValue()).isGreaterThan(calories.path("high").decimalValue())
            assertThat(calories.path("unlimitedCalories").booleanValue()).isTrue()
        }
        expect(call("notification", "/v1/notifications/devices", token, "POST", mapOf(
            "platform" to "ANDROID", "token" to "synthetic-fcm-token", "deviceId" to "progress-e2e",
        )), 201)
        expect(call("notification", "/v1/notifications/settings", token, "PATCH", mapOf(
            "timezone" to "UTC", "goalWarning" to mapOf("enabled" to true),
            "water" to mapOf("enabled" to false), "food" to mapOf("enabled" to false),
            "weighIn" to mapOf("enabled" to false), "quietHours" to mapOf("start" to "00:00", "end" to "00:00"),
        )), 200)
        eventually("Scheduler completes its evaluation without a calorie warning for CHEAT_DAY") {
            assertThat(notificationCount(
                "SELECT count(*) FROM reminder_deadlines WHERE user_id=? AND due_at>clock_timestamp()", account,
            )).isOne()
            assertThat(warnings(account)).isZero()
        }
        expect(call("user", overridePath, token, "DELETE", keyed = true), 204)
        eventually("Removing the override allows one scheduler occurrence and push job for the same intake") {
            val row = json(call("progress", dayPath, token))
            assertThat(row.path("projectionState").stringValue()).isEqualTo("READY")
            assertThat(row.path("achievement").path("outcome").asString("")).isEqualTo("OUT_OF_TARGET")
            assertThat(row.path("streak").path("current").longValue()).isZero()
            assertThat(notificationSnapshot(account, day).path("calories").path("unlimitedCalories").booleanValue()).isFalse()
            assertThat(warnings(account)).isOne()
            assertThat(notificationCount("""SELECT count(*) FROM delivery_jobs j JOIN reminder_occurrences o
                ON o.id=j.occurrence_id WHERE o.user_id=? AND o.kind='CALORIES_EXCEEDED'""", account)).isOne()
        }
    }

    private fun registerAndLogin(): String {
        val attempt = UUID.randomUUID()
        val email = "progress-$attempt@example.ru"
        val proof = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        expect(call("identity", "/v1/auth/email/register", method = "POST", body = mapOf(
            "registrationAttemptId" to attempt, "email" to email, "password" to ACCOUNT_PASSWORD,
            "registrationProof" to proof, "locale" to "en",
        )), 202)
        fun mail(path: String) = json(request(URI("http://" + mailpit.host + ":" + mailpit.getMappedPort(8025) + path)))
        fun verificationMail() = mail("/api/v1/messages").path("messages").firstOrNull { row ->
            row.path("To").any { it.path("Address").stringValue() == email }
        }
        await().atMost(Duration.ofSeconds(60)).until { verificationMail() != null }
        val id = requireNotNull(verificationMail()).path("ID").stringValue()
        val message = mail("/api/v1/message/$id").path("Text").stringValue()
        val encodedToken = message.lineSequence().first { "#token=" in it }.substringAfter("#token=")
        expect(call("identity", "/v1/auth/email/verification/confirm", method = "POST", body = mapOf(
            "token" to URLDecoder.decode(encodedToken, StandardCharsets.UTF_8), "registrationProof" to proof,
        )), 204)
        return json(call("identity", "/v1/auth/email/login", method = "POST", body = mapOf(
            "email" to email, "password" to ACCOUNT_PASSWORD, "deviceId" to "progress-e2e",
        ))).path("accessToken").stringValue()
    }

    private fun postMeal(token: String, day: LocalDate, nutrition: Map<String, BigDecimal>) {
        val item = mapOf("source" to mapOf("type" to "MANUAL"), "name" to "Test food", "baseUnit" to "G",
            "amount" to mapOf("value" to 100, "unit" to "G"), "nutritionPer100" to nutrition)
        expect(call("nutrition", "/v1/diary/meals", token, "POST", mapOf(
            "clientGeneratedId" to UUID.randomUUID(), "localDate" to day.toString(),
            "eatenAt" to day.atStartOfDay().toInstant(ZoneOffset.UTC).toString(), "timezone" to "UTC",
            "dishes" to listOf(mapOf("name" to "Test meal", "items" to listOf(item))),
        )), 201)
    }

    private fun notificationSnapshot(account: UUID, day: LocalDate): JsonNode = db("notification") { connection ->
        connection.prepareStatement("SELECT payload::text FROM event_projections WHERE user_id=? AND kind='PROGRESS' AND entity_id=?").use {
            it.setObject(1, account)
            it.setString(2, "UTC:$day")
            it.executeQuery().use { rows ->
                assertThat(rows.next()).isTrue()
                mapper.readTree(rows.getString(1))
            }
        }
    }

    private fun warnings(account: UUID) = notificationCount(
        "SELECT count(*) FROM reminder_occurrences WHERE user_id=? AND kind IN ('CALORIES_EXCEEDED','CALORIES_UNDER_TARGET')", account,
    )

    private fun notificationCount(sql: String, account: UUID): Long = db("notification") { connection ->
        connection.prepareStatement(sql).use {
            it.setObject(1, account)
            it.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
        }
    }

    private fun database(service: String) = postgres.jdbcUrl.substringBeforeLast('/') + "/" + service
    private fun runtimeRole(service: String) = if (service == "nutrition") "nutrition_runtime" else service + "_app"
    private fun <T> db(service: String, action: (Connection) -> T): T =
        DriverManager.getConnection(database(service), postgres.username, postgres.password).use(action)

    private fun provision(service: String) {
        db("postgres") { connection ->
            connection.createStatement().use { sql ->
                sql.execute("CREATE DATABASE \"$service\"")
                sql.execute("REVOKE CONNECT ON DATABASE \"$service\" FROM PUBLIC")
                val roles = mutableListOf(runtimeRole(service))
                if (service in setOf("user", "nutrition")) roles += listOf(service + "_progress_capture", service + "_progress_replication")
                for (role in roles) {
                    val replication = if (role.endsWith("_replication")) "REPLICATION" else "NOREPLICATION"
                    sql.execute("CREATE ROLE $role LOGIN $replication PASSWORD '$RUNTIME_PASSWORD'")
                    sql.execute("GRANT CONNECT ON DATABASE \"$service\" TO $role")
                }
            }
        }
    }

    private fun migrate(service: String) {
        if (service == "identity" || service == "nutrition") {
            val location = root.resolve("backend/services/$service/src/main/resources/db/migration")
            Flyway.configure().dataSource(database(service), postgres.username, postgres.password)
                .locations("filesystem:$location").load().migrate()
            if (service == "identity") db(service) { connection ->
                connection.createStatement().use {
                    it.execute("GRANT USAGE ON SCHEMA public TO identity_app")
                    it.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO identity_app")
                    it.execute("REVOKE ALL ON flyway_schema_history FROM identity_app")
                    it.execute("GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO identity_app")
                }
            }
        } else {
            val name = "$service-migration"
            val process = launch(service, name, mapOf(
                "SPRING_FLYWAY_URL" to database(service), "SPRING_FLYWAY_USER" to postgres.username,
                "SPRING_FLYWAY_PASSWORD" to postgres.password,
            ), "--cookie.$service.mode=migrate")
            assertThat(process.waitFor(90, TimeUnit.SECONDS)).describedAs("$name timeout").isTrue()
            assertThat(process.exitValue()).withFailMessage("%s", Files.readString(work.resolve("$name.log"))).isZero()
            processes.remove(name)
        }
    }

    private fun runSqlFile(service: String, filename: String) {
        val source = root.resolve("backend/services/$service/ops/$filename")
        val destination = "/tmp/$service-$filename"
        postgres.copyFileToContainer(Transferable.of(Files.readAllBytes(source)), destination)
        val result = postgres.execInContainer("psql", "-X", "-U", postgres.username, "-d", service,
            "-v", "ON_ERROR_STOP=1", "-f", destination)
        assertThat(result.exitCode).withFailMessage("%s", result.stderr).isZero()
    }

    private fun start(service: String, additions: Map<String, String> = emptyMap()) {
        val port = ServerSocket(0).use { it.localPort }
        ports[service] = port
        val prefix = "COOKIE_" + service.uppercase() + "_"
        val env = mapOf(
            "SPRING_PROFILES_ACTIVE" to "test", "PORT" to port.toString(),
            prefix + "DATABASE_URL" to database(service), prefix + "DATABASE_USER" to runtimeRole(service),
            prefix + "DATABASE_PASSWORD" to RUNTIME_PASSWORD, "COOKIE_NATS_URL" to "nats://" + nats.host + ":" + nats.getMappedPort(4222),
            "COOKIE_IDENTITY_JWKS_URL" to "http://127.0.0.1:" + ports.getValue("identity") + "/v1/auth/jwks",
        )
        launch(service, service, env + additions)
        await().atMost(Duration.ofSeconds(90)).until {
            checkAlive()
            runCatching { call(service, "/readyz").statusCode() == 200 }.getOrDefault(false)
        }
    }

    private fun launch(service: String, name: String, env: Map<String, String>, vararg args: String): Process {
        val jar = requireNotNull(System.getProperty("cookie.test.$service-jar"))
        val command = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx192m", "-XX:ActiveProcessorCount=2", "-jar", jar) + args
        return ProcessBuilder(command).apply {
            environment().putAll(env)
            redirectErrorStream(true)
            redirectOutput(work.resolve("$name.log").toFile())
        }.start().also { processes[name] = it }
    }

    private fun checkAlive() {
        for ((name, process) in processes) check(process.isAlive) {
            "$name exited with code " + process.exitValue() + ". See " + work.resolve("$name.log")
        }
    }

    private fun eventually(description: String, assertion: () -> Unit) {
        await().alias(description).atMost(Duration.ofSeconds(75)).untilAsserted {
            checkAlive()
            assertion()
        }
    }

    private fun call(service: String, path: String, token: String? = null, method: String = "GET",
                     body: Any? = null, keyed: Boolean = false): HttpResponse<String> =
        request(URI("http://127.0.0.1:" + ports.getValue(service) + path), token, method, body, keyed)

    private fun request(uri: URI, token: String? = null, method: String = "GET", body: Any? = null,
                        keyed: Boolean = false): HttpResponse<String> {
        val content = body?.let { HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(it)) }
            ?: HttpRequest.BodyPublishers.noBody()
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json").method(method, content)
        token?.let { builder.header("Authorization", "Bearer $it") }
        if (keyed) builder.header("Idempotency-Key", UUID.randomUUID().toString())
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun json(response: HttpResponse<String>): JsonNode {
        expect(response, 200)
        return mapper.readTree(response.body())
    }

    private fun expect(response: HttpResponse<String>, status: Int) {
        assertThat(response.statusCode()).withFailMessage("%s %s returned %s, expected %s",
            response.request().method(), response.uri().path, response.statusCode(), status).isEqualTo(status)
    }

    companion object {
        private const val RUNTIME_PASSWORD = "progress-local-runtime"
        private const val ACCOUNT_PASSWORD = "Progress-Integration-Test-2026"
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:18.6-alpine3.24")
            .withDatabaseName("postgres").withCommand("postgres", "-c", "wal_level=logical")
        @Container @JvmStatic val nats = TestService("nats:2.14.6-alpine3.22").withCommand("-js").withExposedPorts(4222)
        @Container @JvmStatic val redis = TestService("redis:8.2-alpine").withExposedPorts(6379)
        @Container @JvmStatic val mailpit = TestService("axllent/mailpit:v1.31.0").withExposedPorts(1025, 8025)
        class TestService(image: String) : GenericContainer<TestService>(image)
    }
}
