package com.cookie.notification.delivery

import com.cookie.notification.config.FcmProperties
import com.cookie.platform.web.BoundedHttpExchange
import com.google.auth.oauth2.ServiceAccountCredentials
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

fun interface FcmAccessToken { fun token(): String }

class GoogleFcmAccessToken(properties: FcmProperties, http: BoundedHttpExchange) : FcmAccessToken {
    private val refreshLock = ReentrantLock()
    private val credentials = if (properties.enabled) {
        val path = Path.of(properties.credentialsPath)
        require(Files.size(path) <= 65_536)
        Files.newInputStream(path).use { ServiceAccountCredentials.fromStream(it) }.toBuilder()
            .setScopes(listOf("https://www.googleapis.com/auth/firebase.messaging"))
            .setTokenServerUri(java.net.URI.create("https://oauth2.googleapis.com/token"))
            .setHttpTransportFactory { BoundedGoogleAuthTransport(http) }
            .setDefaultRetriesEnabled(false).build()
    } else null
    override fun token(): String {
        check(refreshLock.tryLock(5, TimeUnit.SECONDS)) { "FCM credential refresh busy" }
        try {
            val current = requireNotNull(credentials)
            current.refreshIfExpired()
            return current.accessToken.tokenValue
        } finally { refreshLock.unlock() }
    }
}

class FcmPushSender(
    private val properties: FcmProperties, private val tokens: FcmAccessToken, private val http: BoundedHttpExchange,
    private val mapper: ObjectMapper, private val templates: NotificationTemplates, private val clock: Clock,
) : PushSender {
    init { require(!properties.enabled || properties.projectId.matches(Regex("[a-z][a-z0-9-]{4,62}"))) }
    override fun send(job: DeliveryJob, target: PushTarget, beforeSubmission: () -> Unit): ProviderResult {
        if (!properties.enabled) return ProviderResult.Unavailable("FCM_DISABLED")
        if (target.platform !in setOf("IOS", "ANDROID")) return ProviderResult.Failed("UNSUPPORTED_PLATFORM")
        val token = try { tokens.token() } catch (_: Exception) { return ProviderResult.Unavailable("FCM_AUTH") }
        if (!job.expiresAt.isAfter(clock.instant())) return ProviderResult.Failed("DELIVERY_EXPIRED")
        val text = templates.push(job.kind, target.locale)
        val body = mapper.createObjectNode().apply {
            putObject("message").apply {
                put("token", target.token)
                putObject("notification").put("title", text.title).put("body", text.body)
                putObject("data").put("deliveryId", job.id.toString())
                when (target.platform) {
                    "ANDROID" -> putObject("android").apply {
                        put("ttl", "${Duration.between(clock.instant(), job.expiresAt).seconds.coerceIn(0, 3600)}s")
                        put("priority", "NORMAL")
                        putObject("notification").put("tag", requireNotNull(job.occurrenceId).toString())
                    }
                    "IOS" -> putObject("apns").putObject("headers").apply {
                        put("apns-push-type", "alert")
                        put("apns-priority", "10")
                        put("apns-id", job.id.toString())
                        put("apns-collapse-id", requireNotNull(job.occurrenceId).toString())
                        put("apns-expiration", job.expiresAt.epochSecond.toString())
                    }
                }
            }
        }
        return try {
            val request = HttpRequest.newBuilder(properties.baseUrl.resolve("/v1/projects/${properties.projectId}/messages:send"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build()
            beforeSubmission()
            val response = http.send(request, Duration.ofSeconds(15))
            val json = runCatching { mapper.readTree(response.body()) }.getOrNull()
            val fcmErrors = json?.path("error")?.path("details")?.filter {
                it.path("@type").takeIf { type -> type.isString }?.stringValue() == "type.googleapis.com/google.firebase.fcm.v1.FcmError"
            }?.mapNotNull { it.path("errorCode").takeIf { code -> code.isString }?.stringValue() }.orEmpty()
            when {
                response.statusCode() == 200 -> ProviderResult.Accepted(json?.path("name")?.takeIf { it.isString }?.stringValue())
                "THIRD_PARTY_AUTH_ERROR" in fcmErrors -> ProviderResult.Unavailable("FCM_THIRD_PARTY_AUTH")
                "UNREGISTERED" in fcmErrors -> ProviderResult.Failed("FCM_UNREGISTERED", true)
                response.statusCode() == 401 || response.statusCode() == 403 -> ProviderResult.Unavailable("FCM_AUTH")
                response.statusCode() == 429 -> ProviderResult.Retry("FCM_RATE_LIMIT",
                    retryAfter(response.headers().firstValue("Retry-After").orElse(null), clock.instant()))
                response.statusCode() >= 500 -> ProviderResult.Retry("FCM_UNAVAILABLE",
                    retryAfter(response.headers().firstValue("Retry-After").orElse(null), clock.instant(), 10))
                else -> ProviderResult.Failed("FCM_REJECTED")
            }
        } catch (_: Exception) { ProviderResult.Retry("FCM_NETWORK") }
    }
}

internal fun retryAfter(value: String?, now: Instant, minimumSeconds: Long = 60): Duration {
    val normalized = value?.trim()
    val seconds = normalized?.toLongOrNull() ?: runCatching {
        val delay = Duration.between(now, ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
        // Round up so a fractional clock instant never retries before the HTTP-date.
        delay.seconds + if (delay.nano > 0) 1 else 0
    }.getOrDefault(minimumSeconds)
    return Duration.ofSeconds(seconds.coerceIn(minimumSeconds, 86_400))
}
