package com.cookie.identity.application

import com.cookie.identity.application.ports.RateLimitRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import kotlin.math.max

class IdentityRateLimiter(
    private val repository: RateLimitRepository,
    private val clock: Clock,
) {
    fun register(email: String, ip: String) {
        check("register:email:${digest(email)}", 3, Duration.ofHours(1))
        check("register:ip:${digest(ip)}", 20, Duration.ofHours(1))
    }

    fun resend(email: String, ip: String) {
        check("resend:email-minute:${digest(email)}", 1, Duration.ofMinutes(1))
        check("resend:email-hour:${digest(email)}", 5, Duration.ofHours(1))
        check("resend:ip:${digest(ip)}", 30, Duration.ofHours(1))
    }

    fun login(email: String, ip: String) {
        check("login:email:${digest(email)}", 10, Duration.ofMinutes(15))
        check("login:ip:${digest(ip)}", 100, Duration.ofMinutes(15))
    }

    fun confirm(tokenId: String) {
        check("confirm:token:${digest(tokenId)}", 10, Duration.ofMinutes(15))
    }

    fun refresh(sessionId: String) {
        check("refresh:session:${digest(sessionId)}", 30, Duration.ofMinutes(1))
    }

    fun logout(sessionId: String) {
        check("logout:session:${digest(sessionId)}", 30, Duration.ofMinutes(1))
    }

    fun ipOnly(route: String, ip: String, limit: Int, window: Duration) {
        check("$route:ip:${digest(ip)}", limit, window)
    }

    private fun check(scope: String, limit: Int, window: Duration) {
        val result = repository.consume(scope, window)
        if (result.attemptCount > limit) {
            val remaining = Duration.between(clock.instant(), result.expiresAt).seconds
            throw RateLimitExceededException(max(1, remaining))
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(32)
}
