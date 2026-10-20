package com.cookie.notification.delivery

import com.cookie.notification.application.ports.PushEligibility
import com.cookie.notification.config.DeliveryProperties
import com.cookie.notification.config.FcmProperties
import com.cookie.notification.security.BoundedHttpExchange
import com.cookie.notification.security.DecryptionKeyUnavailableException
import com.cookie.notification.security.VerificationPayloadDecryptor
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DeliveryWorkerRetryTest {
    private val clock = RetryClock(Instant.parse("2026-09-17T12:00:00Z"))
    private val repository = RecordingRetryRepository(clock.instant().plus(Duration.ofDays(1)))
    private val mapper = JsonMapper.builder().build()
    private val http = BoundedHttpExchange(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build())
    private val templates = NotificationTemplates(URI.create("https://example.test/verify"))
    private val authenticated = AtomicBoolean(true)
    private val responseStatus = AtomicInteger(503)
    private val responseBody = AtomicReference("""{"name":"test-message"}""")
    private val requests = AtomicInteger()
    private val workers = mutableListOf<DeliveryWorker>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            requests.incrementAndGet()
            val body = responseBody.get().toByteArray()
            exchange.sendResponseHeaders(responseStatus.get(), body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val decryptor = mock(VerificationPayloadDecryptor::class.java)

    @AfterEach fun close() {
        workers.forEach(DeliveryWorker::close)
        server.stop(0)
    }

    @ParameterizedTest(name = "first {0} response after auth recovery: {1}")
    @CsvSource("IOS,429", "IOS,503", "ANDROID,429", "ANDROID,503")
    fun `twelve authentication and channel pause claims preserve first provider retry and its backoff`(platform: String, status: Int) {
        repository.target = target(platform)
        val worker = worker()
        authenticated.set(false)
        repeat(6) {
            worker.process(repository.claim(1).single())
            assertThat(repository.code).isEqualTo("FCM_AUTH")
            worker.process(repository.claim(1).single())
            assertThat(repository.code).isEqualTo("CHANNEL_PAUSED")
            clock.advance(Duration.ofMinutes(1))
        }
        assertThat(repository.claims).isEqualTo(12)
        assertThat(repository.providerFailures).isZero()
        assertThat(requests.get()).isZero()

        authenticated.set(true)
        responseStatus.set(status)
        worker.process(repository.claim(1).single())
        assertThat(repository.status).isEqualTo("PENDING")
        assertThat(repository.providerFailures).isEqualTo(1)
        assertThat(repository.delay).isEqualTo(Duration.ofSeconds(if (status == 429) 60 else 10))

        clock.advance(requireNotNull(repository.delay))
        responseStatus.set(200)
        worker.process(repository.claim(1).single())
        assertThat(repository.status).isEqualTo("ACCEPTED")
        assertThat(repository.providerFailures).isEqualTo(1)
        assertThat(repository.claims).isEqualTo(14)
        assertThat(requests.get()).isEqualTo(2)
    }

    @ParameterizedTest
    @ValueSource(strings = ["FCM_DISABLED", "QUIET_HOURS", "WATER_INTERVAL", "DECRYPTION_KEY_UNAVAILABLE"])
    fun `technical deferrals never consume provider failure budget`(code: String) {
        when (code) {
            "FCM_DISABLED" -> repository.target = target("IOS")
            "QUIET_HOURS" -> repository.target = target(quietUntil = clock.instant().plusSeconds(3600))
            "WATER_INTERVAL" -> repository.target = target(waterUntil = clock.instant().plusSeconds(3600))
            "DECRYPTION_KEY_UNAVAILABLE" -> {
                repository.channel = "EMAIL"
                `when`(decryptor.decrypt("ciphertext", repository.registration, repository.expiresAt))
                    .thenThrow(DecryptionKeyUnavailableException())
            }
        }
        val worker = worker(enabled = code != "FCM_DISABLED")
        repeat(12) {
            worker.process(repository.claim(1).single())
            assertThat(repository.code).isEqualTo(code)
            clock.advance(Duration.ofMinutes(1))
        }
        assertThat(repository.status).isEqualTo("PENDING")
        assertThat(repository.providerFailures).isZero()
        assertThat(requests.get()).isZero()
    }

    @ParameterizedTest
    @ValueSource(strings = ["IOS", "ANDROID"])
    fun `OAuth failures pause both platform queues until credentials recover`(firstPlatform: String) {
        val worker = worker()
        authenticated.set(false)
        repository.target = target(firstPlatform)
        worker.process(repository.claim(1).single())
        assertThat(repository.code).isEqualTo("FCM_AUTH")
        authenticated.set(true)
        repository.target = target(if (firstPlatform == "IOS") "ANDROID" else "IOS")
        worker.process(repository.claim(1).single())
        assertThat(repository.code).isEqualTo("CHANNEL_PAUSED")
        assertThat(repository.providerFailures).isZero()
        assertThat(requests.get()).isZero()
        clock.advance(Duration.ofMinutes(1))
        responseStatus.set(200)
        worker.process(repository.claim(1).single())
        assertThat(repository.status).isEqualTo("ACCEPTED")
        assertThat(requests.get()).isEqualTo(1)
    }

    @Test fun `Apple credential errors pause iOS while Android still sends without waiting`() {
        val worker = worker()
        repository.target = target("IOS")
        responseStatus.set(401)
        responseBody.set("""{"error":{"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"THIRD_PARTY_AUTH_ERROR"}]}}""")
        worker.process(repository.claim(1).single())
        assertThat(repository.code).isEqualTo("FCM_THIRD_PARTY_AUTH")
        assertThat(repository.invalidations).isZero()
        assertThat(repository.providerFailures).isZero()
        // Another iOS claim uses its cooldown without another provider call.
        worker.process(repository.claim(1).single())
        assertThat(repository.code).isEqualTo("CHANNEL_PAUSED")
        assertThat(requests.get()).isEqualTo(1)
        // The clock has not advanced: a healthy Android send must bypass the iOS cooldown.
        repository.target = target("ANDROID")
        responseStatus.set(200)
        responseBody.set("""{"name":"test-message"}""")
        worker.process(repository.claim(1).single())
        assertThat(repository.status).isEqualTo("ACCEPTED")
        assertThat(requests.get()).isEqualTo(2)
        assertThat(repository.invalidations).isZero()
        assertThat(repository.providerFailures).isZero()
    }

    @Test fun `ten actual retryable provider failures still exhaust the budget`() {
        val worker = worker()
        repeat(9) { index ->
            worker.process(repository.claim(1).single())
            assertThat(repository.status).isEqualTo("PENDING")
            assertThat(repository.providerFailures).isEqualTo(index + 1)
            clock.advance(requireNotNull(repository.delay))
        }
        worker.process(repository.claim(1).single())
        assertThat(repository.status).isEqualTo("FAILED")
        assertThat(repository.code).isEqualTo("RETRIES_EXHAUSTED")
        assertThat(repository.providerFailures).isEqualTo(10)
        assertThat(repository.claim(1)).isEmpty()
        assertThat(requests.get()).isEqualTo(10)
    }

    @Test fun `retry that cannot fit before expiry records its failure and terminates`() {
        clock.advance(Duration.ofDays(1).minusSeconds(5))
        worker().process(repository.claim(1).single())
        assertThat(repository.status).isEqualTo("EXPIRED")
        assertThat(repository.code).isEqualTo("RETRY_AFTER_EXPIRY")
        assertThat(repository.providerFailures).isEqualTo(1)
        assertThat(repository.claim(1)).isEmpty()
    }

    private fun worker(enabled: Boolean = true) = DeliveryWorker(repository, decryptor,
        VerificationSender { _, _ -> error("SMTP submission is not expected") },
        FcmPushSender(FcmProperties(enabled, "unused", "cookie-test",
            URI.create("http://127.0.0.1:${server.address.port}")),
            FcmAccessToken { check(authenticated.get()); "test-token" }, http, mapper, templates, clock),
        PushEligibility { _, _, _, _ -> true }, clock, DeliveryProperties(), mock(DeliveryMetrics::class.java))
        .also(workers::add)

    private fun target(platform: String = "ANDROID", quietUntil: Instant? = null, waterUntil: Instant? = null) =
        PushTarget(repository.device, platform, "test-token", clock.instant(),
            quietUntil = quietUntil, nextWaterSubmissionAt = waterUntil)
}

private class RetryClock(private var now: Instant) : Clock() {
    override fun instant() = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    fun advance(duration: Duration) { now = now.plus(duration) }
}

/** Records worker decisions; JDBC integration tests verify durable counters and lease fencing. */
private class RecordingRetryRepository(val expiresAt: Instant) : DeliveryRepository {
    private val id = UUID.randomUUID()
    private val user = UUID.randomUUID()
    private val occurrence = UUID.randomUUID()
    val device: UUID = UUID.randomUUID()
    val registration: UUID = UUID.randomUUID()
    var channel = "PUSH"
    var target = PushTarget(device, "ANDROID", "test-token", expiresAt.minusSeconds(86400))
    var claims = 0
    var providerFailures = 0
    var invalidations = 0
    var status = "PENDING"
    var code: String? = null
    var delay: Duration? = null
    override fun claim(limit: Int): List<DeliveryJob> {
        if (status != "PENDING") return emptyList()
        status = "CLAIMED"
        claims++
        return listOf(DeliveryJob(id, channel, user, device, registration, "FOOD", "ru", "ciphertext",
            expiresAt, occurrence, claims, UUID.randomUUID(), providerFailures))
    }
    override fun beginSmtpSubmission(job: DeliveryJob) = true
    override fun heartbeat(job: DeliveryJob) = true
    override fun currentPushTarget(job: DeliveryJob) = target
    override fun withPushSubmission(job: DeliveryJob, submit: (PushTarget) -> Unit): Boolean { submit(target); return true }
    override fun markPushSubmission(job: DeliveryJob) = Unit
    override fun invalidateDevice(target: PushTarget) { invalidations++ }
    override fun finish(job: DeliveryJob, status: String, code: String, providerId: String?, providerFailure: Boolean) {
        this.status = status
        this.code = code
        if (providerFailure) providerFailures++
    }
    override fun retry(job: DeliveryJob, code: String, delay: Duration, providerFailure: Boolean) {
        this.status = "PENDING"
        this.code = code
        this.delay = delay
        if (providerFailure) providerFailures++
    }
    override fun maintain() = Unit
}
