package com.cookie.identity.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class IdentityDatabasePropertiesTest {
    @Test
    fun `derives bounded database timeout values`() {
        val properties = IdentityDatabaseProperties(Duration.ofSeconds(5), Duration.ofMillis(250))

        assertThat(properties.transactionTimeoutSeconds).isEqualTo(5)
        assertThat(properties.statementTimeoutMilliseconds).isEqualTo(5_000)
        assertThat(properties.lockTimeoutMilliseconds).isEqualTo(250)
    }

    @Test
    fun `rejects unbounded or contradictory timeouts`() {
        assertThatThrownBy { IdentityDatabaseProperties(Duration.ZERO, Duration.ofMillis(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { IdentityDatabaseProperties(Duration.ofSeconds(5), Duration.ofSeconds(5)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
