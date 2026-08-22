package com.cookie.identity.security

import com.cookie.identity.application.InvalidTokenException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.UUID

class Sha256SecretTokenServiceTest {
    private val tokens = Sha256SecretTokenService(SecureRandom())

    @Test
    fun `round trips token id and verifier without exposing or storing raw secret`() {
        val id = UUID.fromString("0198c4a5-68b5-7def-8123-456789abcdef")
        val created = tokens.create(id)
        val parsed = tokens.parse(created.value)

        assertThat(created.value).startsWith("v1.$id.")
        assertThat(parsed.id).isEqualTo(id)
        assertThat(tokens.verifierMatches(created.verifierHash, parsed.verifierHash)).isTrue()
        assertThat(created.verifierHash).doesNotContain(created.value.substringAfterLast('.'))
        assertThat(created.toString()).doesNotContain(created.value)
        assertThat(parsed.toString()).doesNotContain(created.value.substringAfterLast('.'))
    }

    @Test
    fun `rejects malformed tokens`() {
        listOf(
            "not-a-token",
            "v2.0198c4a5-68b5-7def-8123-456789abcdef.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "v1.not-a-uuid.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "v1.0198c4a5-68b5-7def-8123-456789abcdef.invalid.invalid",
        ).forEach { malformed ->
            assertThatThrownBy { tokens.parse(malformed) }
                .isInstanceOf(InvalidTokenException::class.java)
        }
    }
}
