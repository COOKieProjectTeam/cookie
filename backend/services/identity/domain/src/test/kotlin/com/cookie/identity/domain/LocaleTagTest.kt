package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LocaleTagTest {
    @Test
    fun `parses and canonicalizes well-formed BCP 47 tags`() {
        assertThat(LocaleTag.parse("ru-ru").value).isEqualTo("ru-RU")
        assertThat(LocaleTag.parse("sr-Latn-RS").value).isEqualTo("sr-Latn-RS")
        assertThat(LocaleTag.parse("x-cookie-preview").value).isEqualTo("x-cookie-preview")
        assertThat(LocaleTag.parseOrNull(null)).isNull()
    }

    @Test
    fun `rejects malformed or oversized client values`() {
        listOf("", "ru_РУ", "ru--RU").forEach { malformed ->
            assertThatThrownBy { LocaleTag.parse(malformed) }
                .isInstanceOf(InvalidLocaleTagException::class.java)
        }
        assertThatThrownBy { LocaleTag.parse("x-" + "a".repeat(LocaleTag.MAX_LENGTH)) }
            .isInstanceOf(InvalidLocaleTagException::class.java)
    }

    @Test
    fun `reconstitution treats invalid persistence as server corruption`() {
        assertThatIllegalStateException().isThrownBy { LocaleTag.reconstitute("ru_РУ") }
        assertThatIllegalStateException().isThrownBy { LocaleTag.reconstitute("ru-ru") }
    }
}
