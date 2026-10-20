package com.cookie.identity.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class HmacSha256RateLimitScopeHasherTest {
    @Test
    fun `hashes a domain-separated scope with a stable hmac`() {
        val hasher = HmacSha256RateLimitScopeHasher(KEY)

        assertThat(hasher.hash("ip", "192.0.2.1"))
            .isEqualTo("66b5ff2fb64f19c03d750996688dbf3f")
        assertThat(hasher.hash("email", "192.0.2.1"))
            .isNotEqualTo(hasher.hash("ip", "192.0.2.1"))
    }

    @Test
    fun `different keys cannot correlate the same sensitive value`() {
        val first = HmacSha256RateLimitScopeHasher(KEY)
        val second = HmacSha256RateLimitScopeHasher(OTHER_KEY)

        assertThat(first.hash("ip", "192.0.2.1"))
            .isNotEqualTo(second.hash("ip", "192.0.2.1"))
    }

    @Test
    fun `rejects malformed noncanonical and wrong-sized keys`() {
        listOf("", "not-base64url", "$KEY=", "YQ").forEach { value ->
            assertThatThrownBy { HmacSha256RateLimitScopeHasher(value) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("32-byte secret")
        }
    }

    @Test
    fun `rejects ambiguous namespace or empty value and redacts its key`() {
        val hasher = HmacSha256RateLimitScopeHasher(KEY)

        assertThatThrownBy { hasher.hash("bad namespace", "value") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { hasher.hash("ip", "") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(hasher.toString()).doesNotContain(KEY).contains("[redacted]")
    }

    private companion object {
        const val KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY"
        const val OTHER_KEY = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA"
    }
}
