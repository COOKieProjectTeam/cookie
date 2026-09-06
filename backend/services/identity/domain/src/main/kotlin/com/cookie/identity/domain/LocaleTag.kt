package com.cookie.identity.domain

import java.util.IllformedLocaleException
import java.util.Locale

/** Canonical BCP 47 language tag attached to a registration process. */
class LocaleTag private constructor(val value: String) {
    override fun equals(other: Any?): Boolean = other is LocaleTag && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        /** Product/storage bound, not a BCP 47 protocol limit. */
        const val MAX_LENGTH = 255

        fun parseOrNull(rawValue: String?): LocaleTag? = rawValue?.let(::parse)

        fun parse(rawValue: String): LocaleTag {
            if (rawValue.isEmpty() || rawValue.length > MAX_LENGTH) throw InvalidLocaleTagException()
            val canonical = try {
                Locale.Builder().setLanguageTag(rawValue).build().toLanguageTag()
            } catch (_: IllformedLocaleException) {
                throw InvalidLocaleTagException()
            }
            return LocaleTag(canonical)
        }

        /** Rebuilds a canonical value read from owned storage. */
        fun reconstitute(canonicalValue: String): LocaleTag {
            val parsed = try {
                parse(canonicalValue)
            } catch (exception: InvalidLocaleTagException) {
                throw IllegalStateException("Stored locale tag is invalid", exception)
            }
            check(parsed.value == canonicalValue) { "Stored locale tag is not canonical" }
            return parsed
        }
    }
}
