package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AccountTest {
    private val now = Instant.parse("2026-08-20T10:00:00Z")

    @Test
    fun `activation is a one-way aggregate transition`() {
        val account = Account.pending(UUID.randomUUID(), CanonicalEmail.parse("user@example.ru"), "hash", now)

        val event = account.activate(now.plusSeconds(1))
        assertThat(event).isEqualTo(AccountActivated(account.id, now, now.plusSeconds(1)))
        assertThat(account.activate(now.plusSeconds(2))).isNull()
        assertThat(account.status).isEqualTo(AccountStatus.ACTIVE)
        assertThat(account.emailVerifiedAt).isEqualTo(now.plusSeconds(1))
    }

    @Test
    fun `correct password while locked does not mutate lockout state`() {
        val account = activeAccount()
        repeat(5) { account.recordFailedPassword(now) }
        val count = account.failedLoginCount
        val lockedUntil = account.lockedUntil

        assertThat(account.canAuthenticate(passwordMatches = true, now)).isFalse()

        assertThat(account.failedLoginCount).isEqualTo(count)
        assertThat(account.lockedUntil).isEqualTo(lockedUntil)
    }

    private fun activeAccount(): Account = Account.reconstitute(
        id = UUID.randomUUID(),
        email = CanonicalEmail.parse("user@example.ru"),
        passwordHash = "hash",
        createdAt = now.minusSeconds(10),
        status = AccountStatus.ACTIVE,
        activatedAt = now.minusSeconds(5),
        emailVerifiedAt = now.minusSeconds(5),
        failedLoginCount = 0,
        lockedUntil = null,
    )
}
