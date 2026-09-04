package com.cookie.identity.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class JdbcReadinessPropertiesTest {
    @Test
    fun `rounds the hikari millisecond timeout up for jdbc`() {
        assertThat(JdbcReadinessProperties(Duration.ofMillis(1_001)).validationTimeoutSeconds)
            .isEqualTo(2)
        assertThat(JdbcReadinessProperties(Duration.ofSeconds(1)).validationTimeoutSeconds)
            .isEqualTo(1)
    }

    @Test
    fun `rejects a non-positive timeout`() {
        assertThatThrownBy { JdbcReadinessProperties(Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
