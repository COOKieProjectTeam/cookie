package com.cookie.identity.domain

class RussianEmailAdmissionPolicy {
    fun validate(email: CanonicalEmail) {
        if ('.' !in email.asciiDomain || email.topLevelDomain !in ALLOWED_TOP_LEVEL_DOMAINS) {
            throw EmailDomainNotAllowedException()
        }
    }

    private companion object {
        val ALLOWED_TOP_LEVEL_DOMAINS = setOf("ru", "xn--p1ai")
    }
}

class EmailDomainNotAllowedException : InvalidInputException("Email top-level domain is not supported")
