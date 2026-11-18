package com.cookie.progress

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.cookie.progress.client.ProgressTodayClient
import com.sun.net.httpserver.HttpServer
import org.flywaydb.core.Flyway
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.Duration
import java.nio.file.Files
import java.util.Date
import java.util.UUID

@Tag("integration")
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProgressHttpTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var inbox: ProgressInbox
    @Autowired private lateinit var mapper: ObjectMapper

    @Test
    fun `generated HTTP transport enforces ownership and preserves exact diary totals`() {
        awaitReadiness()
        val user = account()
        val other = account()
        val day = "2026-11-18"
        val timestamp = Instant.now().toString()
        val daySubject = "nutrition.day.changed"
        ingest(user, daySubject, 3, "nutrition_day", "$user:$day", timestamp, 1, mapOf(
            "localDate" to day, "sourceSeq" to 1, "mealCount" to 1, "zoneProvenance" to mapOf("state" to "EMPTY"),
            "nutritionTotals" to mapOf("calories" to "9000000000000.001".toBigDecimal(), "proteinG" to null,
                "fatG" to 0, "carbsG" to 0, "fiberG" to null),
        ))
        ingest(user, "nutrition.progress_commit.recorded", 1, "progress_source", user.toString(), timestamp, null,
            mapOf("firstSeq" to 1, "lastSeq" to 1, "committedAt" to timestamp, "endLsn" to "0/F100"))
        val path = "/v1/progress/days/$day"
        assertEquals(401, get(path).statusCode())
        val snapshot = get(path, token(user))
        assertEquals(200, snapshot.statusCode(), snapshot.body())
        val facts = mapper.readTree(snapshot.body()).path("facts")
        assertEquals("9000000000000.001", facts.path("caloriesKcal").stringValue())
        assertTrue(facts.path("proteinG").isNull)
        val unrelated = get(path, token(other))
        assertEquals(0L, mapper.readTree(unrelated.body()).path("facts").path("mealCount").longValue())
        assertEquals(400, get("/v1/progress?from=$day", token(user)).statusCode())
        assertEquals(400, get("/v1/progress?from=2025-11-17&to=$day", token(user)).statusCode())
        assertEquals(200, get("/v1/progress/weight?from=$day&to=$day", token(user)).statusCode())
        assertEquals(200, get("/healthz").statusCode())
        assertEquals(200, get("/readyz").statusCode())
    }

    @Test
    fun `generated BFF client requires a verified workload and separate delegated user`() {
        awaitReadiness()
        val path = "/internal/v1/progress/today"
        val user = token(account())
        val credential = workloadToken("mobile_bff")
        assertEquals(401, get(path, user).statusCode())
        assertEquals(401, get(path, workloadToken("mobile_bff", "user"), user).statusCode())
        assertEquals(401, get(path, workloadToken("mobile_bff", lifetime = 600), user).statusCode())
        assertEquals(403, get(path, workloadToken("nutrition"), user).statusCode())
        assertEquals(403, get(path, credential).statusCode())
        assertEquals(403, get(path, credential, credential).statusCode())
        val client = ProgressTodayClient(URI.create("http://127.0.0.1:$port"), allowLocalHttp = true)
        val today = client.today(credential, user, "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")
        assertEquals(0L, today.facts.mealCount)
        assertEquals("AWAITING_SOURCE", today.projectionState.value)
    }

    private fun ingest(
        user: UUID, type: String, wire: Int, aggregate: String, key: String,
        at: String, version: Long?, payload: Map<String, Any?>,
    ) {
        inbox.receive("cookie.events.$type.v$wire", mapper.writeValueAsBytes(mapOf(
            "event_id" to UUID.randomUUID(), "event_type" to type, "event_version" to wire, "occurred_at" to at,
            "producer" to "nutrition", "aggregate_type" to aggregate, "aggregate_id" to key,
            "aggregate_version" to version, "payload" to payload + ("userId" to user),
        )))
    }

    private fun get(path: String, token: String? = null, delegated: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET()
        if (token != null) request.header("Authorization", "Bearer $token")
        if (delegated != null) request.header("X-Cookie-User-Token", delegated)
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun awaitReadiness() {
        await().atMost(Duration.ofSeconds(15)).until { get("/readyz").statusCode() == 200 }
    }

    private fun account() = UUID.fromString("019a0000-0000-7000-8000-${UUID.randomUUID().toString().takeLast(12)}")

    private fun token(user: UUID): String {
        val now = Instant.now()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("at+jwt")).keyID(identity.keyID).build(),
            JWTClaimsSet.Builder().issuer("https://api.cookie.app").audience("cookie-api").subject(user.toString())
                .issueTime(Date.from(now.minusSeconds(2))).expirationTime(Date.from(now.plusSeconds(120)))
                .jwtID(account().toString()).claim("sid", account().toString()).build(),
        ).apply { sign(ECDSASigner(identity)) }.serialize()
    }

    private fun workloadToken(subject: String, audience: String = "progress", lifetime: Long = 120): String {
        val now = Instant.now()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("workload+jwt")).keyID(workload.keyID).build(),
            JWTClaimsSet.Builder().issuer(WORKLOAD_ISSUER).audience(audience).subject(subject)
                .issueTime(Date.from(now.minusSeconds(2))).expirationTime(Date.from(now.plusSeconds(lifetime))).build(),
        ).apply { sign(ECDSASigner(workload)) }.serialize()
    }

    companion object {
        class NatsContainer : GenericContainer<NatsContainer>("nats:2.14.6-alpine")
        @Container @JvmStatic val pg = PostgreSQLContainer("postgres:18-alpine")
        @Container @JvmStatic val nats = NatsContainer().withCommand("-js").withExposedPorts(4222)
        private val identity = ECKeyGenerator(Curve.P_256).keyID("integration").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256).generate()
        private const val WORKLOAD_ISSUER = "https://workload.example.test"
        private val workload = ECKeyGenerator(Curve.P_256).keyID("internal-integration").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256).generate()
        private val workloadJwks = Files.createTempFile("progress-workload-jwks", ".json").also {
            Files.writeString(it, JWKSet(workload.toPublicJWK()).toString())
            it.toFile().deleteOnExit()
        }
        private val jwks = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/auth/jwks") { exchange ->
                val body = JWKSet(identity.toPublicJWK()).toString().toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            val owner = DriverManagerDataSource(pg.jdbcUrl, pg.username, pg.password)
            JdbcTemplate(owner).execute("CREATE ROLE progress_app LOGIN PASSWORD 'local-integration'")
            Flyway.configure().dataSource(owner).load().migrate()
            registry.add("spring.datasource.url", pg::getJdbcUrl)
            registry.add("spring.datasource.username") { "progress_app" }
            registry.add("spring.datasource.password") { "local-integration" }
            registry.add("cookie.progress.nats-url") { "nats://${nats.host}:${nats.getMappedPort(4222)}" }
            registry.add("cookie.progress.jwks-url") { "http://127.0.0.1:${jwks.address.port}/v1/auth/jwks" }
            registry.add("cookie.progress.workload-issuer") { WORKLOAD_ISSUER }
            registry.add("cookie.progress.workload-jwks-path") { workloadJwks.toString() }
        }

        @org.junit.jupiter.api.AfterAll
        @JvmStatic
        fun stopJwks() = jwks.stop(0)
    }
}
