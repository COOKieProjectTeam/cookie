package com.cookie.identity.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class UuidV7IdGeneratorTest {
    @Test
    fun `sets rfc uuid v7 version variant and unix timestamp`() {
        val instant = Instant.parse("2026-08-19T12:34:56.789Z")
        val uuid = UuidV7IdGenerator(
            Clock.fixed(instant, ZoneOffset.UTC),
            SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) },
        ).next()

        assertThat(uuid.version()).isEqualTo(7)
        assertThat(uuid.variant()).isEqualTo(2)
        assertThat(uuid.mostSignificantBits ushr 16).isEqualTo(instant.toEpochMilli())
    }

    @Test
    fun `rejects a timestamp UUIDv7 cannot represent`() {
        val clock = Clock.fixed(Instant.ofEpochMilli(-1), ZoneOffset.UTC)

        assertThatThrownBy { UuidV7IdGenerator(clock, SecureRandom()).next() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("UUIDv7 timestamp range")
    }
}
