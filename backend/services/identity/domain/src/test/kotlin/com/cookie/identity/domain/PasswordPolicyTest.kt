package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PasswordPolicyTest {
    private val policy = PasswordPolicy()

    @Test
    fun `counts unicode code points and accepts the documented range`() {
        assertThatCode { policy.validate("СложныйПароль-123") }.doesNotThrowAnyException()
        assertThatCode { policy.validate("😀".repeat(128)) }.doesNotThrowAnyException()
        assertThatThrownBy { policy.validate("😀".repeat(129)) }
            .isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    fun `rejects short whitespace and control values`() {
        assertThatThrownBy { policy.validate("ShortPassword1") }
            .isInstanceOf(InvalidInputException::class.java)
        listOf(" ", "\t", "\n", "\u00A0", "\u2003", "\u200B").forEach { forbidden ->
            assertThatThrownBy { policy.validate("ValidPassword123$forbidden") }
                .describedAs("forbidden code point U+%04X", forbidden.codePointAt(0))
                .isInstanceOf(InvalidInputException::class.java)
        }
    }
}
