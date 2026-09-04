package com.cookie.identity.application

import com.cookie.identity.application.ports.RateLimitRepository
import com.cookie.identity.application.ports.RateLimitScopeHasher
import java.time.Duration

class IdentityRateLimiter(
    private val repository: RateLimitRepository,
    private val scopeHasher: RateLimitScopeHasher,
) {
    fun registerIp(ip: String) =
        check("register:ip:${digest(IP_NAMESPACE, ip)}", 20, Duration.ofHours(1))

    fun registerEmail(email: String) =
        check("register:email:${digest(EMAIL_NAMESPACE, email)}", 3, Duration.ofHours(1))

    fun resendIp(ip: String) =
        check("resend:ip:${digest(IP_NAMESPACE, ip)}", 30, Duration.ofHours(1))

    fun resendEmail(email: String) {
        consumeAll(
            Limit("resend:email-minute:${digest(EMAIL_NAMESPACE, email)}", 1, Duration.ofMinutes(1)),
            Limit("resend:email-hour:${digest(EMAIL_NAMESPACE, email)}", 5, Duration.ofHours(1)),
        )
    }

    fun loginIp(ip: String) =
        check("login:ip:${digest(IP_NAMESPACE, ip)}", 100, Duration.ofMinutes(15))

    fun loginEmail(email: String) =
        check("login:email:${digest(EMAIL_NAMESPACE, email)}", 10, Duration.ofMinutes(15))

    fun confirm(tokenId: String) {
        check("confirm:token:${digest(TOKEN_NAMESPACE, tokenId)}", 10, Duration.ofMinutes(15))
    }

    fun confirmIp(ip: String) =
        check("confirm:ip:${digest(IP_NAMESPACE, ip)}", 60, Duration.ofMinutes(15))

    fun refreshIp(ip: String) =
        check("refresh:ip:${digest(IP_NAMESPACE, ip)}", 120, Duration.ofMinutes(1))

    fun refresh(familyId: String) {
        check("refresh:family:${digest(FAMILY_NAMESPACE, familyId)}", 30, Duration.ofMinutes(1))
    }

    fun logoutIp(ip: String) =
        check("logout:ip:${digest(IP_NAMESPACE, ip)}", 120, Duration.ofMinutes(1))

    fun logout(familyId: String) {
        check("logout:family:${digest(FAMILY_NAMESPACE, familyId)}", 30, Duration.ofMinutes(1))
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

    private fun digest(namespace: String, value: String): String = scopeHasher.hash(namespace, value)

    private data class Limit(
        val scope: String,
        val maxAttempts: Int,
        val window: Duration,
    )

    private companion object {
        const val IP_NAMESPACE = "ip"
        const val EMAIL_NAMESPACE = "email"
        const val TOKEN_NAMESPACE = "verification-token"
        const val FAMILY_NAMESPACE = "refresh-family"
    }
}
