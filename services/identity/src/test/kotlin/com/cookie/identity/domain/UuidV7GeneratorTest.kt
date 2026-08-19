package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class UuidV7GeneratorTest {
    @Test
    fun `sets rfc uuid v7 version variant and unix timestamp`() {
        val instant = Instant.parse("2026-08-19T12:34:56.789Z")
        val uuid = UuidV7Generator(
            Clock.fixed(instant, ZoneOffset.UTC),
            SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) },
        ).next()

        assertThat(uuid.version()).isEqualTo(7)
        assertThat(uuid.variant()).isEqualTo(2)
        assertThat(uuid.mostSignificantBits ushr 16).isEqualTo(instant.toEpochMilli())
    }
}
