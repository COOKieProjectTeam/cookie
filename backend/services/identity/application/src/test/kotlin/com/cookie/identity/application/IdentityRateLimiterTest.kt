package com.cookie.identity.application

import com.cookie.identity.application.ports.RateLimitRepository
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
        val limiter = IdentityRateLimiter(repository)

        limiter.resendIp("192.0.2.1")
        assertThatThrownBy { limiter.resendEmail("user@example.ru") }
            .isInstanceOf(RateLimitExceededException::class.java)

        assertThat(attempts.values).containsExactlyInAnyOrder(1, 1, 1)
        assertThat(attempts.keys).anyMatch { it.startsWith("resend:email-hour:") }
        assertThat(attempts.keys).anyMatch { it.startsWith("resend:ip:") }
    }
}
