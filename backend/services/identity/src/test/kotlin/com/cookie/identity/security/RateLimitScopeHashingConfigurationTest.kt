package com.cookie.identity.security

import com.cookie.identity.config.IdentityProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RateLimitScopeHashingConfigurationTest {
    private val configuration = RateLimitScopeHashingConfiguration()

    @Test
    fun `production fails closed without a shared hmac key`() {
        assertThatThrownBy {
            configuration.productionRateLimitScopeHasher(IdentityProperties())
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("COOKIE_IDENTITY_RATE_LIMIT_HMAC_KEY")
    }

    @Test
    fun `production uses configured key without rendering it`() {
        val hasher = configuration.productionRateLimitScopeHasher(
            IdentityProperties(rateLimitHmacKey = KEY),
        )

        assertThat(hasher.hash("ip", "192.0.2.1")).hasSize(32)
        assertThat(hasher.toString()).doesNotContain(KEY)
    }

    @Test
    fun `development key is stable across service instances`() {
        val first = configuration.developmentRateLimitScopeHasher(IdentityProperties())
        val second = configuration.developmentRateLimitScopeHasher(IdentityProperties())

        assertThat(first.hash("ip", "192.0.2.1"))
            .isEqualTo(second.hash("ip", "192.0.2.1"))
    }

    @Test
    fun `development profile honours an explicitly configured replica key`() {
        val configured = configuration.developmentRateLimitScopeHasher(
            IdentityProperties(rateLimitHmacKey = KEY),
        )
        val production = configuration.productionRateLimitScopeHasher(
            IdentityProperties(rateLimitHmacKey = KEY),
        )

        assertThat(configured.hash("ip", "192.0.2.1"))
            .isEqualTo(production.hash("ip", "192.0.2.1"))
    }

    private companion object {
        const val KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY"
    }
}
