package com.cookie.identity.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

class IdentityPropertiesTest {
    @Test
    fun `accepts bounded secure defaults without rendering connection credentials`() {
        val properties = IdentityProperties(natsUrl = "nats://nats.example:4222")

        assertThat(properties.toString()).doesNotContain("nats.example")
    }

    @Test
    fun `rejects an insecure or ambiguous issuer`() {
        listOf(
            URI.create("http://api.cookie.app"),
            URI.create("https://user@api.cookie.app"),
            URI.create("https://api.cookie.app?tenant=one"),
            URI.create("https://api.cookie.app#fragment"),
        ).forEach { issuer ->
            assertThatThrownBy { IdentityProperties(issuer = issuer) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `rejects unbounded or inconsistent token durations`() {
        assertThatThrownBy { IdentityProperties(accessTokenTtl = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { IdentityProperties(accessTokenTtl = Duration.ofMillis(1_500)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("whole-second")
        assertThatThrownBy {
            IdentityProperties(accessTokenTtl = Duration.ofHours(2), refreshFamilyTtl = Duration.ofHours(1))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            IdentityProperties(
                verificationTokenTtl = Duration.ofMinutes(5),
                verificationResendCooldown = Duration.ofMinutes(6),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            IdentityProperties(
                registrationAttemptTtl = Duration.ofMinutes(4),
                verificationTokenTtl = Duration.ofMinutes(5),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            IdentityProperties(registrationAttemptTtl = Duration.ofDays(7).plusSeconds(1))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects malformed nats endpoints`() {
        assertThatThrownBy { IdentityProperties(natsUrl = "https://nats.example") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("NATS URL")
        assertThatThrownBy { IdentityProperties(natsUrl = "nats://user:secret@nats.example:4222") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("NATS URL")
    }

    @Test
    fun `rejects ambiguous notification key sources`() {
        assertThatThrownBy {
            IdentityProperties(
                notificationPublicKeyPath = "/keys/public.jwk",
                devNotificationPrivateKeyOutputPath = "/keys/private.jwk",
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("mutually exclusive")
    }
}
