package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PasswordPolicyTest {
    private val policy = PasswordPolicy()

    @Test
    fun `registration counts unicode code points and accepts spaces and emoji`() {
        assertThat(policy.prepareForRegistration("Correct horse 😀 battery").value)
            .isEqualTo("Correct horse 😀 battery")
        assertThat(policy.prepareForRegistration("😀".repeat(128)).value)
            .isEqualTo("😀".repeat(128))
        assertThat(policy.prepareForRegistration("👨‍👩‍👧‍👦 family password").value)
            .isEqualTo("👨‍👩‍👧‍👦 family password")
    }

    @Test
    fun `normalizes password to nfc before returning it for hashing`() {
        val decomposed = "e\u0301".repeat(15)

        val password = policy.prepareForRegistration(decomposed)

        assertThat(password.value).isEqualTo("é".repeat(15))
        assertThat(password.toString()).isEqualTo("[redacted-password]")
    }

    @Test
    fun `rejects an excessive raw input before normalized-length validation`() {
        assertThatThrownBy {
            policy.prepareForRegistration("a".repeat(PasswordPolicy.MAX_RAW_CODE_UNITS + 1))
        }.isInstanceOfSatisfying(InvalidPasswordException::class.java) {
            assertThat(it.reason).isEqualTo(InvalidPasswordReason.TOO_LONG)
        }
    }

    @Test
    fun `authentication accepts legacy lengths but registration keeps the minimum`() {
        assertThat(policy.prepareForAuthentication("short").value).isEqualTo("short")
        assertThatThrownBy { policy.prepareForRegistration("short") }
            .isInstanceOfSatisfying(InvalidPasswordException::class.java) {
                assertThat(it.reason).isEqualTo(InvalidPasswordReason.TOO_SHORT)
            }
        assertThatThrownBy { policy.prepareForAuthentication("") }
            .isInstanceOfSatisfying(InvalidPasswordException::class.java) {
                assertThat(it.reason).isEqualTo(InvalidPasswordReason.TOO_SHORT)
            }
    }

    @Test
    fun `rejects oversized control and malformed unicode values with typed reasons`() {
        assertThatThrownBy { policy.prepareForRegistration("😀".repeat(129)) }
            .isInstanceOfSatisfying(InvalidPasswordException::class.java) {
                assertThat(it.reason).isEqualTo(InvalidPasswordReason.TOO_LONG)
            }
        listOf("\u0000", "\t", "\n", "\u2028", "\u2029").forEach { forbidden ->
            assertThatThrownBy { policy.prepareForRegistration("Valid password 123$forbidden") }
                .describedAs("forbidden code point U+%04X", forbidden.codePointAt(0))
                .isInstanceOfSatisfying(InvalidPasswordException::class.java) {
                    assertThat(it.reason).isEqualTo(InvalidPasswordReason.CONTROL_CHARACTER)
                }
        }
        assertThatThrownBy { policy.prepareForRegistration("Valid password 123\uD83D") }
            .isInstanceOfSatisfying(InvalidPasswordException::class.java) {
                assertThat(it.reason).isEqualTo(InvalidPasswordReason.MALFORMED_UNICODE)
            }
    }
}
