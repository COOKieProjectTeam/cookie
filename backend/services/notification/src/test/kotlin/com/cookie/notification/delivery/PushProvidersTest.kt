package com.cookie.notification.delivery

import com.cookie.notification.config.FcmProperties
import com.cookie.platform.web.BoundedHttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PushProvidersTest {
    private val now = Instant.parse("2026-09-17T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val mapper = JsonMapper.builder().build()
    private val http = BoundedHttpExchange(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build())
    private val templates = NotificationTemplates(URI.create("https://links.example.test/verify"))
    private val servers = mutableListOf<HttpServer>()
    private val job = DeliveryJob(UUID.randomUUID(), "PUSH", UUID.randomUUID(), UUID.randomUUID(), null, "WATER", "ru",
        null, now.plusSeconds(600), UUID.randomUUID(), 1, UUID.randomUUID())
    @AfterEach fun stop() { servers.forEach { it.stop(0) } }

    @ParameterizedTest
    @ValueSource(strings = ["IOS", "ANDROID"])
    fun `FCM sends each platform token unchanged with OAuth and platform delivery settings`(platform: String) {
        val captured = AtomicReference<String>()
        val auth = AtomicReference<String>()
        val url = server(200, """{"name":"projects/cookie-test/messages/accepted"}""") { body, authorization ->
            captured.set(body); auth.set(authorization)
        }
        val token = "MiXeD:FCM-registration_token-123"
        val result = sender(url).send(job, PushTarget(requireNotNull(job.deviceId), platform, token, now, "en"))
        assertThat(result).isEqualTo(ProviderResult.Accepted("projects/cookie-test/messages/accepted"))
        val message = mapper.readTree(captured.get()).path("message")
        assertThat(auth.get()).isEqualTo("Bearer oauth-token")
        assertThat(message.path("token").stringValue()).isEqualTo(token)
        assertThat(message.path("data").path("deliveryId").stringValue()).isEqualTo(job.id.toString())
        assertThat(message.path("notification").path("body").stringValue()).isEqualTo("Time for some water.")
        if (platform == "ANDROID") {
            assertThat(message.path("android").path("ttl").stringValue()).isEqualTo("600s")
            assertThat(message.path("android").path("priority").stringValue()).isEqualTo("NORMAL")
            assertThat(message.path("android").path("notification").path("tag").stringValue()).isEqualTo(job.occurrenceId.toString())
            assertThat(message.has("apns")).isFalse()
        } else {
            val headers = message.path("apns").path("headers")
            assertThat(headers.path("apns-push-type").stringValue()).isEqualTo("alert")
            assertThat(headers.path("apns-priority").stringValue()).isEqualTo("10")
            assertThat(headers.path("apns-id").stringValue()).isEqualTo(job.id.toString())
            assertThat(headers.path("apns-collapse-id").stringValue()).isEqualTo(job.occurrenceId.toString())
            assertThat(headers.path("apns-expiration").stringValue()).isEqualTo(job.expiresAt.epochSecond.toString())
            assertThat(message.has("android")).isFalse()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["IOS", "ANDROID"])
    fun `only typed FCM unregistered invalidates a device and Apple credentials failure pauses delivery`(platform: String) {
        val target = PushTarget(requireNotNull(job.deviceId), platform, "MiXeD-fcm-token", now)
        fun send(status: Int, body: String): ProviderResult = sender(server(status, body, "3600") { _, _ -> }).send(job, target)
        assertThat(send(400, """{"error":{"status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.BadRequest","errorCode":"UNREGISTERED"}]}}"""))
            .isEqualTo(ProviderResult.Failed("FCM_REJECTED"))
        assertThat(send(404, fcmError("UNREGISTERED"))).isEqualTo(ProviderResult.Failed("FCM_UNREGISTERED", true))
        assertThat(send(401, fcmError("THIRD_PARTY_AUTH_ERROR"))).isEqualTo(ProviderResult.Unavailable("FCM_THIRD_PARTY_AUTH"))
        assertThat(send(401, "{} ")).isEqualTo(ProviderResult.Unavailable("FCM_AUTH"))
        assertThat(send(403, fcmError("SENDER_ID_MISMATCH"))).isEqualTo(ProviderResult.Unavailable("FCM_AUTH"))
    }

    @ParameterizedTest(name = "FCM {0}, Retry-After {1}: wait {2} seconds on both platforms")
    @CsvSource(delimiter = '|', nullValues = ["<missing>"], value = [
        "429 | <missing> | 60",
        "429 | invalid | 60",
        "429 | 1 | 60",
        "429 | 120 | 120",
        "429 | Thu, 17 Sep 2026 12:05:00 GMT | 300",
        "429 | Thu, 17 Sep 2026 11:59:00 GMT | 60",
        "500 | <missing> | 10",
        "500 | invalid | 10",
        "503 | 1 | 10",
        "503 | 120 | 120",
        "503 | 1800 | 1800",
        "503 | Thu, 17 Sep 2026 12:30:00 GMT | 1800",
        "503 | Thu, 17 Sep 2026 11:59:00 GMT | 10",
        "503 | 9223372036854775807 | 86400",
        "429 | Sat, 19 Sep 2026 12:00:00 GMT | 86400",
    ])
    fun `FCM retry after respects provider floors dates and bounded delay`(status: Int, header: String?, seconds: Long) {
        val provider = sender(server(status, "{}", header) { _, _ -> })
        for (platform in listOf("IOS", "ANDROID")) {
            val result = provider.send(job, PushTarget(requireNotNull(job.deviceId), platform, "MiXeD-fcm-token", now))
            assertThat(result).isEqualTo(ProviderResult.Retry(
                if (status == 429) "FCM_RATE_LIMIT" else "FCM_UNAVAILABLE", Duration.ofSeconds(seconds)))
        }
    }

    @Test fun `HTTP date delay rounds up fractional clock seconds and trims header whitespace`() {
        assertThat(retryAfter(" Thu, 17 Sep 2026 12:30:00 GMT ", now.plusMillis(500), 10))
            .isEqualTo(Duration.ofMinutes(30))
        assertThat(retryAfter(" 120 ", now)).isEqualTo(Duration.ofSeconds(120))
    }

    @ParameterizedTest
    @ValueSource(strings = ["IOS", "ANDROID"])
    fun `submission evidence is recorded only at actual provider dispatch`(platform: String) {
        val submissions = AtomicInteger()
        val calls = AtomicInteger()
        val uri = server(200, """{"name":"message"}""") { _, _ ->
            assertThat(submissions.get()).isEqualTo(1)
            calls.incrementAndGet()
        }
        val target = PushTarget(requireNotNull(job.deviceId), platform, "MiXeD-fcm-token", now)
        val enabled = FcmProperties(true, "unused", "cookie-test", uri)
        val disabled = FcmPushSender(FcmProperties(), FcmAccessToken { error("Disabled") }, http, mapper, templates, clock)
        assertThat(disabled.send(job, target) { submissions.incrementAndGet() })
            .isEqualTo(ProviderResult.Unavailable("FCM_DISABLED"))
        val unauthenticated = FcmPushSender(enabled, FcmAccessToken { error("OAuth unavailable") }, http, mapper, templates, clock)
        assertThat(unauthenticated.send(job, target) { submissions.incrementAndGet() })
            .isEqualTo(ProviderResult.Unavailable("FCM_AUTH"))
        assertThat(submissions.get()).isZero()
        val sender = sender(uri)
        val expiredSender = FcmPushSender(enabled, FcmAccessToken { "oauth-token" }, http, mapper, templates,
            Clock.fixed(job.expiresAt, ZoneOffset.UTC))
        assertThat(expiredSender.send(job, target) { submissions.incrementAndGet() })
            .isEqualTo(ProviderResult.Failed("DELIVERY_EXPIRED"))
        assertThat(submissions.get()).isZero()
        assertThat(sender.send(job, target) { error("Ownership fence lost") })
            .isInstanceOf(ProviderResult.Retry::class.java)
        assertThat(calls.get()).isZero()
        assertThat(sender.send(job, target) { submissions.incrementAndGet() })
            .isEqualTo(ProviderResult.Accepted("message"))
        assertThat(calls.get()).isEqualTo(1)
    }

    private fun sender(uri: URI) = FcmPushSender(FcmProperties(true, "unused", "cookie-test", uri),
        FcmAccessToken { "oauth-token" }, http, mapper, templates, clock)

    private fun fcmError(code: String) =
        """{"error":{"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"$code"}]}}"""

    private fun server(code: Int, response: String, retryAfter: String? = null, capture: (String, String) -> Unit): URI {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also(servers::add)
        server.createContext("/") { exchange ->
            assertThat(exchange.requestMethod).isEqualTo("POST")
            assertThat(exchange.requestURI.path).isEqualTo("/v1/projects/cookie-test/messages:send")
            assertThat(exchange.requestHeaders.getFirst("Content-Type")).isEqualTo("application/json")
            capture(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8), exchange.requestHeaders.getFirst("Authorization"))
            retryAfter?.let { exchange.responseHeaders.set("Retry-After", it) }
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return URI.create("http://127.0.0.1:${server.address.port}")
    }
}
