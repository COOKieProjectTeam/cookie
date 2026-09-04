package com.cookie.identity.security

import com.cookie.identity.application.InvalidTokenException
import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.domain.LocaleTag
import com.cookie.identity.domain.NormalizedPassword
import com.cookie.identity.domain.PasswordPolicy
import com.cookie.identity.domain.RegistrationProof
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.UUID

class Sha256SecretTokenServiceTest {
    private val tokens = Sha256SecretTokenService(SecureRandom())

    @Test
    fun `round trips token id and verifier without exposing or storing raw secret`() {
        val id = UUID.fromString("0198c4a5-68b5-7def-8123-456789abcdef")
        val created = tokens.create(id)
        val parsed = tokens.parse(created.value)

        assertThat(created.value).startsWith("v1.$id.")
        assertThat(parsed.id).isEqualTo(id)
        assertThat(tokens.verifierMatches(created.verifierHash, parsed.verifierHash)).isTrue()
        assertThat(created.verifierHash.value).doesNotContain(created.value.substringAfterLast('.'))
        assertThat(created.toString()).doesNotContain(created.value)
        assertThat(parsed.toString()).doesNotContain(created.value.substringAfterLast('.'))

        val another = tokens.create(UUID.randomUUID())
        assertThat(tokens.verifierMatches(created.verifierHash, another.verifierHash)).isFalse()
    }

    @Test
    fun `rejects malformed tokens`() {
        listOf(
            "not-a-token",
            "v2.0198c4a5-68b5-7def-8123-456789abcdef.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "v1.not-a-uuid.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "v1.0198c4a5-68b5-7def-8123-456789abcdef.invalid.invalid",
        ).forEach { malformed ->
            assertThatThrownBy { tokens.parse(malformed) }
                .isInstanceOf(InvalidTokenException::class.java)
        }
    }

    @Test
    fun `round trips an email token bound to both registration and token ids`() {
        val attemptId = UUID.fromString("0198c4a5-68b5-7def-8123-456789abcdef")
        val tokenId = UUID.fromString("0198c4a5-68b5-7def-9234-56789abcdef0")

        val created = tokens.createEmailVerificationToken(attemptId, tokenId)
        val parsed = tokens.parseEmailVerificationToken(created.value)

        assertThat(created.value).startsWith("v1e.$attemptId.$tokenId.")
        assertThat(created.registrationAttemptId).isEqualTo(attemptId)
        assertThat(created.tokenId).isEqualTo(tokenId)
        assertThat(parsed.registrationAttemptId).isEqualTo(attemptId)
        assertThat(parsed.tokenId).isEqualTo(tokenId)
        assertThat(tokens.verifierMatches(created.verifierHash, parsed.verifierHash)).isTrue()
    }

    @Test
    fun `email and refresh token formats cannot be confused and noncanonical secrets are rejected`() {
        val attemptId = UUID.fromString("0198c4a5-68b5-7def-8123-456789abcdef")
        val tokenId = UUID.fromString("0198c4a5-68b5-7def-9234-56789abcdef0")
        val emailToken = tokens.createEmailVerificationToken(attemptId, tokenId)
        val refreshToken = tokens.create(tokenId)
        val noncanonicalSecret = "${"A".repeat(42)}B"
        val noncanonicalEmailToken = "v1e.$attemptId.$tokenId.$noncanonicalSecret"
        val noncanonicalAttemptId = emailToken.value.replace(attemptId.toString(), attemptId.toString().uppercase())
        val noncanonicalRefreshId = refreshToken.value.replace(tokenId.toString(), tokenId.toString().uppercase())

        listOf(
            { tokens.parse(emailToken.value) },
            { tokens.parseEmailVerificationToken(refreshToken.value) },
            { tokens.parseEmailVerificationToken(noncanonicalEmailToken) },
            { tokens.parseEmailVerificationToken(noncanonicalAttemptId) },
            { tokens.parse(noncanonicalRefreshId) },
        ).forEach { parse ->
            assertThatThrownBy { parse() }.isInstanceOf(InvalidTokenException::class.java)
        }
    }

    @Test
    fun `registration fingerprint is deterministic and binds every request field`() {
        val proof = RegistrationProof.parse("A".repeat(RegistrationProof.ENCODED_LENGTH))
        val attemptId = UUID.fromString("0198c4a5-68b5-7def-8123-456789abcdef")
        val email = CanonicalEmail.parse("user@example.ru")
        val password = PasswordPolicy().prepareForRegistration("correct horse battery staple")
        val locale = LocaleTag.parse("ru-RU")

        fun fingerprint(
            candidateAttemptId: UUID = attemptId,
            candidateEmail: CanonicalEmail = email,
            candidatePassword: NormalizedPassword = password,
            candidateLocale: LocaleTag? = locale,
        ) = tokens.registrationRequestFingerprint(
            proof,
            candidateAttemptId,
            candidateEmail,
            candidatePassword,
            candidateLocale,
        )

        val expected = fingerprint()

        assertThat(fingerprint()).isEqualTo(expected)
        assertThat(fingerprint(candidateAttemptId = UUID.fromString("0198c4a5-68b5-7def-a345-6789abcdef01")))
            .isNotEqualTo(expected)
        assertThat(fingerprint(candidateEmail = CanonicalEmail.parse("other@example.ru")))
            .isNotEqualTo(expected)
        assertThat(
            fingerprint(
                candidatePassword = PasswordPolicy().prepareForRegistration("correct horse battery staplf"),
            ),
        ).isNotEqualTo(expected)
        assertThat(fingerprint(candidateLocale = LocaleTag.parse("en-US"))).isNotEqualTo(expected)
    }

    @Test
    fun `registration secrets never leak through string representations`() {
        val proof = RegistrationProof.parse("A".repeat(RegistrationProof.ENCODED_LENGTH))
        val attemptId = UUID.fromString("0198c4a5-68b5-7def-8123-456789abcdef")
        val tokenId = UUID.fromString("0198c4a5-68b5-7def-9234-56789abcdef0")
        val created = tokens.createEmailVerificationToken(attemptId, tokenId)
        val parsed = tokens.parseEmailVerificationToken(created.value)
        val rawSecret = created.value.substringAfterLast('.')

        assertThat(proof.toString()).doesNotContain(proof.value)
        assertThat(created.toString()).doesNotContain(created.value, rawSecret)
        assertThat(parsed.toString()).doesNotContain(rawSecret)
        assertThat(created.verifierHash.toString()).doesNotContain(created.verifierHash.value)
        assertThat(parsed.verifierHash.toString()).doesNotContain(parsed.verifierHash.value)
    }

    @Test
    fun `derives the same refresh successor only for the same logical request`() {
        val predecessor = tokens.create(UUID.randomUUID())
        val replacementId = UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID()

        val first = tokens.createRefreshSuccessor(predecessor.value, replacementId, idempotencyKey)
        val retry = tokens.createRefreshSuccessor(predecessor.value, replacementId, idempotencyKey)
        val anotherRequest = tokens.createRefreshSuccessor(predecessor.value, replacementId, UUID.randomUUID())

        assertThat(retry.value).isEqualTo(first.value)
        assertThat(retry.verifierHash).isEqualTo(first.verifierHash)
        assertThat(anotherRequest.value).isNotEqualTo(first.value)
        assertThat(tokens.parse(first.value).id).isEqualTo(replacementId)
        assertThat(first.value).doesNotContain(predecessor.value.substringAfterLast('.'))
    }
}
