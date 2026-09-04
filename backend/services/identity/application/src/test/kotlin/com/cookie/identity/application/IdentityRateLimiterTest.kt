package com.cookie.identity.application

import com.cookie.identity.application.ports.RateLimitRepository
import com.cookie.identity.application.ports.RateLimitScopeHasher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class IdentityRateLimiterTest {
    @Test
    fun `email rejection still consumes broader hourly scope after ip guard`() {
        val attempts = mutableMapOf<String, Int>()
        val repository = object : RateLimitRepository {
            override fun consume(scopeKey: String, window: Duration): RateLimitWindow {
                attempts.merge(scopeKey, 1, Int::plus)
                val storedCount = if (scopeKey.startsWith("resend:email-minute:")) 2 else 1
                return RateLimitWindow(storedCount, window.seconds)
            }
        }
        val limiter = IdentityRateLimiter(repository, TEST_RATE_LIMIT_SCOPE_HASHER)

        limiter.resendIp("192.0.2.1")
        assertThatThrownBy { limiter.resendEmail("user@example.ru") }
            .isInstanceOf(RateLimitExceededException::class.java)

        assertThat(attempts.values).containsExactlyInAnyOrder(1, 1, 1)
        assertThat(attempts.keys).anyMatch { it.startsWith("resend:email-hour:") }
        assertThat(attempts.keys).anyMatch { it.startsWith("resend:ip:") }
    }

    @Test
    fun `sensitive dimensions are hashed in separate namespaces`() {
        val hashed = mutableListOf<Pair<String, String>>()
        val repository = object : RateLimitRepository {
            override fun consume(scopeKey: String, window: Duration) = RateLimitWindow(1, window.seconds)
        }
        val limiter = IdentityRateLimiter(
            repository,
            RateLimitScopeHasher { namespace, value ->
                hashed += namespace to value
                "opaque"
            },
        )

        limiter.registerIp("same-value")
        limiter.registerEmail("same-value")
        limiter.confirm("same-value")
        limiter.refresh("same-value")

        assertThat(hashed).containsExactly(
            "ip" to "same-value",
            "email" to "same-value",
            "verification-token" to "same-value",
            "refresh-family" to "same-value",
        )
    }

    @Test
    fun `refresh and logout retain the existing per-ip ceilings behind named operations`() {
        val counts = mutableMapOf<String, Int>()
        val repository = object : RateLimitRepository {
            override fun consume(scopeKey: String, window: Duration): RateLimitWindow {
                val count = counts.merge(scopeKey, 1, Int::plus) ?: error("Missing count")
                return RateLimitWindow(count, window.seconds)
            }
        }
        val limiter = IdentityRateLimiter(repository, RateLimitScopeHasher { _, _ -> "opaque" })

        repeat(120) {
            limiter.refreshIp("192.0.2.1")
            limiter.logoutIp("192.0.2.1")
        }
        assertThatThrownBy { limiter.refreshIp("192.0.2.1") }
            .isInstanceOf(RateLimitExceededException::class.java)
        assertThatThrownBy { limiter.logoutIp("192.0.2.1") }
            .isInstanceOf(RateLimitExceededException::class.java)
    }
}
