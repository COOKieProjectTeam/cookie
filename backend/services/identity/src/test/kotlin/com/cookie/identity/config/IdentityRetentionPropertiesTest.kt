package com.cookie.identity.config

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class IdentityRetentionPropertiesTest {
    @Test
    fun `keeps completed confirmation evidence for the public idempotency window`() {
        assertThatCode {
            IdentityRetentionProperties(completedRegistrationAttemptAudit = Duration.ofDays(30))
        }.doesNotThrowAnyException()

        assertThatThrownBy {
            IdentityRetentionProperties(completedRegistrationAttemptAudit = Duration.ofDays(30).minusSeconds(1))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at least")
    }

    @Test
    fun `keeps abandoned registration evidence after secrets are scrubbed`() {
        assertThatCode {
            IdentityRetentionProperties(abandonedRegistrationAttemptAudit = Duration.ofDays(30))
        }.doesNotThrowAnyException()

        assertThatThrownBy {
            IdentityRetentionProperties(
                abandonedRegistrationAttemptAudit = Duration.ofDays(30).minusSeconds(1),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Abandoned registration attempt retention")
    }
}
