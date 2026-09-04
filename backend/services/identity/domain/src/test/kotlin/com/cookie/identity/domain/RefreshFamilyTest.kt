package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RefreshFamilyTest {
    private val now = Instant.parse("2026-08-20T10:00:00Z")
    private val retryKey = UUID.randomUUID()

    @Test
    fun `rotation redeems the current credential and installs its replacement`() {
        val family = activeFamily()
        val originalId = family.currentCredentialId
        val replacementId = UUID.randomUUID()

        family.rotateCurrentTo(
            presentedCredentialId = originalId,
            replacementCredentialId = replacementId,
            replacementVerifierHash = verifier("b"),
            idempotencyKey = retryKey,
            retryUntil = family.expiresAt,
            now = now,
        )

        assertThat(family.currentCredentialId).isEqualTo(replacementId)
        assertThat(family.lastActivityAt).isEqualTo(now)
        assertThat(family.credentialSnapshots().single { it.id == originalId }.redeemedAt).isEqualTo(now)
    }

    @Test
    fun `the same request can recover while its successor is still current`() {
        val family = activeFamily()
        val originalId = family.currentCredentialId
        family.rotateCurrentTo(
            originalId,
            UUID.randomUUID(),
            verifier("b"),
            retryKey,
            family.expiresAt,
            now,
        )

        assertThat(family.refreshDecision(originalId, true, retryKey, now.plusSeconds(3_599)))
            .isEqualTo(RefreshDecision.RETRY)
    }

    @Test
    fun `a persisted legacy retry deadline remains conservative`() {
        val family = activeFamily()
        val originalId = family.currentCredentialId
        family.rotateCurrentTo(
            originalId,
            UUID.randomUUID(),
            verifier("b"),
            retryKey,
            now.plusSeconds(30),
            now,
        )

        assertThat(family.refreshDecision(originalId, true, retryKey, now.plusSeconds(31)))
            .isEqualTo(RefreshDecision.STALE_RETRY)
        assertThat(family.status).isEqualTo(RefreshFamilyStatus.ACTIVE)
    }

    @Test
    fun `a different key is token reuse but an expired family is invalid`() {
        val family = activeFamily()
        val originalId = family.currentCredentialId
        family.rotateCurrentTo(
            originalId,
            UUID.randomUUID(),
            verifier("b"),
            retryKey,
            family.expiresAt,
            now,
        )

        assertThat(family.refreshDecision(originalId, true, UUID.randomUUID(), now.plusSeconds(1)))
            .isEqualTo(RefreshDecision.TOKEN_REUSE)
        assertThat(family.refreshDecision(originalId, true, retryKey, now.plusSeconds(3_600)))
            .isEqualTo(RefreshDecision.INVALID)
    }

    @Test
    fun `a delayed exact retry stays harmless after its successor has rotated`() {
        val family = activeFamily()
        val originalId = family.currentCredentialId
        val successorId = UUID.randomUUID()
        family.rotateCurrentTo(
            originalId,
            successorId,
            verifier("b"),
            retryKey,
            family.expiresAt,
            now,
        )
        family.rotateCurrentTo(
            successorId,
            UUID.randomUUID(),
            verifier("c"),
            UUID.randomUUID(),
            family.expiresAt,
            now.plusSeconds(1),
        )

        assertThat(family.refreshDecision(originalId, true, retryKey, now.plusSeconds(2)))
            .isEqualTo(RefreshDecision.STALE_RETRY)
        assertThat(family.status).isEqualTo(RefreshFamilyStatus.ACTIVE)
    }

    @Test
    fun `wrong verifier never revokes a family`() {
        val family = activeFamily()
        val originalId = family.currentCredentialId
        family.rotateCurrentTo(
            originalId,
            UUID.randomUUID(),
            verifier("b"),
            retryKey,
            family.expiresAt,
            now,
        )

        assertThat(family.refreshDecision(originalId, false, UUID.randomUUID(), now.plusSeconds(1)))
            .isEqualTo(RefreshDecision.INVALID)
        assertThat(family.status).isEqualTo(RefreshFamilyStatus.ACTIVE)
    }

    @Test
    fun `token reuse revokes only the aggregate root`() {
        val family = activeFamily()

        family.revoke(RefreshFamilyRevokeReason.TOKEN_REUSE_DETECTED, now)

        assertThat(family.status).isEqualTo(RefreshFamilyStatus.REVOKED)
        assertThat(family.revokeReason).isEqualTo(RefreshFamilyRevokeReason.TOKEN_REUSE_DETECTED)
        assertThat(family.reuseDetectedAt).isEqualTo(now)
    }

    @Test
    fun `reconstitution rejects credentials from another family`() {
        val familyId = UUID.randomUUID()

        assertThatThrownBy {
            RefreshFamily.reconstitute(
                id = familyId,
                accountId = UUID.randomUUID(),
                deviceId = null,
                expiresAt = now.plusSeconds(3600),
                createdAt = now.minusSeconds(1),
                status = RefreshFamilyStatus.ACTIVE,
                lastActivityAt = now.minusSeconds(1),
                revokedAt = null,
                revokeReason = null,
                reuseDetectedAt = null,
                credentials = listOf(
                    RefreshCredential.reconstitute(
                        id = UUID.randomUUID(),
                        familyId = UUID.randomUUID(),
                        verifierHash = verifier("a"),
                        createdAt = now.minusSeconds(1),
                        redeemedAt = null,
                        replacedByCredentialId = null,
                        rotationIdempotencyKey = null,
                        retryUntil = null,
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `reconstitution rejects revocation before the last family activity`() {
        val familyId = UUID.randomUUID()
        val credentialId = UUID.randomUUID()

        assertThatThrownBy {
            RefreshFamily.reconstitute(
                id = familyId,
                accountId = UUID.randomUUID(),
                deviceId = null,
                expiresAt = now.plusSeconds(3600),
                createdAt = now.minusSeconds(10),
                status = RefreshFamilyStatus.REVOKED,
                lastActivityAt = now,
                revokedAt = now.minusSeconds(1),
                revokeReason = RefreshFamilyRevokeReason.LOGOUT,
                reuseDetectedAt = null,
                credentials = listOf(
                    RefreshCredential.reconstitute(
                        id = credentialId,
                        familyId = familyId,
                        verifierHash = verifier("a"),
                        createdAt = now.minusSeconds(10),
                        redeemedAt = null,
                        replacedByCredentialId = null,
                        rotationIdempotencyKey = null,
                        retryUntil = null,
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun activeFamily(): RefreshFamily = RefreshFamily.start(
        id = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        firstCredentialId = UUID.randomUUID(),
        firstVerifierHash = verifier("a"),
        deviceId = DeviceId.parse("test-device"),
        expiresAt = now.plusSeconds(3600),
        now = now.minusSeconds(1),
    )

    private fun verifier(character: String): VerifierHash = VerifierHash.fromSha256Hex(character.repeat(64))
}
