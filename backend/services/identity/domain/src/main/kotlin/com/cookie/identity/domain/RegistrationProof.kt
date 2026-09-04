package com.cookie.identity.domain

import java.util.Base64

/**
 * A client-held half of the registration proof. The email token is delivered
 * over a different channel; neither half can activate a credential alone.
 */
class RegistrationProof private constructor(val value: String) {
    override fun toString(): String = "[redacted-registration-proof]"

    companion object {
        fun parse(rawProof: String): RegistrationProof {
            if (rawProof.length != ENCODED_LENGTH || !BASE64_URL.matches(rawProof)) {
                throw InvalidRegistrationProofException()
            }
            val bytes = try {
                Base64.getUrlDecoder().decode(rawProof)
            } catch (_: IllegalArgumentException) {
                throw InvalidRegistrationProofException()
            }
            try {
                if (bytes.size != SECRET_BYTES || Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != rawProof) {
                    throw InvalidRegistrationProofException()
                }
            } finally {
                bytes.fill(0)
            }
            return RegistrationProof(rawProof)
        }

        private val BASE64_URL = Regex("[A-Za-z0-9_-]+")
        private const val SECRET_BYTES = 32
        const val ENCODED_LENGTH = 43
    }
}

class InvalidRegistrationProofException : InvalidInputException("Invalid registration proof")
