package com.cookie.identity

import com.cookie.identity.generated.model.EmailLoginRequest
import com.cookie.identity.generated.model.EmailRegistrationRequest
import com.cookie.identity.generated.model.EmailVerificationConfirmRequest
import com.cookie.identity.generated.model.RefreshTokenRequest
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSADecrypter
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.SignedJWT
import io.nats.client.Nats
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
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
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("integration")
@Testcontainers
class IdentityFlowIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun resetSharedRateLimits() {
        jdbc.update("TRUNCATE TABLE rate_limit_buckets")
    }

    @Test
    fun `register confirm idempotent refresh token reuse and encrypted outbox are atomic`() {
        val unique = UUID.randomUUID().toString().take(8)
        val email = "identity-$unique@пример.рф"
        val password = "НадежныйПароль-$unique"
        val registrationProof = registrationProof()
        val registrationAttemptId = UUID.randomUUID()
        val requestId = UUID.randomUUID()

        val register = post(
            "/v1/auth/email/register",
            EmailRegistrationRequest(registrationAttemptId, email, password, registrationProof, "ru-RU"),
            mapOf("X-Request-Id" to requestId.toString()),
        )
        assertThat(register.statusCode()).isEqualTo(HttpStatus.ACCEPTED.value())
        assertThat(register.headers().firstValue("X-Request-Id").orElseThrow()).isEqualTo(requestId.toString())
        val canonicalEmail = "identity-$unique@xn--e1afmkfd.xn--p1ai"
        val persistedAttemptId = requireNotNull(
            jdbc.queryForObject(
                """
                SELECT aggregate_id::uuid FROM outbox_events
                WHERE event_type = 'notification.email.requested' AND correlation_id = ?
                """.trimIndent(),
                UUID::class.java,
                requestId,
            ),
        )
        assertThat(persistedAttemptId).isEqualTo(registrationAttemptId)
        assertThat(
            jdbc.queryForObject(
                """
                SELECT correlation_id FROM outbox_events
                WHERE event_type = 'notification.email.requested' AND aggregate_id = ?
                """.trimIndent(),
                UUID::class.java,
                persistedAttemptId.toString(),
            ),
        ).isEqualTo(requestId)

        val payloadJson = requireNotNull(
            jdbc.queryForObject(
                """
                SELECT payload::text FROM outbox_events
                WHERE event_type = 'notification.email.requested' AND aggregate_id = ?
                ORDER BY occurred_at DESC LIMIT 1
                """.trimIndent(),
                String::class.java,
                persistedAttemptId.toString(),
            ),
        )
        assertThat(payloadJson).doesNotContain(email, password)
        val compactJwe = objectMapper.readTree(payloadJson).path("encryptedPayload").stringValue()
        val jwe = JWEObject.parse(compactJwe).apply { decrypt(RSADecrypter(notificationPrivateKey)) }
        val delivery = objectMapper.readTree(jwe.payload.toString())
        assertThat(delivery.path("registrationAttemptId").stringValue())
            .isEqualTo(registrationAttemptId.toString())
        assertThat(delivery.path("recipientEmail").stringValue()).isEqualTo(canonicalEmail)
        assertThat(delivery.path("locale").stringValue()).isEqualTo("ru-RU")
        val verificationToken = delivery.path("token").stringValue()
        val tokenParts = verificationToken.split('.', limit = 4)
        assertThat(UUID.fromString(tokenParts[1])).isEqualTo(registrationAttemptId)
        val verificationTokenId = UUID.fromString(tokenParts[2])
        assertThat(
            jdbc.queryForObject(
                "SELECT verifier_hash FROM registration_verification_tokens WHERE id = ?",
                String::class.java,
                verificationTokenId,
            ),
        ).hasSize(64).doesNotContain(verificationToken)

        val malformedProof = post(
            "/v1/auth/email/verification/confirm",
            EmailVerificationConfirmRequest(verificationToken, "malformed"),
        )
        assertThat(malformedProof.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value())
        assertThat(objectMapper.readTree(malformedProof.body()).path("code").stringValue())
            .isEqualTo("INVALID_VERIFICATION_TOKEN")

        val confirm = post(
            "/v1/auth/email/verification/confirm",
            EmailVerificationConfirmRequest(verificationToken, registrationProof),
        )
        assertThat(confirm.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value())
        assertThat(confirm.body()).isEmpty()
        val accountId = requireNotNull(
            jdbc.queryForObject(
                "SELECT account_id FROM email_credentials WHERE email = ?",
                UUID::class.java,
                canonicalEmail,
            ),
        )
        assertThat(
            jdbc.queryForObject(
                """
                SELECT completed_at IS NOT NULL
                    AND activated_account_id = ?
                    AND pending_password_hash IS NULL
                FROM registration_attempts WHERE id = ?
                """.trimIndent(),
                Boolean::class.java,
                accountId,
                registrationAttemptId,
            ),
        ).isTrue()
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = 'account.activated' AND aggregate_id = ?",
                Int::class.java,
                accountId.toString(),
            ),
        ).isEqualTo(1)

        val confirmAgain = post(
            "/v1/auth/email/verification/confirm",
            EmailVerificationConfirmRequest(verificationToken, registrationProof),
        )
        assertThat(confirmAgain.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value())

        val completedPayloadConflict = post(
            "/v1/auth/email/register",
            EmailRegistrationRequest(
                registrationAttemptId,
                email,
                "$password-changed",
                registrationProof,
                "ru-RU",
            ),
        )
        assertThat(completedPayloadConflict.statusCode()).isEqualTo(HttpStatus.CONFLICT.value())

        val login = post(
            "/v1/auth/email/login",
            EmailLoginRequest(email, password, "integration-device"),
        )
        assertThat(login.statusCode()).isEqualTo(HttpStatus.OK.value())
        assertTokenResponseIsNotCacheable(login)
        val loginBody = objectMapper.readTree(login.body())
        val firstRefresh = loginBody.path("refreshToken").stringValue()
        assertThat(loginBody.path("user").path("id").stringValue()).isEqualTo(accountId.toString())
        assertAccessTokenVerifies(loginBody.path("accessToken").stringValue(), accountId)

        val retryKey = UUID.randomUUID().toString()
        val concurrentRefreshes = simultaneously(
            listOf(
                {
                    post(
                        "/v1/auth/refresh",
                        RefreshTokenRequest(firstRefresh),
                        mapOf("Idempotency-Key" to retryKey),
                    )
                },
                {
                    post(
                        "/v1/auth/refresh",
                        RefreshTokenRequest(firstRefresh),
                        mapOf("Idempotency-Key" to retryKey),
                    )
                },
            ),
        )
        assertThat(concurrentRefreshes.map { it.statusCode() })
            .containsOnly(HttpStatus.OK.value())
        concurrentRefreshes.forEach(::assertTokenResponseIsNotCacheable)
        val replacementRefreshes = concurrentRefreshes.map {
            objectMapper.readTree(it.body()).path("refreshToken").stringValue()
        }
        assertThat(replacementRefreshes).containsOnly(replacementRefreshes.first())

        val rotateAgain = post(
            "/v1/auth/refresh",
            RefreshTokenRequest(replacementRefreshes.first()),
            mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
        )
        assertThat(rotateAgain.statusCode()).isEqualTo(HttpStatus.OK.value())
        val currentRefresh = objectMapper.readTree(rotateAgain.body()).path("refreshToken").stringValue()

        val staleExactRetry = post(
            "/v1/auth/refresh",
            RefreshTokenRequest(firstRefresh),
            mapOf("Idempotency-Key" to retryKey),
        )
        assertThat(staleExactRetry.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value())

        val rotateAfterStaleRetry = post(
            "/v1/auth/refresh",
            RefreshTokenRequest(currentRefresh),
            mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
        )
        assertThat(rotateAfterStaleRetry.statusCode()).isEqualTo(HttpStatus.OK.value())
        val latestRefresh = objectMapper.readTree(rotateAfterStaleRetry.body()).path("refreshToken").stringValue()

        val reusedCredentialWithAnotherKey = post(
            "/v1/auth/refresh",
            RefreshTokenRequest(firstRefresh),
            mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
        )
        assertThat(reusedCredentialWithAnotherKey.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value())

        val familyRevoked = post(
            "/v1/auth/refresh",
            RefreshTokenRequest(latestRefresh),
            mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
        )
        assertThat(familyRevoked.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value())

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM outbox_events
                    WHERE (
                        (aggregate_id = ? AND event_type = 'notification.email.requested')
                        OR (aggregate_id = ? AND event_type = 'account.activated')
                    )
                      AND published_at IS NOT NULL
                    """.trimIndent(),
                    Int::class.java,
                    persistedAttemptId.toString(),
                    accountId.toString(),
                ),
            ).isEqualTo(2)
        }
        assertPublishedEnvelope(persistedAttemptId, "notification.email.requested")
        assertPublishedEnvelope(accountId, "account.activated")
    }

    @Test
    fun `simultaneously started duplicate registration and confirmation commit each transition once`() {
        val unique = UUID.randomUUID().toString().take(8)
        val email = "concurrent-$unique@example.ru"
        val password = "ValidPassword-$unique"
        val registrationProof = registrationProof()
        val registrationAttemptId = UUID.randomUUID()
        val registration = {
            post(
                "/v1/auth/email/register",
                EmailRegistrationRequest(registrationAttemptId, email, password, registrationProof, "ru-RU"),
            )
        }

        assertThat(simultaneously(listOf(registration, registration)).map { it.statusCode() })
            .containsOnly(HttpStatus.ACCEPTED.value())
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM email_credentials WHERE email = ?",
                Int::class.java,
                email,
            ),
        ).isZero()
        val persistedAttemptId = requireNotNull(
            jdbc.queryForObject(
                "SELECT id FROM registration_attempts WHERE email = ?",
                UUID::class.java,
                email,
            ),
        )
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM registration_attempts WHERE email = ?",
                Int::class.java,
                email,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'notification.email.requested'",
                Int::class.java,
                persistedAttemptId.toString(),
            ),
        ).isEqualTo(1)

        val token = latestVerificationToken(email)
        val confirmation = {
            post(
                "/v1/auth/email/verification/confirm",
                EmailVerificationConfirmRequest(token, registrationProof),
            )
        }
        assertThat(simultaneously(listOf(confirmation, confirmation)).map { it.statusCode() })
            .containsOnly(HttpStatus.NO_CONTENT.value())
        val accountId = requireNotNull(
            jdbc.queryForObject(
                "SELECT account_id FROM email_credentials WHERE email = ?",
                UUID::class.java,
                email,
            ),
        )
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'account.activated'",
                Int::class.java,
                accountId.toString(),
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `persisted exact registration retries bypass email limit and proof reuse conflicts globally`() {
        val unique = UUID.randomUUID().toString().take(8)
        val email = "retry-$unique@example.ru"
        val password = "ValidPassword-$unique"
        val proof = registrationProof()
        val attemptId = UUID.randomUUID()
        val request = EmailRegistrationRequest(attemptId, email, password, proof, "ru-RU")

        repeat(6) {
            assertThat(post("/v1/auth/email/register", request).statusCode())
                .isEqualTo(HttpStatus.ACCEPTED.value())
        }
        assertThat(
            jdbc.queryForObject(
                "SELECT attempt_count FROM rate_limit_buckets WHERE scope_key LIKE 'register:email:%'",
                Int::class.java,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "SELECT attempt_count FROM rate_limit_buckets WHERE scope_key LIKE 'register:ip:%'",
                Int::class.java,
            ),
        ).isEqualTo(6)

        val conflict = post(
            "/v1/auth/email/register",
            EmailRegistrationRequest(attemptId, email, "$password-changed", proof, "ru-RU"),
        )
        assertThat(conflict.statusCode()).isEqualTo(HttpStatus.CONFLICT.value())
        assertThat(objectMapper.readTree(conflict.body()).path("code").stringValue())
            .isEqualTo("REGISTRATION_ATTEMPT_CONFLICT")
        val proofReuseAcrossEmail = post(
            "/v1/auth/email/register",
            EmailRegistrationRequest(
                UUID.randomUUID(),
                "other-$unique@example.ru",
                password,
                proof,
                "ru-RU",
            ),
        )
        assertThat(proofReuseAcrossEmail.statusCode()).isEqualTo(HttpStatus.CONFLICT.value())
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM registration_attempts WHERE id = ?",
                Int::class.java,
                attemptId,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'notification.email.requested'",
                Int::class.java,
                attemptId.toString(),
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `simultaneously started competing confirmations produce one winner and one scrubbed tombstone`() {
        val unique = UUID.randomUUID().toString().take(8)
        val email = "winner-$unique@example.ru"
        val firstPassword = "FirstPassword-$unique"
        val secondPassword = "SecondPassword-$unique"
        val firstProof = registrationProof()
        val secondProof = registrationProof()
        val firstAttemptId = UUID.randomUUID()
        val secondAttemptId = UUID.randomUUID()

        assertThat(
            post(
                "/v1/auth/email/register",
                EmailRegistrationRequest(firstAttemptId, email, firstPassword, firstProof, null),
            ).statusCode(),
        ).isEqualTo(HttpStatus.ACCEPTED.value())
        assertThat(
            post(
                "/v1/auth/email/register",
                EmailRegistrationRequest(secondAttemptId, email, secondPassword, secondProof, null),
            ).statusCode(),
        ).isEqualTo(HttpStatus.ACCEPTED.value())
        val firstToken = latestVerificationToken(firstAttemptId)
        val secondToken = latestVerificationToken(secondAttemptId)

        val results = simultaneously(
            listOf(
                {
                    post(
                        "/v1/auth/email/verification/confirm",
                        EmailVerificationConfirmRequest(firstToken, firstProof),
                    )
                },
                {
                    post(
                        "/v1/auth/email/verification/confirm",
                        EmailVerificationConfirmRequest(secondToken, secondProof),
                    )
                },
            ),
        )

        assertThat(results.map { it.statusCode() })
            .containsExactlyInAnyOrder(HttpStatus.NO_CONTENT.value(), HttpStatus.BAD_REQUEST.value())
        val firstWon = results.first().statusCode() == HttpStatus.NO_CONTENT.value()
        val winningAttemptId = if (firstWon) firstAttemptId else secondAttemptId
        val losingAttemptId = if (firstWon) secondAttemptId else firstAttemptId
        val winningPassword = if (firstWon) firstPassword else secondPassword
        val losingPassword = if (firstWon) secondPassword else firstPassword
        val losingProof = if (firstWon) secondProof else firstProof
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM registration_attempts WHERE id = ? AND completed_at IS NOT NULL",
                Int::class.java,
                winningAttemptId,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM registration_attempts WHERE id = ?",
                Int::class.java,
                losingAttemptId,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                """
                SELECT abandoned_at IS NOT NULL
                    AND pending_password_hash IS NULL
                    AND locale IS NULL
                FROM registration_attempts WHERE id = ?
                """.trimIndent(),
                Boolean::class.java,
                losingAttemptId,
            ),
        ).isTrue()
        assertThat(
            post(
                "/v1/auth/email/register",
                EmailRegistrationRequest(losingAttemptId, email, losingPassword, losingProof, null),
            ).statusCode(),
        ).isEqualTo(HttpStatus.ACCEPTED.value())
        assertThat(
            post(
                "/v1/auth/email/register",
                EmailRegistrationRequest(losingAttemptId, email, "$losingPassword-changed", losingProof, null),
            ).statusCode(),
        ).isEqualTo(HttpStatus.CONFLICT.value())
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM email_credentials WHERE email = ?",
                Int::class.java,
                email,
            ),
        ).isEqualTo(1)
        assertThat(post("/v1/auth/email/login", EmailLoginRequest(email, losingPassword, null)).statusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED.value())
        assertThat(post("/v1/auth/email/login", EmailLoginRequest(email, winningPassword, null)).statusCode())
            .isEqualTo(HttpStatus.OK.value())
    }

    @Test
    fun `registration lock contention fails within the configured database deadline`() {
        val email = "lock-${UUID.randomUUID().toString().take(8)}@example.ru"
        val registrationAttemptId = UUID.randomUUID()
        lateinit var response: HttpResponse<String>
        lateinit var elapsed: Duration

        dataSource.connection.use { blocker ->
            blocker.autoCommit = false
            blocker.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
                statement.setString(1, "identity:registration:$email")
                statement.execute()
            }
            val startedAt = System.nanoTime()
            response = post(
                "/v1/auth/email/register",
                EmailRegistrationRequest(
                    registrationAttemptId,
                    email,
                    "Valid password for lock test",
                    registrationProof(),
                    null,
                ),
            )
            elapsed = Duration.ofNanos(System.nanoTime() - startedAt)
            blocker.rollback()
        }

        assertThat(response.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value())
        assertThat(response.headers().firstValue("Retry-After").orElseThrow()).isEqualTo("1")
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5))
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM registration_attempts WHERE email = ?",
                Int::class.java,
                email,
            ),
        ).isZero()
    }

    @Test
    fun `email token cannot activate an attacker password without its client proof`() {
        val unique = UUID.randomUUID().toString().take(8)
        val email = "takeover-$unique@example.ru"
        val attackerPassword = "AttackerPassword-$unique"
        val ownerPassword = "OwnerPassword-Strong-$unique"
        val attackerProof = registrationProof()
        val ownerProof = registrationProof()
        val attackerAttemptId = UUID.randomUUID()
        val ownerAttemptId = UUID.randomUUID()

        assertThat(
            post(
                "/v1/auth/email/register",
                EmailRegistrationRequest(attackerAttemptId, email, attackerPassword, attackerProof, null),
            ).statusCode(),
        ).isEqualTo(HttpStatus.ACCEPTED.value())
        val attackerChallenge = latestVerificationToken(attackerAttemptId)

        assertThat(
            post(
                "/v1/auth/email/register",
                EmailRegistrationRequest(ownerAttemptId, email, ownerPassword, ownerProof, null),
            ).statusCode(),
        ).isEqualTo(HttpStatus.ACCEPTED.value())
        val ownerChallenge = latestVerificationToken(ownerAttemptId)

        assertThat(ownerChallenge).isNotEqualTo(attackerChallenge)
        assertThat(
            post(
                "/v1/auth/email/verification/confirm",
                EmailVerificationConfirmRequest(attackerChallenge, ownerProof),
            ).statusCode(),
        ).isEqualTo(HttpStatus.BAD_REQUEST.value())
        assertThat(
            post(
                "/v1/auth/email/verification/confirm",
                EmailVerificationConfirmRequest(ownerChallenge, ownerProof),
            ).statusCode(),
        ).isEqualTo(HttpStatus.NO_CONTENT.value())
        assertThat(
            post(
                "/v1/auth/email/verification/confirm",
                EmailVerificationConfirmRequest(attackerChallenge, attackerProof),
            ).statusCode(),
        ).isEqualTo(HttpStatus.BAD_REQUEST.value())

        assertThat(
            post("/v1/auth/email/login", EmailLoginRequest(email, attackerPassword, null)).statusCode(),
        ).isEqualTo(HttpStatus.UNAUTHORIZED.value())
        assertThat(
            post("/v1/auth/email/login", EmailLoginRequest(email, ownerPassword, null)).statusCode(),
        ).isEqualTo(HttpStatus.OK.value())
    }

    @Test
    fun `simultaneously started refreshes with different keys leave the whole family revoked`() {
        val unique = UUID.randomUUID().toString().take(8)
        val email = "reuse-$unique@example.ru"
        val password = "ValidPassword-$unique"
        registerAndConfirm(email, password)
        val login = post("/v1/auth/email/login", EmailLoginRequest(email, password, "reuse-device"))
        val firstRefresh = objectMapper.readTree(login.body()).path("refreshToken").stringValue()
        val firstCredentialId = UUID.fromString(firstRefresh.split('.', limit = 3)[1])
        val familyId = requireNotNull(
            jdbc.queryForObject(
                "SELECT family_id FROM refresh_credentials WHERE id = ?",
                UUID::class.java,
                firstCredentialId,
            ),
        )

        val outcomes = simultaneously(
            listOf(
                {
                    post(
                        "/v1/auth/refresh",
                        RefreshTokenRequest(firstRefresh),
                        mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
                    )
                },
                {
                    post(
                        "/v1/auth/refresh",
                        RefreshTokenRequest(firstRefresh),
                        mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
                    )
                },
            ),
        )
        assertThat(outcomes.map { it.statusCode() })
            .containsExactlyInAnyOrder(HttpStatus.OK.value(), HttpStatus.UNAUTHORIZED.value())
        val issuedSuccessor = objectMapper.readTree(outcomes.single { it.statusCode() == HttpStatus.OK.value() }.body())
            .path("refreshToken")
            .stringValue()
        assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*) FROM refresh_families
                WHERE id = ?
                  AND status = 'REVOKED'
                  AND revoke_reason = 'TOKEN_REUSE_DETECTED'
                  AND reuse_detected_at = revoked_at
                """.trimIndent(),
                Int::class.java,
                familyId,
            ),
        ).isEqualTo(1)
        assertThat(
            post(
                "/v1/auth/refresh",
                RefreshTokenRequest(issuedSuccessor),
                mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
            ).statusCode(),
        ).isEqualTo(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    fun `simultaneously started logout and refresh leave the family logged out`() {
        val unique = UUID.randomUUID().toString().take(8)
        val email = "logout-race-$unique@example.ru"
        val password = "ValidPassword-$unique"
        registerAndConfirm(email, password)
        val login = post("/v1/auth/email/login", EmailLoginRequest(email, password, "logout-race-device"))
        val currentRefresh = objectMapper.readTree(login.body()).path("refreshToken").stringValue()
        val currentCredentialId = UUID.fromString(currentRefresh.split('.', limit = 3)[1])
        val familyId = requireNotNull(
            jdbc.queryForObject(
                "SELECT family_id FROM refresh_credentials WHERE id = ?",
                UUID::class.java,
                currentCredentialId,
            ),
        )

        val outcomes = simultaneously(
            listOf(
                { post("/v1/auth/logout", RefreshTokenRequest(currentRefresh)) },
                {
                    post(
                        "/v1/auth/refresh",
                        RefreshTokenRequest(currentRefresh),
                        mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
                    )
                },
            ),
        )
        assertThat(outcomes.first().statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value())
        assertThat(outcomes.last().statusCode())
            .isIn(HttpStatus.OK.value(), HttpStatus.UNAUTHORIZED.value())
        assertThat(
            jdbc.queryForObject(
                "SELECT status || ':' || revoke_reason FROM refresh_families WHERE id = ?",
                String::class.java,
                familyId,
            ),
        ).isEqualTo("REVOKED:LOGOUT")

        val possiblyIssuedSuccessor = outcomes.last().takeIf { it.statusCode() == HttpStatus.OK.value() }
            ?.let { objectMapper.readTree(it.body()).path("refreshToken").stringValue() }
        listOfNotNull(currentRefresh, possiblyIssuedSuccessor).forEach { token ->
            assertThat(
                post(
                    "/v1/auth/refresh",
                    RefreshTokenRequest(token),
                    mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
                ).statusCode(),
            ).isEqualTo(HttpStatus.UNAUTHORIZED.value())
        }
    }

    @Test
    fun `logout before refresh leaves the presented family logged out`() {
        val unique = UUID.randomUUID().toString().take(8)
        val email = "logout-first-$unique@example.ru"
        val password = "ValidPassword-$unique"
        registerAndConfirm(email, password)
        val login = post("/v1/auth/email/login", EmailLoginRequest(email, password, "logout-first-device"))
        val refreshToken = objectMapper.readTree(login.body()).path("refreshToken").stringValue()
        val credentialId = UUID.fromString(refreshToken.split('.', limit = 3)[1])
        val familyId = requireNotNull(
            jdbc.queryForObject(
                "SELECT family_id FROM refresh_credentials WHERE id = ?",
                UUID::class.java,
                credentialId,
            ),
        )

        assertThat(post("/v1/auth/logout", RefreshTokenRequest(refreshToken)).statusCode())
            .isEqualTo(HttpStatus.NO_CONTENT.value())
        assertThat(
            post(
                "/v1/auth/refresh",
                RefreshTokenRequest(refreshToken),
                mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
            ).statusCode(),
        ).isEqualTo(HttpStatus.UNAUTHORIZED.value())
        assertThat(
            jdbc.queryForObject(
                "SELECT status || ':' || revoke_reason FROM refresh_families WHERE id = ?",
                String::class.java,
                familyId,
            ),
        ).isEqualTo("REVOKED:LOGOUT")
    }

    @Test
    fun `refresh before logout lets redeemed predecessor log out the rotated family`() {
        val unique = UUID.randomUUID().toString().take(8)
        val email = "refresh-first-$unique@example.ru"
        val password = "ValidPassword-$unique"
        registerAndConfirm(email, password)
        val login = post("/v1/auth/email/login", EmailLoginRequest(email, password, "refresh-first-device"))
        val predecessor = objectMapper.readTree(login.body()).path("refreshToken").stringValue()
        val predecessorId = UUID.fromString(predecessor.split('.', limit = 3)[1])
        val familyId = requireNotNull(
            jdbc.queryForObject(
                "SELECT family_id FROM refresh_credentials WHERE id = ?",
                UUID::class.java,
                predecessorId,
            ),
        )
        val refresh = post(
            "/v1/auth/refresh",
            RefreshTokenRequest(predecessor),
            mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
        )
        assertThat(refresh.statusCode()).isEqualTo(HttpStatus.OK.value())
        val successor = objectMapper.readTree(refresh.body()).path("refreshToken").stringValue()

        assertThat(post("/v1/auth/logout", RefreshTokenRequest(predecessor)).statusCode())
            .isEqualTo(HttpStatus.NO_CONTENT.value())
        assertThat(
            post(
                "/v1/auth/refresh",
                RefreshTokenRequest(successor),
                mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
            ).statusCode(),
        ).isEqualTo(HttpStatus.UNAUTHORIZED.value())
        assertThat(
            jdbc.queryForObject(
                "SELECT status || ':' || revoke_reason FROM refresh_families WHERE id = ?",
                String::class.java,
                familyId,
            ),
        ).isEqualTo("REVOKED:LOGOUT")
    }

    @Test
    fun `resend preserves prior link and logout is idempotent`() {
        val unique = UUID.randomUUID().toString().take(8)
        val email = "lifecycle-$unique@example.ru"
        val password = "ValidPassword-$unique"
        val registrationProof = registrationProof()
        val registrationAttemptId = UUID.randomUUID()

        assertThat(
            post(
                "/v1/auth/email/register",
                EmailRegistrationRequest(registrationAttemptId, email, password, registrationProof, "ru-RU"),
            ).statusCode(),
        ).isEqualTo(HttpStatus.ACCEPTED.value())
        val originalToken = latestVerificationToken(email)
        val originalTokenId = UUID.fromString(originalToken.split('.', limit = 4)[2])
        jdbc.update(
            "UPDATE registration_attempts SET created_at = created_at - interval '2 minutes' WHERE id = ?",
            registrationAttemptId,
        )
        jdbc.update(
            "UPDATE registration_verification_tokens SET issued_at = issued_at - interval '2 minutes' WHERE id = ?",
            originalTokenId,
        )

        val resend = post(
            "/v1/auth/email/verification/resend",
            mapOf(
                "registrationAttemptId" to registrationAttemptId.toString(),
                "email" to email,
                "registrationProof" to registrationProof,
            ),
        )

        assertThat(resend.statusCode()).isEqualTo(HttpStatus.ACCEPTED.value())
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM registration_attempts WHERE email = ?",
                Int::class.java,
                email,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM registration_verification_tokens WHERE attempt_id = ?",
                Int::class.java,
                registrationAttemptId,
            ),
        ).isEqualTo(2)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM registration_attempts WHERE id = ? AND locale = 'ru-RU'",
                Int::class.java,
                registrationAttemptId,
            ),
        ).isEqualTo(1)
        assertThat(
            post(
                "/v1/auth/email/verification/confirm",
                EmailVerificationConfirmRequest(originalToken, registrationProof),
            ).statusCode(),
        ).isEqualTo(HttpStatus.NO_CONTENT.value())

        val login = post(
            "/v1/auth/email/login",
            EmailLoginRequest(email, password, "lifecycle-device"),
        )
        assertThat(login.statusCode()).isEqualTo(HttpStatus.OK.value())
        val refreshToken = objectMapper.readTree(login.body()).path("refreshToken").stringValue()

        repeat(2) {
            assertThat(post("/v1/auth/logout", RefreshTokenRequest(refreshToken)).statusCode())
                .isEqualTo(HttpStatus.NO_CONTENT.value())
        }
        assertThat(
            post(
                "/v1/auth/refresh",
                RefreshTokenRequest(refreshToken),
                mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
            ).statusCode(),
        ).isEqualTo(HttpStatus.UNAUTHORIZED.value())
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
    fun `failed login while locked does not mutate or extend the lock`() {
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
        ).isEqualTo(5)
        assertThat(lockedUntil(email)).isEqualTo(lockBefore)
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
    fun `health and jwks endpoints expose only operational public data`() {
        val health = get("/healthz")
        val readiness = get("/readyz")
        val jwks = get("/v1/auth/jwks")

        assertThat(health.statusCode()).isEqualTo(HttpStatus.OK.value())
        assertThat(readiness.statusCode()).isEqualTo(HttpStatus.OK.value())
        assertThat(jwks.statusCode()).isEqualTo(HttpStatus.OK.value())
        assertThat(jwks.headers().firstValue("Cache-Control").orElseThrow()).contains("max-age=300")
        val keys = objectMapper.readTree(jwks.body()).path("keys")
        assertThat(keys.isArray).isTrue()
        assertThat(keys).isNotEmpty()
        assertThat(jwks.body()).doesNotContain("\"d\"")
    }

    @Test
    fun `readiness remains healthy while nats is unavailable`() {
        nats.dockerClient.pauseContainerCmd(nats.containerId).exec()
        try {
            assertThat(get("/readyz").statusCode()).isEqualTo(HttpStatus.OK.value())
        } finally {
            nats.dockerClient.unpauseContainerCmd(nats.containerId).exec()
        }
    }

    @Test
    fun `readiness fails within a bounded time while postgres is unavailable`() {
        postgres.dockerClient.pauseContainerCmd(postgres.containerId).exec()
        val startedAt = System.nanoTime()
        val (response, elapsed) = try {
            val readiness = get("/readyz")
            readiness to Duration.ofNanos(System.nanoTime() - startedAt)
        } finally {
            postgres.dockerClient.unpauseContainerCmd(postgres.containerId).exec()
        }

        assertThat(response.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value())
        assertThat(objectMapper.readTree(response.body()).path("status").stringValue()).isEqualTo("not_ready")
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5))
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(get("/readyz").statusCode()).isEqualTo(HttpStatus.OK.value())
        }
    }

    @Test
    fun `transport rejects invalid device ids and oversized bodies`() {
        val invalidDevice = post(
            "/v1/auth/email/login",
            EmailLoginRequest("nobody@example.ru", "ValidPassword-123", " "),
        )
        val oversized = postRaw(
            "/v1/auth/email/login",
            "{\"padding\":\"${"x".repeat(17 * 1_024)}\"}",
        )

        assertThat(invalidDevice.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value())
        assertThat(objectMapper.readTree(invalidDevice.body()).path("code").stringValue())
            .isEqualTo("INVALID_DEVICE_ID")
        assertThat(oversized.statusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value())
        val oversizedError = objectMapper.readTree(oversized.body())
        assertThat(oversizedError.path("code").stringValue()).isEqualTo("PAYLOAD_TOO_LARGE")
        assertThat(oversizedError.path("requestId").stringValue())
            .isEqualTo(oversized.headers().firstValue("X-Request-Id").orElseThrow())
    }

    @Test
    fun `refresh requires a valid idempotency key header`() {
        val missing = post("/v1/auth/refresh", RefreshTokenRequest("x".repeat(32)))
        val malformed = post(
            "/v1/auth/refresh",
            RefreshTokenRequest("x".repeat(32)),
            mapOf("Idempotency-Key" to "not-a-uuid"),
        )

        assertThat(missing.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value())
        assertThat(malformed.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value())
    }

    private fun registerAndConfirm(email: String, password: String) {
        val registrationProof = registrationProof()
        val registrationAttemptId = UUID.randomUUID()
        assertThat(
            post(
                "/v1/auth/email/register",
                EmailRegistrationRequest(registrationAttemptId, email, password, registrationProof, "ru-RU"),
            ).statusCode(),
        ).isEqualTo(HttpStatus.ACCEPTED.value())
        val canonicalEmail = email.lowercase()
        val token = latestVerificationToken(canonicalEmail)
        assertThat(
            post(
                "/v1/auth/email/verification/confirm",
                EmailVerificationConfirmRequest(token, registrationProof),
            ).statusCode(),
        ).isEqualTo(HttpStatus.NO_CONTENT.value())
    }

    private fun registrationProof(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(registrationProofRandom::nextBytes))

    private fun latestVerificationToken(canonicalEmail: String): String {
        val payload = requireNotNull(
            jdbc.queryForObject(
                """
                SELECT o.payload::text
                FROM outbox_events o
                JOIN registration_attempts i ON i.id::text = o.aggregate_id
                WHERE o.event_type = 'notification.email.requested' AND i.email = ?
                ORDER BY o.occurred_at DESC LIMIT 1
                """.trimIndent(),
                String::class.java,
                canonicalEmail,
            ),
        )
        val compact = objectMapper.readTree(payload).path("encryptedPayload").stringValue()
        val jwe = JWEObject.parse(compact).apply { decrypt(RSADecrypter(notificationPrivateKey)) }
        return objectMapper.readTree(jwe.payload.toString()).path("token").stringValue()
    }

    private fun latestVerificationToken(registrationAttemptId: UUID): String {
        val payload = requireNotNull(
            jdbc.queryForObject(
                """
                SELECT payload::text
                FROM outbox_events
                WHERE event_type = 'notification.email.requested' AND aggregate_id = ?
                ORDER BY occurred_at DESC LIMIT 1
                """.trimIndent(),
                String::class.java,
                registrationAttemptId.toString(),
            ),
        )
        val compact = objectMapper.readTree(payload).path("encryptedPayload").stringValue()
        val jwe = JWEObject.parse(compact).apply { decrypt(RSADecrypter(notificationPrivateKey)) }
        return objectMapper.readTree(jwe.payload.toString()).path("token").stringValue()
    }

    private fun assertPublishedEnvelope(accountId: UUID, eventType: String) {
        val eventId = requireNotNull(
            jdbc.queryForObject(
                """
                SELECT event_id FROM outbox_events
                WHERE aggregate_id = ? AND event_type = ? AND published_at IS NOT NULL
                """.trimIndent(),
                UUID::class.java,
                accountId.toString(),
                eventType,
            ),
        )
        Nats.connect("nats://${nats.host}:${nats.getMappedPort(4222)}").use { connection ->
            val stream = connection.getStreamContext("COOKIE_EVENTS")
            val state = stream.streamInfo.streamState
            val message = (state.firstSequence..state.lastSequence).firstNotNullOfOrNull { sequence ->
                runCatching { stream.getMessage(sequence) }.getOrNull()?.takeIf { stored ->
                    objectMapper.readTree(stored.data).path("event_id").stringValue() == eventId.toString()
                }
            }
            assertThat(message).isNotNull
            requireNotNull(message)
            assertThat(message.subject).isEqualTo("cookie.events.$eventType.v1")
            assertThat(requireNotNull(message.headers).getFirst("Nats-Msg-Id")).isEqualTo(eventId.toString())
            val envelope = objectMapper.readTree(message.data)
            assertThat(envelope.path("event_type").stringValue()).isEqualTo(eventType)
            assertThat(envelope.path("aggregate_id").stringValue()).isEqualTo(accountId.toString())
        }
    }

    private fun assertAccessTokenVerifies(rawAccessToken: String, accountId: UUID) {
        val token = SignedJWT.parse(rawAccessToken)
        val jwks = JWKSet.parse(get("/v1/auth/jwks").body())
        val publicKey = requireNotNull(jwks.getKeyByKeyId(token.header.keyID)).toECKey()

        assertThat(token.verify(ECDSAVerifier(publicKey))).isTrue()
        assertThat(token.jwtClaimsSet.subject).isEqualTo(accountId.toString())
        assertThat(token.jwtClaimsSet.issuer).isEqualTo("https://api.cookie.app")
        assertThat(token.jwtClaimsSet.audience).containsExactly("cookie-api")
    }

    private fun assertTokenResponseIsNotCacheable(response: HttpResponse<String>) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store")
        assertThat(response.headers().firstValue("Pragma").orElseThrow()).isEqualTo("no-cache")
    }

    private fun lockedUntil(email: String): Instant = requireNotNull(
        jdbc.queryForObject(
            "SELECT locked_until FROM email_credentials WHERE email = ?",
            { result, _ -> result.getTimestamp("locked_until").toInstant() },
            email,
        ),
    )

    private fun post(path: String, body: Any, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val requestBuilder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
        headers.forEach(requestBuilder::header)
        val request = requestBuilder
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun postRaw(path: String, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun <T> simultaneously(actions: List<() -> T>): List<T> {
        require(actions.size > 1) { "Concurrent test requires at least two actions" }
        val executor = Executors.newFixedThreadPool(actions.size)
        val barrier = CyclicBarrier(actions.size + 1)
        val futures = actions.map { action ->
            executor.submit<T> {
                barrier.await(10, TimeUnit.SECONDS)
                action()
            }
        }
        barrier.await(10, TimeUnit.SECONDS)
        return try {
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        class NatsContainer(image: String) : GenericContainer<NatsContainer>(image)

        private val notificationPrivateKey = RSAKeyGenerator(2048)
            .keyID("notification-integration")
            .keyUse(KeyUse.ENCRYPTION)
            .algorithm(JWEAlgorithm.RSA_OAEP_256)
            .generate()
        private val registrationProofRandom = SecureRandom()
        private val notificationPublicFile = Files.createTempFile("cookie-notification-public-", ".jwk").also {
            Files.writeString(it, notificationPrivateKey.toPublicJWK().toJSONString())
            it.toFile().deleteOnExit()
        }

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("identity")
            .withUsername("identity")
            .withPassword("identity")

        @Container
        @JvmStatic
        val nats: NatsContainer = NatsContainer("nats:2.14.6-alpine")
            .withCommand("-js")
            .withExposedPorts(4222)

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("cookie.identity.database.transaction-timeout") { "PT2S" }
            registry.add("cookie.identity.database.lock-timeout") { "PT0.25S" }
            registry.add("cookie.identity.nats-url") { "nats://${nats.host}:${nats.getMappedPort(4222)}" }
            registry.add("cookie.identity.notification-public-key-path") { notificationPublicFile.toString() }
        }
    }
}
