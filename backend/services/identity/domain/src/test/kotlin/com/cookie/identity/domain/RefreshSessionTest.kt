package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RefreshSessionTest {
    private val now = Instant.parse("2026-08-20T10:00:00Z")

    @Test
    fun `rotated token is classified as replay and family members can be revoked`() {
        val session = activeSession()
        session.rotate(UUID.randomUUID(), now)

        assertThat(session.rotationDecision(verifierMatches = true, now.plusSeconds(1)))
            .isEqualTo(RotationDecision.REPLAY)
        session.revoke(RefreshRevokeReason.REPLAY_DETECTED, now.plusSeconds(1))
        assertThat(session.status).isEqualTo(RefreshSessionStatus.REVOKED)
        assertThat(session.reuseDetectedAt).isEqualTo(now.plusSeconds(1))
    }

    @Test
    fun `wrong verifier never triggers replay handling`() {
        val session = activeSession()
        session.rotate(UUID.randomUUID(), now)

        assertThat(session.rotationDecision(verifierMatches = false, now.plusSeconds(1)))
            .isEqualTo(RotationDecision.INVALID)
    }

    private fun activeSession(): RefreshSession = RefreshSession.active(
        id = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        familyId = UUID.randomUUID(),
        verifierHash = "a".repeat(64),
        deviceId = "test-device",
        familyExpiresAt = now.plusSeconds(3600),
        now = now.minusSeconds(1),
    )
}
