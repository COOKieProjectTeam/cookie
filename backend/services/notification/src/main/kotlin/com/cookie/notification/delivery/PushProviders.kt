package com.cookie.notification.delivery

import com.cookie.notification.config.ApnsProperties
import com.cookie.notification.config.FcmProperties
import com.cookie.notification.security.BoundedHttpExchange
import com.google.auth.oauth2.ServiceAccountCredentials
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpRequest
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class ApnsPushSender(
    private val properties: ApnsProperties, private val http: BoundedHttpExchange,
    private val mapper: ObjectMapper, private val templates: NotificationTemplates, private val clock: Clock,
) : PushSender {
    private val signingKey: ECPrivateKey? = if (properties.enabled) {
        require(properties.keyId.matches(Regex("[A-Za-z0-9]{10}")) && properties.teamId.matches(Regex("[A-Za-z0-9]{10}")))
        require(properties.topic.matches(Regex("[A-Za-z0-9.-]{1,255}")))
        val path = Path.of(properties.keyPath)
        require(Files.size(path) <= 16_384)
        val pem = Files.readString(path).replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "").replace(Regex("\\s"), "")
        (KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem))) as ECPrivateKey)
            .also { require(it.params.curve.field.fieldSize == 256) }
    } else null
    private var cachedToken: Pair<Instant, String>? = null

    override fun send(job: DeliveryJob, target: PushTarget, beforeSubmission: () -> Unit): ProviderResult {
        if (!properties.enabled) return ProviderResult.Unavailable("APNS_DISABLED")
        if (!target.token.matches(Regex("[0-9a-fA-F]{32,512}"))) return ProviderResult.Failed("APNS_BAD_TOKEN", true)
        val text = templates.push(job.kind, target.locale)
        val body = mapper.createObjectNode().apply {
            putObject("aps").putObject("alert").put("title", text.title).put("body", text.body)
            put("deliveryId", job.id.toString())
        }
        val serialized = mapper.writeValueAsBytes(body)
        if (serialized.size > 4096) return ProviderResult.Failed("APNS_PAYLOAD_SIZE")
        return try {
            val request = HttpRequest.newBuilder(properties.baseUrl.resolve("/3/device/${target.token}"))
                .timeout(Duration.ofSeconds(15)).header("authorization", "bearer ${providerToken()}")
                .header("apns-topic", properties.topic).header("apns-id", job.id.toString())
                .header("apns-push-type", "alert").header("apns-priority", "10")
                .header("apns-collapse-id", requireNotNull(job.occurrenceId).toString())
                .header("apns-expiration", job.expiresAt.epochSecond.toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(serialized)).build()
            beforeSubmission()
            val response = http.send(request, Duration.ofSeconds(15))
            // APNs successful responses have no JSON body; acceptance must never become a parse retry.
            if (response.statusCode() == 200) return ProviderResult.Accepted(job.id.toString())
            val responseBody = runCatching { mapper.readTree(response.body()) }.getOrNull()
            val reason = responseBody?.path("reason")?.takeIf { it.isString }?.stringValue()
            val invalidatedAt = responseBody?.path("timestamp")?.takeIf { it.isNumber }?.longValue()?.let(Instant::ofEpochMilli)
            when {
                reason in setOf("BadDeviceToken", "Unregistered", "ExpiredToken") -> ProviderResult.Failed("APNS_INVALID_DEVICE",
                    invalidatedAt == null || !target.revision.isAfter(invalidatedAt))
                response.statusCode() == 429 && reason == "TooManyProviderTokenUpdates" -> ProviderResult.Unavailable("APNS_TOKEN_UPDATES")
                response.statusCode() == 429 -> ProviderResult.Retry("APNS_RATE_LIMIT",
                    retryAfter(response.headers().firstValue("Retry-After").orElse(null), clock.instant()))
                response.statusCode() >= 500 -> ProviderResult.Retry("APNS_UNAVAILABLE",
                    retryAfter(response.headers().firstValue("Retry-After").orElse(null), clock.instant(), 15 * 60L))
                response.statusCode() == 403 -> ProviderResult.Unavailable("APNS_AUTH")
                else -> ProviderResult.Failed("APNS_REJECTED")
            }
        } catch (_: Exception) { ProviderResult.Retry("APNS_NETWORK") }
    }

    @Synchronized
    private fun providerToken(): String {
        val now = clock.instant()
        cachedToken?.takeIf { it.first.plus(Duration.ofMinutes(50)).isAfter(now) }?.let { return it.second }
        val token = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(properties.keyId).build(),
            JWTClaimsSet.Builder().issuer(properties.teamId).issueTime(Date.from(now)).build()).apply {
            sign(ECDSASigner(requireNotNull(signingKey)))
        }.serialize()
        cachedToken = now to token
        return token
    }
}

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
        val token = try { tokens.token() } catch (_: Exception) { return ProviderResult.Unavailable("FCM_AUTH") }
        if (!job.expiresAt.isAfter(clock.instant())) return ProviderResult.Failed("DELIVERY_EXPIRED")
        val text = templates.push(job.kind, target.locale)
        val body = mapper.createObjectNode().apply {
            putObject("message").apply {
                put("token", target.token)
                putObject("notification").put("title", text.title).put("body", text.body)
                putObject("data").put("deliveryId", job.id.toString())
                putObject("android").apply {
                    put("ttl", "${Duration.between(clock.instant(), job.expiresAt).seconds.coerceIn(0, 3600)}s")
                    put("priority", "NORMAL")
                    putObject("notification").put("tag", requireNotNull(job.occurrenceId).toString())
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
            val unregistered = json?.path("error")?.path("details")?.any {
                it.path("errorCode").takeIf { code -> code.isString }?.stringValue() == "UNREGISTERED"
            } == true
            when {
                response.statusCode() == 200 -> ProviderResult.Accepted(json?.path("name")?.takeIf { it.isString }?.stringValue())
                unregistered -> ProviderResult.Failed("FCM_UNREGISTERED", true)
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
