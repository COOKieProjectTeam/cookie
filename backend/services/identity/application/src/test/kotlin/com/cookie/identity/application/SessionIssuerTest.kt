package com.cookie.identity.application

import com.cookie.identity.application.ports.AccessTokenProvider
import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.application.ports.RefreshFamilyRepository
import com.cookie.identity.application.ports.RefreshTokenService
import com.cookie.identity.domain.DeviceId
import com.cookie.identity.domain.RefreshFamily
import com.cookie.identity.domain.VerifierHash
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID

class SessionIssuerTest {
    @Test
    fun `new refresh family retains the typed device identifier`() {
        val familyId = UUID.randomUUID()
        val credentialId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val deviceId = DeviceId.parse("ios-installation-123")
        val families = CapturingFamilies()
        val issuer = SessionIssuer(
            families = families,
            tokens = FixedTokens,
            ids = SequenceIds(familyId, credentialId),
            accessTokens = FixedAccessTokens,
            policy = IdentityPolicy(
                refreshFamilyTtl = Duration.ofDays(30),
                registrationAttemptTtl = Duration.ofHours(24),
                verificationTokenTtl = Duration.ofMinutes(30),
                verificationResendCooldown = Duration.ofMinutes(1),
            ),
        )

        issuer.createFamily(accountId, deviceId, NOW)

        assertThat(families.added?.id).isEqualTo(familyId)
        assertThat(families.added?.accountId).isEqualTo(accountId)
        assertThat(families.added?.deviceId).isEqualTo(deviceId)
    }

    private class CapturingFamilies : RefreshFamilyRepository {
        var added: RefreshFamily? = null
            private set

        override fun findCredentialLookup(id: UUID): RefreshCredentialLookup? = error("Not used")
        override fun findByCredentialIdForUpdate(credentialId: UUID): RefreshFamily? = error("Not used")
        override fun add(family: RefreshFamily) {
            check(added == null)
            added = family
        }
        override fun save(family: RefreshFamily) = error("Not used")
    }

    private class SequenceIds(vararg values: UUID) : IdGenerator {
        private val remaining = ArrayDeque(values.toList())

        override fun next(): UUID = remaining.removeFirst()
    }

    private data object FixedTokens : RefreshTokenService {
        override fun create(id: UUID) = GeneratedSecretToken(id, "refresh-token", HASH)
        override fun createRefreshSuccessor(
            predecessorRawToken: String,
            replacementId: UUID,
            idempotencyKey: UUID,
        ): GeneratedSecretToken = error("Not used")
        override fun parse(value: String): ParsedSecretToken = error("Not used")
        override fun verifierMatches(expected: VerifierHash, actual: VerifierHash): Boolean = expected == actual
    }

    private data object FixedAccessTokens : AccessTokenProvider {
        override fun issue(accountId: UUID, sessionId: UUID, now: Instant) = IssuedAccessToken("access-token", 900)
        override fun publicKeys(): List<PublicJwk> = emptyList()
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-20T10:00:00Z")
        val HASH: VerifierHash = VerifierHash.fromSha256Hex("a".repeat(64))
    }
}
