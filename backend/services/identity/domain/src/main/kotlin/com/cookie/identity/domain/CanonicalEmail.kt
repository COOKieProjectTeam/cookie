package com.cookie.identity.domain

import com.ibm.icu.text.IDNA
import java.text.Normalizer
import java.util.Locale

class CanonicalEmail private constructor(val value: String) {
    override fun equals(other: Any?): Boolean = other is CanonicalEmail && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "[redacted-email]"

    companion object {
        private val ASCII_LOCAL_PART = Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+")
        private val ALLOWED_TLDS = setOf("ru", "xn--p1ai")
        private val IDNA_PROCESSOR = IDNA.getUTS46Instance(
            IDNA.NONTRANSITIONAL_TO_ASCII or IDNA.USE_STD3_RULES or
                IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ or IDNA.CHECK_CONTEXTO,
        )

        fun parse(rawEmail: String): CanonicalEmail {
            val canonical = canonicalize(rawEmail)
            val tld = canonical.substringAfterLast('.', missingDelimiterValue = "")
            if (tld !in ALLOWED_TLDS) {
                throw InvalidInputException("Only Russian email domains are supported")
            }
            return CanonicalEmail(canonical)
        }

        fun reconstitute(canonicalEmail: String): CanonicalEmail {
            val normalized = try {
                canonicalize(canonicalEmail)
            } catch (exception: InvalidInputException) {
                throw IllegalStateException("Stored email is not structurally valid", exception)
            }
            check(normalized == canonicalEmail) { "Stored email is not canonical" }
            return CanonicalEmail(normalized)
        }

        private fun canonicalize(rawEmail: String): String {
            val normalized = Normalizer.normalize(rawEmail.trim(), Normalizer.Form.NFC)
            if (normalized.length > MAX_EMAIL_LENGTH || normalized.count { it == '@' } != 1) {
                throw InvalidInputException("Invalid email")
            }

            val (rawLocal, rawDomain) = normalized.split('@', limit = 2)
            if (
                !ASCII_LOCAL_PART.matches(rawLocal) || rawLocal.length > MAX_LOCAL_PART_LENGTH ||
                rawLocal.startsWith('.') || rawLocal.endsWith('.') || ".." in rawLocal
            ) {
                throw InvalidInputException("Invalid email")
            }

            val idnaInfo = IDNA.Info()
            val asciiDomain = IDNA_PROCESSOR.nameToASCII(rawDomain, StringBuilder(), idnaInfo)
                .toString()
                .lowercase(Locale.ROOT)
            if (idnaInfo.hasErrors()) {
                throw InvalidInputException("Invalid email")
            }
            if (
                asciiDomain.isEmpty() || asciiDomain.length > MAX_DOMAIN_LENGTH ||
                asciiDomain.startsWith('.') || asciiDomain.endsWith('.')
            ) {
                throw InvalidInputException("Invalid email")
            }

            val canonical = "${rawLocal.lowercase(Locale.ROOT)}@$asciiDomain"
            if (canonical.length > MAX_EMAIL_LENGTH) {
                throw InvalidInputException("Invalid email")
            }
            return canonical
        }

        private const val MAX_EMAIL_LENGTH = 254
        private const val MAX_LOCAL_PART_LENGTH = 64
        private const val MAX_DOMAIN_LENGTH = 253
    }
}
