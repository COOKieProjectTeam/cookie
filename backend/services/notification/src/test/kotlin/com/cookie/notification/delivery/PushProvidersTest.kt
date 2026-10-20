package com.cookie.notification.delivery

import com.cookie.notification.config.ApnsProperties
import com.cookie.notification.config.FcmProperties
import com.cookie.notification.security.BoundedHttpExchange
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PushProvidersTest {
    @TempDir lateinit var directory: Path
    private val now = Instant.parse("2026-09-17T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val mapper = JsonMapper.builder().build()
    private val http = BoundedHttpExchange(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build())
    private val templates = NotificationTemplates(URI.create("https://links.example.test/verify"))
    private val servers = mutableListOf<HttpServer>()
    private val job = DeliveryJob(UUID.randomUUID(), "PUSH", UUID.randomUUID(), UUID.randomUUID(), null, "WATER", "ru",
        null, now.plusSeconds(600), UUID.randomUUID(), 1, UUID.randomUUID())
    @AfterEach fun stop() { servers.forEach { it.stop(0) } }

    @Test fun `FCM submits bounded TTL OAuth token and notification tag then respects retry after`() {
        val captured = AtomicReference<String>()
        val auth = AtomicReference<String>()
        val url = server(429, "{}", "120") { body, authorization -> captured.set(body); auth.set(authorization) }
        val provider = FcmPushSender(FcmProperties(true, "unused", "cookie-test", url), FcmAccessToken { "oauth-token" }, http, mapper, templates, clock)
        val result = provider.send(job, PushTarget(requireNotNull(job.deviceId), "ANDROID", "device-token", now, "en"))
        assertThat(result).isEqualTo(ProviderResult.Retry("FCM_RATE_LIMIT", Duration.ofSeconds(120)))
        val message = mapper.readTree(captured.get()).path("message")
        assertThat(auth.get()).isEqualTo("Bearer oauth-token")
        assertThat(message.path("android").path("ttl").stringValue()).isEqualTo("600s")
        assertThat(message.path("android").path("notification").path("tag").stringValue()).isEqualTo(job.occurrenceId.toString())
        assertThat(message.path("notification").path("body").stringValue()).isEqualTo("Time for some water.")
    }

    @Test fun `FCM invalid argument does not disable device but unregistered does`() {
        val target = PushTarget(requireNotNull(job.deviceId), "ANDROID", "device-token", now)
        val invalid = server(400, """{"error":{"status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.BadRequest","fieldViolations":[{"field":"message.token","description":"synthetic invalid argument"}]}]}}""") { _, _ -> }
        val unregistered = server(404, """{"error":{"details":[{"errorCode":"UNREGISTERED"}]}}""") { _, _ -> }
        fun sender(uri: URI) = FcmPushSender(FcmProperties(true, "unused", "cookie-test", uri), FcmAccessToken { "token" }, http, mapper, templates, clock)
        assertThat(sender(invalid).send(job, target)).isEqualTo(ProviderResult.Failed("FCM_REJECTED"))
        assertThat(sender(unregistered).send(job, target)).isEqualTo(ProviderResult.Failed("FCM_UNREGISTERED", true))
    }

    @Test fun `APNs uses provider JWT stable identifiers and correct retry floor`() {
        val keyPath = apnsKey()
        val received = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also(servers::add)
        server.createContext("/") { exchange ->
            assertThat(exchange.requestHeaders.getFirst("apns-id")).isEqualTo(job.id.toString())
            assertThat(exchange.requestHeaders.getFirst("apns-collapse-id")).isEqualTo(job.occurrenceId.toString())
            assertThat(exchange.requestHeaders.getFirst("apns-expiration")).isEqualTo(job.expiresAt.epochSecond.toString())
            received.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.requestBody.readAllBytes()
            val bytes = """{"reason":"InternalServerError"}""".toByteArray()
            exchange.sendResponseHeaders(500, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val provider = ApnsPushSender(ApnsProperties(true, keyPath.toString(), "ABCDEFGHIJ", "0123456789", "com.example.cookie",
            URI.create("http://127.0.0.1:${server.address.port}")), http, mapper, templates, clock)
        val result = provider.send(job, PushTarget(requireNotNull(job.deviceId), "IOS", "a".repeat(64), now))
        assertThat(result).isEqualTo(ProviderResult.Retry("APNS_UNAVAILABLE", Duration.ofMinutes(15)))
        val jwt = SignedJWT.parse(received.get().removePrefix("bearer "))
        assertThat(jwt.header.keyID).isEqualTo("ABCDEFGHIJ")
        assertThat(jwt.jwtClaimsSet.issuer).isEqualTo("0123456789")
    }

    @ParameterizedTest(name = "successful APNs response body: {0}")
    @ValueSource(strings = ["", "{}"])
    fun `APNs acceptance does not require optional response fields`(response: String) {
        val uri = server(200, response, "3600") { _, _ -> }
        val provider = ApnsPushSender(ApnsProperties(true, apnsKey().toString(), "ABCDEFGHIJ", "0123456789",
            "com.example.cookie", uri), http, mapper, templates, clock)
        assertThat(provider.send(job, PushTarget(requireNotNull(job.deviceId), "IOS", "a".repeat(64), now)))
            .isEqualTo(ProviderResult.Accepted(job.id.toString()))
    }

    @ParameterizedTest(name = "APNs {0}, Retry-After {1}: wait {2} seconds")
    @CsvSource(delimiter = '|', nullValues = ["<missing>"], value = [
        "429 | <missing> | 60",
        "429 | invalid | 60",
        "429 | 1 | 60",
        "429 | 120 | 120",
        "429 | Thu, 17 Sep 2026 12:05:00 GMT | 300",
        "429 | Thu, 17 Sep 2026 11:59:00 GMT | 60",
        "500 | <missing> | 900",
        "500 | invalid | 900",
        "503 | 120 | 900",
        "503 | 1800 | 1800",
        "503 | Thu, 17 Sep 2026 12:30:00 GMT | 1800",
        "503 | Thu, 17 Sep 2026 11:59:00 GMT | 900",
        "503 | 9223372036854775807 | 86400",
        "429 | Sat, 19 Sep 2026 12:00:00 GMT | 86400",
    ])
    fun `APNs retry after respects provider floors dates and bounded delay`(status: Int, header: String?, seconds: Long) {
        val uri = server(status, """{"reason":"TransientProviderError"}""", header) { _, _ -> }
        val provider = ApnsPushSender(ApnsProperties(true, apnsKey().toString(), "ABCDEFGHIJ", "0123456789",
            "com.example.cookie", uri), http, mapper, templates, clock)
        val result = provider.send(job, PushTarget(requireNotNull(job.deviceId), "IOS", "a".repeat(64), now))
        assertThat(result).isEqualTo(ProviderResult.Retry(
            if (status == 429) "APNS_RATE_LIMIT" else "APNS_UNAVAILABLE", Duration.ofSeconds(seconds)))
    }

    @Test fun `APNs ignores retry after for invalid tokens credentials and token update configuration errors`() {
        val target = PushTarget(requireNotNull(job.deviceId), "IOS", "a".repeat(64), now)
        val keyPath = apnsKey()
        fun send(status: Int, body: String): ProviderResult {
            val uri = server(status, body, "3600") { _, _ -> }
            return ApnsPushSender(ApnsProperties(true, keyPath.toString(), "ABCDEFGHIJ", "0123456789",
                "com.example.cookie", uri), http, mapper, templates, clock).send(job, target)
        }
        assertThat(send(410, """{"reason":"Unregistered","timestamp":${now.minusSeconds(1).toEpochMilli()}}"""))
            .isEqualTo(ProviderResult.Failed("APNS_INVALID_DEVICE", false))
        assertThat(send(410, """{"reason":"Unregistered","timestamp":${now.toEpochMilli()}}"""))
            .isEqualTo(ProviderResult.Failed("APNS_INVALID_DEVICE", true))
        assertThat(send(400, """{"reason":"BadDeviceToken"}"""))
            .isEqualTo(ProviderResult.Failed("APNS_INVALID_DEVICE", true))
        assertThat(send(403, """{"reason":"InvalidProviderToken"}"""))
            .isEqualTo(ProviderResult.Unavailable("APNS_AUTH"))
        assertThat(send(429, """{"reason":"TooManyProviderTokenUpdates"}"""))
            .isEqualTo(ProviderResult.Unavailable("APNS_TOKEN_UPDATES"))
    }

    @Test fun `HTTP date delay rounds up fractional clock seconds and trims header whitespace`() {
        assertThat(retryAfter(" Thu, 17 Sep 2026 12:30:00 GMT ", now.plusMillis(500), 900))
            .isEqualTo(Duration.ofMinutes(30))
        assertThat(retryAfter(" 120 ", now)).isEqualTo(Duration.ofSeconds(120))
    }

    @Test fun `submission evidence is recorded only at actual provider dispatch`() {
        val submissions = AtomicInteger()
        val calls = AtomicInteger()
        val uri = server(200, """{"name":"message"}""") { _, _ ->
            assertThat(submissions.get()).isEqualTo(1)
            calls.incrementAndGet()
        }
        val target = PushTarget(requireNotNull(job.deviceId), "ANDROID", "device-token", now)
        val enabled = FcmProperties(true, "unused", "cookie-test", uri)
        val disabled = FcmPushSender(FcmProperties(), FcmAccessToken { error("Disabled") }, http, mapper, templates, clock)
        assertThat(disabled.send(job, target) { submissions.incrementAndGet() })
            .isEqualTo(ProviderResult.Unavailable("FCM_DISABLED"))
        val unauthenticated = FcmPushSender(enabled, FcmAccessToken { error("OAuth unavailable") }, http, mapper, templates, clock)
        assertThat(unauthenticated.send(job, target) { submissions.incrementAndGet() })
            .isEqualTo(ProviderResult.Unavailable("FCM_AUTH"))
        assertThat(submissions.get()).isZero()
        val sender = FcmPushSender(enabled, FcmAccessToken { "token" }, http, mapper, templates, clock)
        assertThat(sender.send(job, target) { error("Ownership fence lost") })
            .isInstanceOf(ProviderResult.Retry::class.java)
        assertThat(calls.get()).isZero()
        assertThat(sender.send(job, target) { submissions.incrementAndGet() })
            .isEqualTo(ProviderResult.Accepted("message"))
        assertThat(calls.get()).isEqualTo(1)
    }

    private fun apnsKey(): Path {
        val signing = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return Files.writeString(directory.resolve("apns.p8"), "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(signing.private.encoded) + "\n-----END PRIVATE KEY-----\n")
    }

    private fun server(code: Int, response: String, retryAfter: String? = null, capture: (String, String) -> Unit): URI {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also(servers::add)
        server.createContext("/") { exchange ->
            capture(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8), exchange.requestHeaders.getFirst("Authorization"))
            retryAfter?.let { exchange.responseHeaders.set("Retry-After", it) }
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return URI.create("http://127.0.0.1:${server.address.port}")
    }
}
