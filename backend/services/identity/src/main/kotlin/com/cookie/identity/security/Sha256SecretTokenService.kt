package com.cookie.identity.security

import com.cookie.identity.application.GeneratedSecretToken
import com.cookie.identity.application.InvalidTokenException
import com.cookie.identity.application.ParsedSecretToken
import com.cookie.identity.application.ports.SecretTokenService
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

@Component
class Sha256SecretTokenService(private val random: SecureRandom) : SecretTokenService {
    override fun create(id: UUID): GeneratedSecretToken {
        val secretBytes = ByteArray(SECRET_BYTES).also(random::nextBytes)
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes)
        return GeneratedSecretToken(id, "$TOKEN_VERSION.$id.$secret", hash(secret))
    }

    override fun parse(value: String): ParsedSecretToken {
        if (value.length != TOKEN_LENGTH) throw InvalidTokenException()
        val parts = value.split('.', limit = TOKEN_PARTS)
        if (parts.size != TOKEN_PARTS || parts[0] != TOKEN_VERSION || parts[2].length != ENCODED_SECRET_LENGTH) {
            throw InvalidTokenException()
        }
        val id = try {
            UUID.fromString(parts[1])
        } catch (_: IllegalArgumentException) {
            throw InvalidTokenException()
        }
        try {
            if (Base64.getUrlDecoder().decode(parts[2]).size != SECRET_BYTES) throw InvalidTokenException()
        } catch (_: IllegalArgumentException) {
            throw InvalidTokenException()
        }
        return ParsedSecretToken(id, hash(parts[2]))
    }

    override fun verifierMatches(expectedHex: String, actualHex: String): Boolean =
        MessageDigest.isEqual(
            expectedHex.toByteArray(StandardCharsets.US_ASCII),
            actualHex.toByteArray(StandardCharsets.US_ASCII),
        )

    private fun hash(secret: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(StandardCharsets.US_ASCII)),
        )

    companion object {
        private const val TOKEN_VERSION = "v1"
        private const val TOKEN_PARTS = 3
        private const val SECRET_BYTES = 32
        private const val ENCODED_SECRET_LENGTH = 43
        private const val TOKEN_LENGTH = 83
    }
}
