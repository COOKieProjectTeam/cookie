package com.cookie.identity.application

import com.cookie.identity.application.ports.RateLimitRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

class IdentityRateLimiter(
    private val repository: RateLimitRepository,
) {
    fun registerIp(ip: String) =
        check("register:ip:${digest(ip)}", 20, Duration.ofHours(1))

    fun registerEmail(email: String) =
        check("register:email:${digest(email)}", 3, Duration.ofHours(1))

    fun resendIp(ip: String) =
        check("resend:ip:${digest(ip)}", 30, Duration.ofHours(1))

    fun resendEmail(email: String) {
        consumeAll(
            Limit("resend:email-minute:${digest(email)}", 1, Duration.ofMinutes(1)),
            Limit("resend:email-hour:${digest(email)}", 5, Duration.ofHours(1)),
        )
    }

    fun loginIp(ip: String) =
        check("login:ip:${digest(ip)}", 100, Duration.ofMinutes(15))

    fun loginEmail(email: String) =
        check("login:email:${digest(email)}", 10, Duration.ofMinutes(15))

    fun confirm(tokenId: String) {
        check("confirm:token:${digest(tokenId)}", 10, Duration.ofMinutes(15))
    }

    fun confirmIp(ip: String) =
        check("confirm:ip:${digest(ip)}", 60, Duration.ofMinutes(15))

    fun refresh(familyId: String) {
        check("refresh:family:${digest(familyId)}", 30, Duration.ofMinutes(1))
    }

    fun logout(familyId: String) {
        check("logout:family:${digest(familyId)}", 30, Duration.ofMinutes(1))
    }

    fun ipOnly(route: String, ip: String, limit: Int, window: Duration) {
        check("$route:ip:${digest(ip)}", limit, window)
    }

    /**
     * Consume every applicable scope before reporting rejection. Otherwise a
     * saturated narrow scope can prevent the broader IP bucket from advancing.
     */
    private fun consumeAll(vararg limits: Limit) {
        val exceeded = limits.map { limit -> limit to repository.consume(limit.scope, limit.window) }
            .filter { (limit, result) -> result.attemptCount > limit.maxAttempts }
        if (exceeded.isNotEmpty()) {
            val retryAfter = exceeded.maxOf { (_, result) -> result.retryAfterSeconds }
            throw RateLimitExceededException(retryAfter)
        }
    }

    private fun check(scope: String, limit: Int, window: Duration) {
        val result = repository.consume(scope, window)
        if (result.attemptCount > limit) {
            throw RateLimitExceededException(result.retryAfterSeconds)
        }
    }

    private fun digest(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    ).take(32)

    private data class Limit(
        val scope: String,
        val maxAttempts: Int,
        val window: Duration,
    )
}
