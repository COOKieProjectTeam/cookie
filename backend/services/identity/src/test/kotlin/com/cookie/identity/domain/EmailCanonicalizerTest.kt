package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EmailCanonicalizerTest {
    private val canonicalizer = EmailCanonicalizer()

    @Test
    fun `canonicalizes ru email`() {
        assertThat(canonicalizer.canonicalize("  User.Name+tag@Example.RU  "))
            .isEqualTo("user.name+tag@example.ru")
    }

    @Test
    fun `canonicalizes rf domain through idna`() {
        assertThat(canonicalizer.canonicalize("USER@пример.рф"))
            .isEqualTo("user@xn--e1afmkfd.xn--p1ai")
    }

    @Test
    fun `rejects non-russian top-level domain`() {
        assertThatThrownBy { canonicalizer.canonicalize("user@example.com") }
            .isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    fun `rejects unicode local part`() {
        assertThatThrownBy { canonicalizer.canonicalize("почта@example.ru") }
            .isInstanceOf(InvalidInputException::class.java)
    }
}
