package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PasswordHasherTest {
    @Test
    fun `stores argon2id phc with selected parameters`() {
        val hasher = PasswordHasher()
        val password = "НадежныйПароль-123"

        val encoded = hasher.encode(password)

        assertThat(encoded).startsWith("${'$'}argon2id${'$'}v=19${'$'}m=19456,t=2,p=1${'$'}")
        assertThat(encoded).doesNotContain(password)
        assertThat(hasher.matches(password, encoded)).isTrue()
        assertThat(hasher.matches("НеверныйПароль-123", encoded)).isFalse()
    }
}
