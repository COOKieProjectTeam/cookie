package com.cookie.identity.security

import com.cookie.identity.application.GeneratedSecretToken
import com.cookie.identity.application.GeneratedEmailVerificationToken
import com.cookie.identity.application.InvalidTokenException
import com.cookie.identity.application.ParsedEmailVerificationToken
import com.cookie.identity.application.ParsedSecretToken
import com.cookie.identity.application.ports.RefreshTokenService
import com.cookie.identity.application.ports.RegistrationSecretService
import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.domain.LocaleTag
import com.cookie.identity.domain.NormalizedPassword
import com.cookie.identity.domain.RegistrationProof
import com.cookie.identity.domain.VerifierHash
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class Sha256SecretTokenService(private val random: SecureRandom) : RefreshTokenService, RegistrationSecretService {
    override fun create(id: UUID): GeneratedSecretToken {
        val secretBytes = ByteArray(SECRET_BYTES).also(random::nextBytes)
        return try {
            token(id, secretBytes)
        } finally {
            secretBytes.fill(0)
        }
    }

    override fun createRefreshSuccessor(
        predecessorRawToken: String,
        replacementId: UUID,
        idempotencyKey: UUID,
    ): GeneratedSecretToken {
        val predecessor = decode(predecessorRawToken)
        val derivationInput = ByteBuffer.allocate(SUCCESSOR_CONTEXT.size + UUID_BYTES * 3)
            .put(SUCCESSOR_CONTEXT)
            .putUuid(predecessor.id)
            .putUuid(replacementId)
            .putUuid(idempotencyKey)
            .array()
        val mac = Mac.getInstance(HMAC_SHA_256)
        val successorBytes = try {
            mac.init(SecretKeySpec(predecessor.secretBytes, HMAC_SHA_256))
            mac.doFinal(derivationInput)
        } finally {
            predecessor.secretBytes.fill(0)
            derivationInput.fill(0)
        }
        return try {
            token(replacementId, successorBytes)
        } finally {
            successorBytes.fill(0)
        }
    }

    override fun parse(value: String): ParsedSecretToken {
        val decoded = decode(value)
        return try {
            ParsedSecretToken(decoded.id, hash(decoded.encodedSecret))
        } finally {
            decoded.secretBytes.fill(0)
        }
    }

    override fun createEmailVerificationToken(
        attemptId: UUID,
        tokenId: UUID,
    ): GeneratedEmailVerificationToken {
        val secretBytes = ByteArray(SECRET_BYTES).also(random::nextBytes)
        return try {
            val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes)
            GeneratedEmailVerificationToken(
                registrationAttemptId = attemptId,
                tokenId = tokenId,
                value = "$EMAIL_TOKEN_VERSION.$attemptId.$tokenId.$secret",
                verifierHash = hash(secret),
            )
        } finally {
            secretBytes.fill(0)
        }
    }

    override fun parseEmailVerificationToken(value: String): ParsedEmailVerificationToken {
        if (value.length != EMAIL_TOKEN_LENGTH) throw InvalidTokenException()
        val parts = value.split('.', limit = EMAIL_TOKEN_PARTS)
        if (
            parts.size != EMAIL_TOKEN_PARTS ||
            parts[0] != EMAIL_TOKEN_VERSION ||
            parts[3].length != ENCODED_SECRET_LENGTH
        ) {
            throw InvalidTokenException()
        }
        val attemptId = parseUuid(parts[1])
        val tokenId = parseUuid(parts[2])
        val secretBytes = decodeSecret(parts[3])
        return try {
            ParsedEmailVerificationToken(attemptId, tokenId, hash(parts[3]))
        } finally {
            secretBytes.fill(0)
        }
    }

    override fun hashRegistrationProof(proof: RegistrationProof): VerifierHash = hash(proof.value)

    override fun registrationRequestFingerprint(
        proof: RegistrationProof,
        attemptId: UUID,
        email: CanonicalEmail,
        password: NormalizedPassword,
        locale: LocaleTag?,
    ): VerifierHash {
        val proofBytes = Base64.getUrlDecoder().decode(proof.value)
        val emailBytes = email.value.toByteArray(StandardCharsets.US_ASCII)
        val passwordBytes = password.value.toByteArray(StandardCharsets.UTF_8)
        val localeBytes = locale?.value?.toByteArray(StandardCharsets.US_ASCII) ?: ByteArray(0)
        val mac = Mac.getInstance(HMAC_SHA_256)
        return try {
            mac.init(SecretKeySpec(proofBytes, HMAC_SHA_256))
            mac.update(REGISTRATION_FINGERPRINT_CONTEXT)
            mac.updateUuid(attemptId)
            mac.updateField(emailBytes)
            mac.updateField(passwordBytes)
            mac.updateField(localeBytes)
            VerifierHash.fromSha256Hex(HexFormat.of().formatHex(mac.doFinal()))
        } finally {
            proofBytes.fill(0)
            emailBytes.fill(0)
            passwordBytes.fill(0)
            localeBytes.fill(0)
        }
    }

    private fun decode(value: String): DecodedToken {
        if (value.length != TOKEN_LENGTH) throw InvalidTokenException()
        val parts = value.split('.', limit = TOKEN_PARTS)
        if (parts.size != TOKEN_PARTS || parts[0] != TOKEN_VERSION || parts[2].length != ENCODED_SECRET_LENGTH) {
            throw InvalidTokenException()
        }
        val id = parseUuid(parts[1])
        val secretBytes = decodeSecret(parts[2])
        return DecodedToken(id, parts[2], secretBytes)
    }

    private fun parseUuid(value: String): UUID {
        if (value.length != UUID_TEXT_LENGTH) throw InvalidTokenException()
        val parsed = try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            throw InvalidTokenException()
        }
        if (parsed.toString() != value) throw InvalidTokenException()
        return parsed
    }

    private fun decodeSecret(encodedSecret: String): ByteArray = try {
        Base64.getUrlDecoder().decode(encodedSecret).also {
            if (it.size != SECRET_BYTES || Base64.getUrlEncoder().withoutPadding().encodeToString(it) != encodedSecret) {
                throw InvalidTokenException()
            }
        }
    } catch (_: IllegalArgumentException) {
        throw InvalidTokenException()
    }

    override fun verifierMatches(expected: VerifierHash, actual: VerifierHash): Boolean =
        MessageDigest.isEqual(
            expected.value.toByteArray(StandardCharsets.US_ASCII),
            actual.value.toByteArray(StandardCharsets.US_ASCII),
        )

    private fun hash(secret: String): VerifierHash =
        VerifierHash.fromSha256Hex(
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(StandardCharsets.US_ASCII)),
            ),
        )

    private fun token(id: UUID, secretBytes: ByteArray): GeneratedSecretToken {
        check(secretBytes.size == SECRET_BYTES) { "Opaque token secret must contain 256 bits" }
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes)
        return GeneratedSecretToken(id, "$TOKEN_VERSION.$id.$secret", hash(secret))
    }

    private fun ByteBuffer.putUuid(value: UUID): ByteBuffer =
        putLong(value.mostSignificantBits).putLong(value.leastSignificantBits)

    private fun Mac.updateUuid(value: UUID) {
        val bytes = ByteBuffer.allocate(UUID_BYTES).putUuid(value).array()
        try {
            update(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun Mac.updateField(value: ByteArray) {
        val length = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array()
        try {
            update(length)
            update(value)
        } finally {
            length.fill(0)
        }
    }

    private class DecodedToken(
        val id: UUID,
        val encodedSecret: String,
        val secretBytes: ByteArray,
    )

    companion object {
        private val SUCCESSOR_CONTEXT = "cookie.identity.refresh.successor.v1\u0000"
            .toByteArray(StandardCharsets.US_ASCII)
        private val REGISTRATION_FINGERPRINT_CONTEXT = "cookie.identity.registration.request.v1\u0000"
            .toByteArray(StandardCharsets.US_ASCII)
        private const val HMAC_SHA_256 = "HmacSHA256"
        private const val EMAIL_TOKEN_VERSION = "v1e"
        private const val EMAIL_TOKEN_PARTS = 4
        private const val EMAIL_TOKEN_LENGTH = 121
        private const val TOKEN_VERSION = "v1"
        private const val TOKEN_PARTS = 3
        private const val SECRET_BYTES = 32
        private const val ENCODED_SECRET_LENGTH = 43
        private const val TOKEN_LENGTH = 83
        private const val UUID_BYTES = 16
        private const val UUID_TEXT_LENGTH = 36
    }
}
