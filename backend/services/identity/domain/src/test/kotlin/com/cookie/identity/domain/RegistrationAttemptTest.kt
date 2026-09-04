package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class RegistrationAttemptTest {
    private val createdAt = Instant.parse("2026-08-20T10:00:00Z")
    private val firstTokenId = UUID.randomUUID()

    @Test
    fun `both independent proofs are required while attempt and token are live`() {
        val attempt = attempt()

        assertThat(decision(attempt, tokenMatches = true, proofMatches = true, at = createdAt.plusSeconds(1)))
            .isEqualTo(RegistrationTokenDecision.COMPLETE)
        assertThat(decision(attempt, tokenMatches = false, proofMatches = true, at = createdAt.plusSeconds(1)))
            .isEqualTo(RegistrationTokenDecision.INVALID)
        assertThat(decision(attempt, tokenMatches = true, proofMatches = false, at = createdAt.plusSeconds(1)))
            .isEqualTo(RegistrationTokenDecision.INVALID)
    }

    @Test
    fun `token and attempt expiration are explicit and independent from cleanup`() {
        val attempt = attempt(tokenExpiresAt = createdAt.plusSeconds(30), expiresAt = createdAt.plusSeconds(60))

        assertThat(decision(attempt, at = createdAt.plusSeconds(30))).isEqualTo(RegistrationTokenDecision.INVALID)
        assertThat(attempt.requestDecision(requestFingerprint(), createdAt.plusSeconds(59)))
            .isEqualTo(RegistrationRequestDecision.EXACT_RETRY)
        assertThat(attempt.requestDecision(requestFingerprint(), createdAt.plusSeconds(60)))
            .isEqualTo(RegistrationRequestDecision.EXPIRED)
    }

    @Test
    fun `resend adds a child to the same aggregate after cooldown`() {
        val attempt = attempt()
        val secondTokenId = UUID.randomUUID()

        assertThat(attempt.canIssueVerificationToken(createdAt.plusSeconds(9), Duration.ofSeconds(10))).isFalse()
        assertThat(attempt.canIssueVerificationToken(createdAt.plusSeconds(10), Duration.ofSeconds(10))).isTrue()
        val issued = attempt.issueVerificationToken(
            id = secondTokenId,
            verifierHash = hash('d'),
            expiresAt = createdAt.plusSeconds(50),
            cooldown = Duration.ofSeconds(10),
            now = createdAt.plusSeconds(10),
        )

        assertThat(issued.attemptId).isEqualTo(attempt.id)
        assertThat(attempt.verificationTokenSnapshots()).hasSize(2)
        assertThat(attempt.verificationTokenVerifierHash(secondTokenId)).isEqualTo(hash('d'))
        assertThat(attempt.latestVerificationTokenIssuedAt).isEqualTo(createdAt.plusSeconds(10))
    }

    @Test
    fun `completion redeems one child invalidates siblings and scrubs pending secrets`() {
        val attempt = attempt()
        val siblingId = UUID.randomUUID()
        attempt.issueVerificationToken(
            siblingId,
            hash('d'),
            createdAt.plusSeconds(55),
            Duration.ZERO,
            createdAt.plusSeconds(1),
        )
        val accountId = UUID.randomUUID()

        attempt.complete(
            tokenId = firstTokenId,
            tokenVerifierMatches = true,
            registrationProofMatches = true,
            accountId = accountId,
            now = createdAt.plusSeconds(2),
        )

        assertThat(attempt.completedAt).isEqualTo(createdAt.plusSeconds(2))
        assertThat(attempt.activatedAccountId).isEqualTo(accountId)
        assertThat(attempt.pendingPasswordHash).isNull()
        assertThat(attempt.locale).isNull()
        assertThat(attempt.verificationTokenSnapshots().single { it.id == firstTokenId }.redeemedAt)
            .isEqualTo(createdAt.plusSeconds(2))
        assertThat(attempt.verificationTokenSnapshots().single { it.id == siblingId }.redeemedAt).isNull()
        assertThat(decision(attempt, tokenId = siblingId, at = createdAt.plusSeconds(3)))
            .isEqualTo(RegistrationTokenDecision.INVALID)
        assertThatIllegalStateException().isThrownBy { attempt.passwordHashForActivation() }
    }

    @Test
    fun `only the completing token is an exact retry even after every expiry`() {
        val attempt = attempt()
        val siblingId = UUID.randomUUID()
        attempt.issueVerificationToken(
            siblingId,
            hash('d'),
            createdAt.plusSeconds(55),
            Duration.ZERO,
            createdAt.plusSeconds(1),
        )
        attempt.complete(firstTokenId, true, true, UUID.randomUUID(), createdAt.plusSeconds(2))

        assertThat(decision(attempt, tokenId = firstTokenId, at = createdAt.plusSeconds(600)))
            .isEqualTo(RegistrationTokenDecision.EXACT_RETRY)
        assertThat(decision(attempt, tokenId = siblingId, at = createdAt.plusSeconds(600)))
            .isEqualTo(RegistrationTokenDecision.INVALID)
        assertThat(decision(attempt, tokenId = firstTokenId, tokenMatches = false, at = createdAt.plusSeconds(600)))
            .isEqualTo(RegistrationTokenDecision.INVALID)
    }

    @Test
    fun `same proof with a changed request fingerprint is a conflict`() {
        val attempt = attempt()

        assertThat(attempt.requestDecision(requestFingerprint(), createdAt.plusSeconds(1)))
            .isEqualTo(RegistrationRequestDecision.EXACT_RETRY)
        assertThat(attempt.requestDecision(hash('e'), createdAt.plusSeconds(1)))
            .isEqualTo(RegistrationRequestDecision.CONFLICT)
    }

    @Test
    fun `abandonment is idempotent scrubs secrets and preserves retry evidence`() {
        val attempt = attempt()
        val abandonedAt = createdAt.plusSeconds(60)

        attempt.abandon(abandonedAt)
        attempt.abandon(abandonedAt.plusSeconds(1))

        assertThat(attempt.isAbandoned).isTrue()
        assertThat(attempt.abandonedAt).isEqualTo(abandonedAt)
        assertThat(attempt.pendingPasswordHash).isNull()
        assertThat(attempt.locale).isNull()
        assertThat(attempt.requestDecision(requestFingerprint(), abandonedAt.plusSeconds(2)))
            .isEqualTo(RegistrationRequestDecision.ABANDONED)
        assertThat(decision(attempt, at = abandonedAt.plusSeconds(2)))
            .isEqualTo(RegistrationTokenDecision.INVALID)
        assertThat(attempt.canIssueVerificationToken(abandonedAt.plusSeconds(2), Duration.ZERO)).isFalse()
    }

    @Test
    fun `completion rejects an expired token and either mismatched proof`() {
        val attempt = attempt(tokenExpiresAt = createdAt.plusSeconds(10))

        assertThatIllegalStateException().isThrownBy {
            attempt.complete(firstTokenId, true, true, UUID.randomUUID(), createdAt.plusSeconds(10))
        }
        assertThatIllegalStateException().isThrownBy {
            attempt.complete(firstTokenId, false, true, UUID.randomUUID(), createdAt.plusSeconds(1))
        }
        assertThatIllegalStateException().isThrownBy {
            attempt.complete(firstTokenId, true, false, UUID.randomUUID(), createdAt.plusSeconds(1))
        }
    }

    @Test
    fun `reconstitution rejects a completed root without exactly one matching redeemed child`() {
        val attemptId = UUID.randomUUID()
        val completion = createdAt.plusSeconds(1)

        assertThatIllegalArgumentException().isThrownBy {
            RegistrationAttempt.reconstitute(
                id = attemptId,
                email = email(),
                registrationProofHash = proofHash(),
                requestFingerprint = requestFingerprint(),
                locale = null,
                pendingPasswordHash = null,
                expiresAt = createdAt.plusSeconds(60),
                createdAt = createdAt,
                completedAt = completion,
                activatedAccountId = UUID.randomUUID(),
                abandonedAt = null,
                verificationTokens = listOf(
                    RegistrationVerificationToken.reconstitute(
                        id = firstTokenId,
                        attemptId = attemptId,
                        verifierHash = hash('a'),
                        issuedAt = createdAt,
                        expiresAt = createdAt.plusSeconds(30),
                        redeemedAt = null,
                    ),
                ),
            )
        }
    }

    @Test
    fun `reconstitution rejects child tokens outside the aggregate lifetime`() {
        val attemptId = UUID.randomUUID()

        assertThatIllegalArgumentException().isThrownBy {
            RegistrationAttempt.reconstitute(
                id = attemptId,
                email = email(),
                registrationProofHash = proofHash(),
                requestFingerprint = requestFingerprint(),
                locale = LocaleTag.parse("ru-RU"),
                pendingPasswordHash = "password-hash",
                expiresAt = createdAt.plusSeconds(60),
                createdAt = createdAt,
                completedAt = null,
                activatedAccountId = null,
                abandonedAt = null,
                verificationTokens = listOf(
                    RegistrationVerificationToken.reconstitute(
                        id = firstTokenId,
                        attemptId = attemptId,
                        verifierHash = hash('a'),
                        issuedAt = createdAt,
                        expiresAt = createdAt.plusSeconds(61),
                        redeemedAt = null,
                    ),
                ),
            )
        }
    }

    private fun decision(
        attempt: RegistrationAttempt,
        tokenId: UUID = firstTokenId,
        tokenMatches: Boolean = true,
        proofMatches: Boolean = true,
        at: Instant,
    ): RegistrationTokenDecision = attempt.tokenDecision(tokenId, tokenMatches, proofMatches, at)

    private fun attempt(
        tokenExpiresAt: Instant = createdAt.plusSeconds(30),
        expiresAt: Instant = createdAt.plusSeconds(60),
    ): RegistrationAttempt = RegistrationAttempt.start(
        id = UUID.randomUUID(),
        email = email(),
        registrationProofHash = proofHash(),
        requestFingerprint = requestFingerprint(),
        locale = LocaleTag.parse("ru-RU"),
        pendingPasswordHash = "password-hash",
        expiresAt = expiresAt,
        firstTokenId = firstTokenId,
        firstTokenVerifierHash = hash('a'),
        firstTokenExpiresAt = tokenExpiresAt,
        now = createdAt,
    )

    private fun email(): CanonicalEmail = CanonicalEmail.parse("user@example.ru")

    private fun proofHash(): VerifierHash = hash('b')

    private fun requestFingerprint(): VerifierHash = hash('c')

    private fun hash(character: Char): VerifierHash = VerifierHash.fromSha256Hex(character.toString().repeat(64))
}
