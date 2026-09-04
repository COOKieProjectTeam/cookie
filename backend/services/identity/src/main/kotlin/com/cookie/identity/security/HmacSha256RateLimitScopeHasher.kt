package com.cookie.identity.security

import com.cookie.identity.application.ports.RateLimitScopeHasher
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pseudonymises low-entropy rate-limit dimensions without making IPv4 or email
 * values recoverable through an unkeyed dictionary attack.
 */
class HmacSha256RateLimitScopeHasher(encodedKey: String) : RateLimitScopeHasher {
    private val key = decodeKey(encodedKey).let { keyBytes ->
        try {
            SecretKeySpec(keyBytes, HMAC_SHA_256)
        } finally {
            keyBytes.fill(0)
        }
    }

    override fun hash(namespace: String, value: String): String {
        require(NAMESPACE.matches(namespace)) { "Invalid rate-limit hash namespace" }
        require(value.isNotEmpty()) { "Rate-limit hash value must not be empty" }

        val valueBytes = value.toByteArray(StandardCharsets.UTF_8)
        return try {
            val mac = Mac.getInstance(HMAC_SHA_256)
            mac.init(key)
            mac.update(CONTEXT)
            mac.update(namespace.toByteArray(StandardCharsets.US_ASCII))
            mac.update(SEPARATOR)
            HexFormat.of().formatHex(mac.doFinal(valueBytes)).take(STORED_HEX_LENGTH)
        } finally {
            valueBytes.fill(0)
        }
    }

    override fun toString(): String = "HmacSha256RateLimitScopeHasher(key=[redacted])"

    private fun decodeKey(encodedKey: String): ByteArray {
        if (encodedKey.length != ENCODED_KEY_LENGTH || !BASE64_URL.matches(encodedKey)) {
            throw IllegalArgumentException("Rate-limit HMAC key must be a canonical base64url-encoded 32-byte secret")
        }
        val decoded = try {
            Base64.getUrlDecoder().decode(encodedKey)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Rate-limit HMAC key must be a canonical base64url-encoded 32-byte secret")
        }
        if (
            decoded.size != KEY_BYTES ||
            Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != encodedKey
        ) {
            decoded.fill(0)
            throw IllegalArgumentException("Rate-limit HMAC key must be a canonical base64url-encoded 32-byte secret")
        }
        return decoded
    }

    private companion object {
        const val HMAC_SHA_256 = "HmacSHA256"
        const val KEY_BYTES = 32
        const val ENCODED_KEY_LENGTH = 43
        const val STORED_HEX_LENGTH = 32
        const val SEPARATOR: Byte = 0
        val BASE64_URL = Regex("[A-Za-z0-9_-]+")
        val NAMESPACE = Regex("[a-z][a-z0-9-]{0,31}")
        val CONTEXT = "cookie.identity.rate-limit.v1\u0000".toByteArray(StandardCharsets.US_ASCII)
    }
}
