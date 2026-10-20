package com.cookie.identity.messaging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class OutboxRetryBackoffTest {
    @Test
    fun `first retry starts at one second`() {
        assertThat(outboxRetryBackoff(attempt = 1) { error("jitter is not needed") })
            .isEqualTo(Duration.ofSeconds(1))
    }

    @Test
    fun `intermediate retries use equal jitter within the exponential window`() {
        assertThat(outboxRetryBackoff(attempt = 3) { 0 })
            .isEqualTo(Duration.ofSeconds(2))
        assertThat(outboxRetryBackoff(attempt = 3) { bound -> bound - 1 })
            .isEqualTo(Duration.ofSeconds(4))
    }

    @Test
    fun `capped retries retain jitter without exceeding five minutes`() {
        assertThat(outboxRetryBackoff(attempt = 100) { 0 })
            .isEqualTo(Duration.ofSeconds(150))
        assertThat(outboxRetryBackoff(attempt = 100) { bound -> bound - 1 })
            .isEqualTo(Duration.ofMinutes(5))
    }
}
