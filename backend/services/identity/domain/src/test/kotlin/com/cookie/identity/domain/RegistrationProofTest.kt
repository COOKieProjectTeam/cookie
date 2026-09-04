package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

class RegistrationProofTest {
    @Test
    fun `accepts only canonical unpadded base64url for 256 bits`() {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })

        val proof = RegistrationProof.parse(encoded)

        assertThat(proof.value).isEqualTo(encoded)
        assertThat(proof.toString()).doesNotContain(encoded)
    }

    @Test
    fun `rejects malformed non-canonical and wrong-sized proofs`() {
        listOf("A".repeat(42), "A".repeat(44), "A".repeat(42) + "=", "!".repeat(43)).forEach { raw ->
            assertThatThrownBy { RegistrationProof.parse(raw) }
                .isInstanceOf(InvalidRegistrationProofException::class.java)
        }
    }
}
