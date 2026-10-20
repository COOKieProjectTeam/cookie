package com.cookie.identity.domain

/**
 * An opaque client-installation identifier. It is compared exactly as supplied;
 * unlike a display name, it is not trimmed or Unicode-normalized.
 */
@JvmInline
value class DeviceId private constructor(val value: String) {
    override fun toString(): String = "DeviceId([redacted])"

    companion object {
        const val MAX_CODE_POINTS = 255

        fun parseOrNull(rawValue: String?): DeviceId? = rawValue?.let(::parse)

        fun parse(rawValue: String): DeviceId {
            if (!isValid(rawValue)) throw InvalidDeviceIdException()
            return DeviceId(rawValue)
        }

        fun reconstitute(value: String): DeviceId {
            check(isValid(value)) { "Stored device id is invalid" }
            return DeviceId(value)
        }

        private fun isValid(value: String): Boolean {
            if (value.isEmpty() || value.length > MAX_CODE_UNITS) return false
            if (value.isBlank() || value.codePointCount(0, value.length) > MAX_CODE_POINTS) return false
            return value.codePoints().noneMatch { codePoint ->
                val type = Character.getType(codePoint)
                Character.isISOControl(codePoint) ||
                    type == Character.FORMAT.toInt() ||
                    type == Character.LINE_SEPARATOR.toInt() ||
                    type == Character.PARAGRAPH_SEPARATOR.toInt() ||
                    type == Character.SURROGATE.toInt()
            }
        }

        private const val MAX_CODE_UNITS = MAX_CODE_POINTS * 2
    }
}

class InvalidDeviceIdException : InvalidInputException("Invalid device id")
