package com.cookie.identity.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class RawSecretToken(val id: UUID, val value: String, val verifierHash: String)

data class ParsedSecretToken(val id: UUID, val secret: String, val verifierHash: String)

class SecretTokens(private val random: SecureRandom) {
    fun create(id: UUID): RawSecretToken {
        val secretBytes = ByteArray(32).also(random::nextBytes)
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes)
        return RawSecretToken(id, "v1.$id.$secret", hash(secret))
    }

    fun parse(value: String): ParsedSecretToken {
        val parts = value.split('.', limit = 3)
        if (parts.size != 3 || parts[0] != "v1" || parts[2].length != 43) {
            throw InvalidTokenException()
        }
        val id = try {
            UUID.fromString(parts[1])
        } catch (_: IllegalArgumentException) {
            throw InvalidTokenException()
        }
        try {
            if (Base64.getUrlDecoder().decode(parts[2]).size != 32) throw InvalidTokenException()
        } catch (_: IllegalArgumentException) {
            throw InvalidTokenException()
        }
        return ParsedSecretToken(id, parts[2], hash(parts[2]))
    }

    fun verifierMatches(expectedHex: String, actualHex: String): Boolean =
        MessageDigest.isEqual(
            expectedHex.toByteArray(StandardCharsets.US_ASCII),
            actualHex.toByteArray(StandardCharsets.US_ASCII),
        )

    private fun hash(secret: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(secret.toByteArray(StandardCharsets.US_ASCII))
            .joinToString("") { byte -> "%02x".format(byte) }
}
