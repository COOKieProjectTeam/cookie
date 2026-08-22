package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CanonicalEmailTest {
    @Test
    fun `canonicalizes local part and idn domain`() {
        assertThat(CanonicalEmail.parse("  User.Name+tag@пример.РФ  ").value)
            .isEqualTo("user.name+tag@xn--e1afmkfd.xn--p1ai")
        assertThat(CanonicalEmail.parse("user@faß.ru").value)
            .isEqualTo("user@xn--fa-hia.ru")
    }

    @Test
    fun `rejects unsupported domains and unicode local parts`() {
        assertThatThrownBy { CanonicalEmail.parse("user@example.com") }
            .isInstanceOf(InvalidInputException::class.java)
        assertThatThrownBy { CanonicalEmail.parse("почта@example.ru") }
            .isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    fun `rehydration is independent from the current admission allowlist`() {
        assertThat(CanonicalEmail.reconstitute("legacy@example.com").value)
            .isEqualTo("legacy@example.com")
        assertThatThrownBy { CanonicalEmail.reconstitute("Legacy@Example.com") }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { CanonicalEmail.reconstitute("legacy@") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `rejects a canonical value longer than the database column`() {
        val oversized = "a".repeat(64) + "@" + "b".repeat(63) + "." +
            "c".repeat(63) + "." + "d".repeat(61) + ".ru"

        assertThatThrownBy { CanonicalEmail.parse(oversized) }
            .isInstanceOf(InvalidInputException::class.java)
    }
}
