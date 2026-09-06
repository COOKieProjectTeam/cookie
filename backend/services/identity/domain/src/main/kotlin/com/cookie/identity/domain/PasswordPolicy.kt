package com.cookie.identity.domain

import java.text.Normalizer

class PasswordPolicy {
    fun prepareForRegistration(rawPassword: String): NormalizedPassword =
        normalizeAndValidate(rawPassword, minimumCodePoints = MIN_REGISTRATION_CODE_POINTS)

    fun prepareForAuthentication(rawPassword: String): NormalizedPassword =
        normalizeAndValidate(rawPassword, minimumCodePoints = MIN_AUTHENTICATION_CODE_POINTS)

    private fun normalizeAndValidate(rawPassword: String, minimumCodePoints: Int): NormalizedPassword {
        // Bound work before Unicode traversal and normalization. The normalized
        // password still has the stricter code-point limit below.
        if (rawPassword.length > MAX_RAW_CODE_UNITS) {
            throw InvalidPasswordException(InvalidPasswordReason.TOO_LONG)
        }
        if (rawPassword.hasUnpairedSurrogate()) {
            throw InvalidPasswordException(InvalidPasswordReason.MALFORMED_UNICODE)
        }

        val normalized = Normalizer.normalize(rawPassword, Normalizer.Form.NFC)
        val codePointCount = normalized.codePointCount(0, normalized.length)
        when {
            codePointCount < minimumCodePoints -> throw InvalidPasswordException(InvalidPasswordReason.TOO_SHORT)
            codePointCount > MAX_CODE_POINTS -> throw InvalidPasswordException(InvalidPasswordReason.TOO_LONG)
        }

        normalized.codePoints().forEach { codePoint ->
            val type = Character.getType(codePoint)
            if (
                Character.isISOControl(codePoint) ||
                type == Character.LINE_SEPARATOR.toInt() ||
                type == Character.PARAGRAPH_SEPARATOR.toInt()
            ) {
                throw InvalidPasswordException(InvalidPasswordReason.CONTROL_CHARACTER)
            }
        }

        return NormalizedPassword(normalized)
    }

    companion object {
        const val MIN_REGISTRATION_CODE_POINTS = 15
        const val MIN_AUTHENTICATION_CODE_POINTS = 1
        const val MAX_CODE_POINTS = 128
        const val MAX_RAW_CODE_UNITS = 1024
    }
}

class NormalizedPassword internal constructor(val value: String) {
    override fun toString(): String = "[redacted-password]"
}

enum class InvalidPasswordReason(val description: String) {
    TOO_SHORT("Password is too short"),
    TOO_LONG("Password is too long"),
    MALFORMED_UNICODE("Password contains malformed Unicode"),
    CONTROL_CHARACTER("Password contains a control or line-separator character"),
}

class InvalidPasswordException(val reason: InvalidPasswordReason) : InvalidInputException(reason.description)

private fun String.hasUnpairedSurrogate(): Boolean {
    var index = 0
    while (index < length) {
        val character = this[index]
        when {
            Character.isHighSurrogate(character) -> {
                if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return true
                index += 2
            }
            Character.isLowSurrogate(character) -> return true
            else -> index += 1
        }
    }
    return false
}
