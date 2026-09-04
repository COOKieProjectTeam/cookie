package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class VerifierHashTest {
    @Test
    fun `accepts lowercase SHA-256 hex`() {
        val value = "0123456789abcdef".repeat(4)

        assertThat(VerifierHash.fromSha256Hex(value).value).isEqualTo(value)
    }

    @Test
    fun `rejects non-canonical verifier hashes`() {
        listOf(
            "a".repeat(63),
            "a".repeat(65),
            "A".repeat(64),
            "g".repeat(64),
        ).forEach { value ->
            assertThatIllegalArgumentException()
                .isThrownBy { VerifierHash.fromSha256Hex(value) }
        }
    }

    @Test
    fun `does not expose hash through string representation`() {
        val hash = VerifierHash.fromSha256Hex("a".repeat(64))

        assertThat(hash.toString()).isEqualTo("VerifierHash([redacted])")
    }
}
