package com.cookie.identity.domain

import com.ibm.icu.text.IDNA
import java.util.Locale

class CanonicalEmail private constructor(val value: String) {
    internal val asciiDomain: String
        get() = value.substringAfter('@')

    internal val topLevelDomain: String
        get() = asciiDomain.substringAfterLast('.', missingDelimiterValue = "")

    override fun equals(other: Any?): Boolean = other is CanonicalEmail && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "[redacted-email]"

    companion object {
        private val ASCII_LOCAL_PART = Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+")
        private val IDNA_PROCESSOR = IDNA.getUTS46Instance(
            IDNA.NONTRANSITIONAL_TO_ASCII or IDNA.USE_STD3_RULES or
                IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ or IDNA.CHECK_CONTEXTO,
        )

        fun parse(rawEmail: String): CanonicalEmail = CanonicalEmail(canonicalize(rawEmail))

        fun reconstitute(canonicalEmail: String): CanonicalEmail {
            val normalized = try {
                canonicalize(canonicalEmail)
            } catch (exception: InvalidEmailException) {
                throw IllegalStateException("Stored email is not structurally valid", exception)
            }
            check(normalized == canonicalEmail) { "Stored email is not canonical" }
            return CanonicalEmail(normalized)
        }

        private fun canonicalize(rawEmail: String): String {
            if (rawEmail.length > MAX_RAW_EMAIL_LENGTH) invalidEmail(InvalidEmailReason.ADDRESS_TOO_LONG)
            val trimmed = rawEmail.trim()
            val (rawLocalPart, rawDomain) = split(trimmed)
            val localPart = canonicalizeLocalPart(rawLocalPart)
            val domain = canonicalizeDomain(rawDomain)
            val canonical = "$localPart@$domain"
            if (canonical.length > MAX_EMAIL_LENGTH) {
                invalidEmail(InvalidEmailReason.ADDRESS_TOO_LONG)
            }
            return canonical
        }

        private fun split(email: String): RawEmailParts {
            if (email.count { it == '@' } != 1) {
                invalidEmail(InvalidEmailReason.INVALID_SEPARATOR)
            }
            val parts = email.split('@', limit = 2)
            return RawEmailParts(localPart = parts[0], domain = parts[1])
        }

        private fun canonicalizeLocalPart(rawLocalPart: String): String {
            when {
                rawLocalPart.isEmpty() -> invalidEmail(InvalidEmailReason.LOCAL_PART_EMPTY)
                rawLocalPart.length > MAX_LOCAL_PART_LENGTH -> invalidEmail(InvalidEmailReason.LOCAL_PART_TOO_LONG)
                !ASCII_LOCAL_PART.matches(rawLocalPart) ->
                    invalidEmail(InvalidEmailReason.LOCAL_PART_HAS_UNSUPPORTED_CHARACTERS)
                rawLocalPart.startsWith('.') || rawLocalPart.endsWith('.') || ".." in rawLocalPart ->
                    invalidEmail(InvalidEmailReason.LOCAL_PART_HAS_INVALID_DOTS)
            }
            return rawLocalPart.lowercase(Locale.ROOT)
        }

        private fun canonicalizeDomain(rawDomain: String): String {
            if (rawDomain.isEmpty()) invalidEmail(InvalidEmailReason.DOMAIN_EMPTY)

            val idnaInfo = IDNA.Info()
            val asciiDomain = IDNA_PROCESSOR.nameToASCII(rawDomain, StringBuilder(), idnaInfo)
                .toString()
                .lowercase(Locale.ROOT)
            when {
                idnaInfo.hasErrors() -> invalidEmail(InvalidEmailReason.DOMAIN_INVALID)
                asciiDomain.isEmpty() -> invalidEmail(InvalidEmailReason.DOMAIN_EMPTY)
                asciiDomain.length > MAX_DOMAIN_LENGTH -> invalidEmail(InvalidEmailReason.DOMAIN_TOO_LONG)
                asciiDomain.startsWith('.') || asciiDomain.endsWith('.') ->
                    invalidEmail(InvalidEmailReason.DOMAIN_INVALID)
            }
            return asciiDomain
        }

        private fun invalidEmail(reason: InvalidEmailReason): Nothing = throw InvalidEmailException(reason)

        private const val MAX_EMAIL_LENGTH = 254
        // Prevent unbounded work in ICU before the canonical DNS length is known.
        private const val MAX_RAW_EMAIL_LENGTH = 1_024
        private const val MAX_LOCAL_PART_LENGTH = 64
        private const val MAX_DOMAIN_LENGTH = 253
    }
}

enum class InvalidEmailReason(val description: String) {
    INVALID_SEPARATOR("Email must contain exactly one @ separator"),
    ADDRESS_TOO_LONG("Email is too long"),
    LOCAL_PART_EMPTY("Email local part must not be empty"),
    LOCAL_PART_TOO_LONG("Email local part is too long"),
    LOCAL_PART_HAS_UNSUPPORTED_CHARACTERS("Email local part contains unsupported characters"),
    LOCAL_PART_HAS_INVALID_DOTS("Email local part has invalid dot placement"),
    DOMAIN_EMPTY("Email domain must not be empty"),
    DOMAIN_TOO_LONG("Email domain is too long"),
    DOMAIN_INVALID("Email domain is invalid"),
}

class InvalidEmailException(val reason: InvalidEmailReason) : InvalidInputException(reason.description)

private data class RawEmailParts(val localPart: String, val domain: String)
