package com.cookie.identity.domain

import java.net.IDN
import java.text.Normalizer
import java.util.Locale

class EmailCanonicalizer {
    fun canonicalize(rawEmail: String): String {
        val normalized = Normalizer.normalize(rawEmail.trim(), Normalizer.Form.NFC)
        if (normalized.length > 254 || normalized.count { it == '@' } != 1) {
            throw InvalidInputException("Invalid email")
        }

        val (rawLocal, rawDomain) = normalized.split('@', limit = 2)
        if (
            !ASCII_LOCAL_PART.matches(rawLocal) || rawLocal.length > 64 ||
            rawLocal.startsWith('.') || rawLocal.endsWith('.') || ".." in rawLocal
        ) {
            throw InvalidInputException("Invalid email")
        }

        val asciiDomain = try {
            IDN.toASCII(rawDomain, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        } catch (_: IllegalArgumentException) {
            throw InvalidInputException("Invalid email")
        }
        if (asciiDomain.length > 253 || asciiDomain.startsWith('.') || asciiDomain.endsWith('.')) {
            throw InvalidInputException("Invalid email")
        }

        val tld = asciiDomain.substringAfterLast('.', missingDelimiterValue = "")
        if (tld !in ALLOWED_TLDS) {
            throw InvalidInputException("Only Russian email domains are supported")
        }

        return "${rawLocal.lowercase(Locale.ROOT)}@$asciiDomain"
    }

    companion object {
        private val ASCII_LOCAL_PART = Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+")
        private val ALLOWED_TLDS = setOf("ru", "xn--p1ai")
    }
}
