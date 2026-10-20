package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
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
    fun `structural parsing is independent from domain admission policy`() {
        assertThat(CanonicalEmail.parse("user@example.com").value).isEqualTo("user@example.com")

        val policy = RussianEmailAdmissionPolicy()
        assertThatCode { policy.validate(CanonicalEmail.parse("user@example.ru")) }.doesNotThrowAnyException()
        assertThatCode { policy.validate(CanonicalEmail.parse("user@пример.рф")) }.doesNotThrowAnyException()
        assertThatThrownBy { policy.validate(CanonicalEmail.parse("user@example.com")) }
            .isInstanceOf(EmailDomainNotAllowedException::class.java)
        assertThatThrownBy { policy.validate(CanonicalEmail.parse("user@ru")) }
            .isInstanceOf(EmailDomainNotAllowedException::class.java)
        assertThatThrownBy { policy.validate(CanonicalEmail.parse("user@xn--p1ai")) }
            .isInstanceOf(EmailDomainNotAllowedException::class.java)
    }

    @Test
    fun `rejects unicode local parts with a typed reason`() {
        assertThatThrownBy { CanonicalEmail.parse("почта@example.ru") }
            .isInstanceOfSatisfying(InvalidEmailException::class.java) {
                assertThat(it.reason).isEqualTo(InvalidEmailReason.LOCAL_PART_HAS_UNSUPPORTED_CHARACTERS)
            }
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
            .isInstanceOfSatisfying(InvalidEmailException::class.java) {
                assertThat(it.reason).isEqualTo(InvalidEmailReason.ADDRESS_TOO_LONG)
            }
    }

    @Test
    fun `reports structural failures without echoing the submitted address`() {
        assertThatThrownBy { CanonicalEmail.parse("user.example.ru") }
            .isInstanceOfSatisfying(InvalidEmailException::class.java) {
                assertThat(it.reason).isEqualTo(InvalidEmailReason.INVALID_SEPARATOR)
                assertThat(it.message).doesNotContain("user.example.ru")
            }
        assertThatThrownBy { CanonicalEmail.parse("user@") }
            .isInstanceOfSatisfying(InvalidEmailException::class.java) {
                assertThat(it.reason).isEqualTo(InvalidEmailReason.DOMAIN_EMPTY)
            }
    }

    @Test
    fun `bounds raw input before invoking idna processing`() {
        assertThatThrownBy { CanonicalEmail.parse("user@" + "д".repeat(2_000)) }
            .isInstanceOfSatisfying(InvalidEmailException::class.java) {
                assertThat(it.reason).isEqualTo(InvalidEmailReason.ADDRESS_TOO_LONG)
            }
        assertThatThrownBy { CanonicalEmail.parse(" ".repeat(2_000) + "user@example.ru") }
            .isInstanceOfSatisfying(InvalidEmailException::class.java) {
                assertThat(it.reason).isEqualTo(InvalidEmailReason.ADDRESS_TOO_LONG)
            }
    }
}
