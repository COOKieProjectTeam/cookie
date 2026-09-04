package com.cookie.identity.domain

/**
 * A SHA-256 digest of the secret part of an opaque token.
 *
 * Keeping the digest in a dedicated value object prevents entities from accepting an
 * arbitrary string where a verifier hash is required.
 */
@JvmInline
value class VerifierHash private constructor(val value: String) {
    init {
        require(SHA_256_LOWERCASE_HEX.matches(value)) {
            "Verifier hash must be a lowercase SHA-256 hex value"
        }
    }

    override fun toString(): String = "VerifierHash([redacted])"

    companion object {
        private val SHA_256_LOWERCASE_HEX = Regex("[0-9a-f]{64}")

        fun fromSha256Hex(value: String): VerifierHash = VerifierHash(value)
    }
}
