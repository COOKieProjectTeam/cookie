package com.cookie.identity

import com.cookie.identity.generated.model.EmailLoginRequest
import com.cookie.identity.generated.model.EmailRegistrationRequest
import com.cookie.identity.generated.model.EmailVerificationConfirmRequest
import com.cookie.identity.generated.model.RefreshTokenRequest
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.crypto.RSADecrypter
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class IdentityFlowIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `register confirm refresh replay and encrypted outbox are atomic`() {
        val unique = UUID.randomUUID().toString().take(8)
        val email = "identity-$unique@пример.рф"
        val password = "НадежныйПароль-$unique"

        val register = post("/v1/auth/email/register", EmailRegistrationRequest(email, password, "ru-RU"))
        assertThat(register.statusCode()).isEqualTo(HttpStatus.ACCEPTED.value())
        val canonicalEmail = "identity-$unique@xn--e1afmkfd.xn--p1ai"
        val accountId = requireNotNull(
            jdbc.queryForObject(
                "SELECT account_id FROM email_credentials WHERE email = ?",
                UUID::class.java,
                canonicalEmail,
            ),
        )

        val payloadJson = requireNotNull(
            jdbc.queryForObject(
                """
                SELECT payload::text FROM outbox_events
                WHERE event_type = 'notification.email.requested' AND aggregate_id = ?
                ORDER BY occurred_at DESC LIMIT 1
                """.trimIndent(),
                String::class.java,
                accountId.toString(),
            ),
        )
        assertThat(payloadJson).doesNotContain(email, password)
        val compactJwe = objectMapper.readTree(payloadJson).path("encryptedPayload").stringValue()
        val jwe = JWEObject.parse(compactJwe).apply { decrypt(RSADecrypter(notificationPrivateKey)) }
        val delivery = objectMapper.readTree(jwe.payload.toString())
        assertThat(delivery.path("recipientEmail").stringValue()).isEqualTo(canonicalEmail)
        val verificationToken = delivery.path("token").stringValue()
        val verificationTokenId = UUID.fromString(verificationToken.split('.', limit = 3)[1])
        assertThat(
            jdbc.queryForObject(
                "SELECT token_hash FROM auth_action_tokens WHERE id = ?",
                String::class.java,
                verificationTokenId,
            ),
        ).hasSize(64).doesNotContain(verificationToken)

        val confirm = post(
            "/v1/auth/email/verification/confirm",
            EmailVerificationConfirmRequest(verificationToken, "integration-device"),
        )
        assertThat(confirm.statusCode()).isEqualTo(HttpStatus.OK.value())
        val confirmedBody = objectMapper.readTree(confirm.body())
        val firstRefresh = confirmedBody.path("refreshToken").stringValue()
        assertThat(confirmedBody.path("user").path("newUser").booleanValue()).isTrue()
        assertThat(
            jdbc.queryForObject("SELECT status FROM accounts WHERE id = ?", String::class.java, accountId),
        ).isEqualTo("ACTIVE")
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = 'account.activated' AND aggregate_id = ?",
                Int::class.java,
                accountId.toString(),
            ),
        ).isEqualTo(1)

        val confirmAgain = post(
            "/v1/auth/email/verification/confirm",
            EmailVerificationConfirmRequest(verificationToken, "integration-device"),
        )
        assertThat(confirmAgain.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value())

        val concurrentRefreshes = listOf(
            CompletableFuture.supplyAsync { post("/v1/auth/refresh", RefreshTokenRequest(firstRefresh)) },
            CompletableFuture.supplyAsync { post("/v1/auth/refresh", RefreshTokenRequest(firstRefresh)) },
        ).map(CompletableFuture<HttpResponse<String>>::join)
        assertThat(concurrentRefreshes.map { it.statusCode() })
            .containsExactlyInAnyOrder(HttpStatus.OK.value(), HttpStatus.UNAUTHORIZED.value())
        val replacementRefresh = objectMapper.readTree(
            concurrentRefreshes.single { it.statusCode() == HttpStatus.OK.value() }.body(),
        ).path("refreshToken").stringValue()

        val familyRevoked = post("/v1/auth/refresh", RefreshTokenRequest(replacementRefresh))
        assertThat(familyRevoked.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value())

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL",
                    Int::class.java,
                ),
            ).isGreaterThanOrEqualTo(2)
        }
    }

    @Test
    fun `unknown and wrong login expose the same response`() {
        val unique = UUID.randomUUID().toString().take(8)
        val existingEmail = "login-$unique@example.ru"
        val correctPassword = "ValidPassword-$unique"
        registerAndConfirm(existingEmail, correctPassword)
        val unknown = post(
            "/v1/auth/email/login",
            EmailLoginRequest("unknown-${UUID.randomUUID()}@example.ru", "ValidPassword-123", null),
        )
        val wrong = post(
            "/v1/auth/email/login",
            EmailLoginRequest(existingEmail, "WrongPassword-123", null),
        )

        assertThat(unknown.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value())
        assertThat(wrong.statusCode()).isEqualTo(unknown.statusCode())
        assertThat(objectMapper.readTree(wrong.body()).path("code").stringValue())
            .isEqualTo(objectMapper.readTree(unknown.body()).path("code").stringValue())
    }

    @Test
    fun `failed login while locked increments failures and extends lock`() {
        val unique = UUID.randomUUID().toString().take(8)
        val email = "locked-$unique@example.ru"
        registerAndConfirm(email, "ValidPassword-$unique")
        jdbc.update(
            """
            UPDATE email_credentials
            SET failed_login_count = 5, locked_until = clock_timestamp() + interval '30 seconds'
            WHERE email = ?
            """.trimIndent(),
            email,
        )
        val lockBefore = lockedUntil(email)

        val response = post(
            "/v1/auth/email/login",
            EmailLoginRequest(email, "DummyPassword-OnlyForEqualWork-1", null),
        )

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value())
        assertThat(
            jdbc.queryForObject(
                "SELECT failed_login_count FROM email_credentials WHERE email = ?",
                Int::class.java,
                email,
            ),
        ).isEqualTo(6)
        assertThat(lockedUntil(email)).isAfter(lockBefore)
    }

    @Test
    fun `correct password while locked does not extend the lock`() {
        val unique = UUID.randomUUID().toString().take(8)
        val email = "correct-locked-$unique@example.ru"
        val password = "ValidPassword-$unique"
        registerAndConfirm(email, password)
        jdbc.update(
            """
            UPDATE email_credentials
            SET failed_login_count = 5, locked_until = clock_timestamp() + interval '30 seconds'
            WHERE email = ?
            """.trimIndent(),
            email,
        )
        val lockBefore = lockedUntil(email)

        val response = post("/v1/auth/email/login", EmailLoginRequest(email, password, null))

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value())
        assertThat(
            jdbc.queryForObject(
                "SELECT failed_login_count FROM email_credentials WHERE email = ?",
                Int::class.java,
                email,
            ),
        ).isEqualTo(5)
        assertThat(lockedUntil(email)).isEqualTo(lockBefore)
    }

    @Test
    fun `readiness does not depend on a lazily connected broker`() {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/readyz")).GET().build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value())
    }

    private fun registerAndConfirm(email: String, password: String) {
        assertThat(
            post("/v1/auth/email/register", EmailRegistrationRequest(email, password, "ru-RU")).statusCode(),
        ).isEqualTo(HttpStatus.ACCEPTED.value())
        val canonicalEmail = email.lowercase()
        val payload = requireNotNull(
            jdbc.queryForObject(
                """
                SELECT o.payload::text
                FROM outbox_events o
                JOIN email_credentials ec ON ec.account_id::text = o.aggregate_id
                WHERE o.event_type = 'notification.email.requested' AND ec.email = ?
                ORDER BY o.occurred_at DESC LIMIT 1
                """.trimIndent(),
                String::class.java,
                canonicalEmail,
            ),
        )
        val compact = objectMapper.readTree(payload).path("encryptedPayload").stringValue()
        val jwe = JWEObject.parse(compact).apply { decrypt(RSADecrypter(notificationPrivateKey)) }
        val token = objectMapper.readTree(jwe.payload.toString()).path("token").stringValue()
        assertThat(
            post(
                "/v1/auth/email/verification/confirm",
                EmailVerificationConfirmRequest(token, "integration-device"),
            ).statusCode(),
        ).isEqualTo(HttpStatus.OK.value())
    }

    private fun lockedUntil(email: String): Instant = requireNotNull(
        jdbc.queryForObject(
            "SELECT locked_until FROM email_credentials WHERE email = ?",
            { result, _ -> result.getTimestamp("locked_until").toInstant() },
            email,
        ),
    )

    private fun post(path: String, body: Any): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        class NatsContainer(image: String) : GenericContainer<NatsContainer>(image)

        private val notificationPrivateKey = RSAKeyGenerator(2048)
            .keyID("notification-integration")
            .keyUse(KeyUse.ENCRYPTION)
            .algorithm(JWEAlgorithm.RSA_OAEP_256)
            .generate()
        private val notificationPublicFile = Files.createTempFile("cookie-notification-public-", ".jwk").also {
            Files.writeString(it, notificationPrivateKey.toPublicJWK().toJSONString())
            it.toFile().deleteOnExit()
        }

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("identity")
            .withUsername("identity")
            .withPassword("identity")

        @Container
        @JvmStatic
        val nats: NatsContainer = NatsContainer("nats:2.14.4-alpine")
            .withCommand("-js")
            .withExposedPorts(4222)

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("cookie.identity.nats-url") { "nats://${nats.host}:${nats.getMappedPort(4222)}" }
            registry.add("cookie.identity.notification-public-key-path") { notificationPublicFile.toString() }
        }
    }
}
