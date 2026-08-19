package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.UUID

class SecretTokensTest {
    private val tokens = SecretTokens(SecureRandom())

    @Test
    fun `round trips version id and verifier without storing raw token`() {
        val id = UUID.fromString("0198c4a5-68b5-7def-8123-456789abcdef")
        val created = tokens.create(id)
        val parsed = tokens.parse(created.value)

        assertThat(created.value).startsWith("v1.$id.")
        assertThat(parsed.id).isEqualTo(id)
        assertThat(tokens.verifierMatches(created.verifierHash, parsed.verifierHash)).isTrue()
        assertThat(created.verifierHash).doesNotContain(parsed.secret)
    }

    @Test
    fun `rejects malformed tokens`() {
        assertThatThrownBy { tokens.parse("not-a-token") }
            .isInstanceOf(InvalidTokenException::class.java)
    }
}
