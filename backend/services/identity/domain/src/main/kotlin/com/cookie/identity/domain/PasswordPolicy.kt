package com.cookie.identity.domain

class PasswordPolicy {
    fun validate(password: String) {
        val codePointCount = password.codePointCount(0, password.length)
        if (codePointCount !in MIN_CODE_POINTS..MAX_CODE_POINTS) {
            throw InvalidInputException("Password must contain 15 to 128 Unicode code points")
        }

        password.codePoints().forEach { codePoint ->
            val type = Character.getType(codePoint)
            if (
                Character.isWhitespace(codePoint) ||
                Character.isSpaceChar(codePoint) ||
                Character.isISOControl(codePoint) ||
                type == Character.FORMAT.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() ||
                type == Character.PARAGRAPH_SEPARATOR.toInt()
            ) {
                throw InvalidInputException("Password must not contain whitespace or control characters")
            }
        }
    }

    companion object {
        const val MIN_CODE_POINTS = 15
        const val MAX_CODE_POINTS = 128
    }
}
