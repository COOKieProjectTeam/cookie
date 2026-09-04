package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AccountTest {
    private val now = Instant.parse("2026-08-20T10:00:00Z")

    @Test
    fun `registration creates an account and its domain event together`() {
        val id = UUID.randomUUID()
        val email = CanonicalEmail.parse("user@example.ru")

        val registration = Account.register(id, email, "hash", now)

        assertThat(registration.account.id).isEqualTo(id)
        assertThat(registration.account.email).isEqualTo(email)
        assertThat(registration.account.passwordHash).isEqualTo("hash")
        assertThat(registration.account.createdAt).isEqualTo(now)
        assertThat(registration.event).isEqualTo(AccountActivated(id, now, now))
    }

    @Test
    fun `correct password while locked does not mutate lockout state`() {
        val account = account(failedLoginCount = 5, lockedUntil = now.plusSeconds(30))
        val count = account.failedLoginCount
        val lockedUntil = account.lockedUntil

        assertThat(account.authenticatePassword(passwordMatches = true, now))
            .isEqualTo(PasswordAuthenticationResult.REJECTED)

        assertThat(account.failedLoginCount).isEqualTo(count)
        assertThat(account.lockedUntil).isEqualTo(lockedUntil)
    }

    @Test
    fun `wrong password while locked cannot extend a victim lockout`() {
        val account = account(failedLoginCount = 8, lockedUntil = now.plusSeconds(30))
        val count = account.failedLoginCount
        val lockedUntil = account.lockedUntil

        assertThat(account.authenticatePassword(passwordMatches = false, now))
            .isEqualTo(PasswordAuthenticationResult.REJECTED)

        assertThat(account.failedLoginCount).isEqualTo(count)
        assertThat(account.lockedUntil).isEqualTo(lockedUntil)
    }

    @Test
    fun `wrong password decision records the failure in the same operation`() {
        val account = account()

        assertThat(account.authenticatePassword(passwordMatches = false, now))
            .isEqualTo(PasswordAuthenticationResult.REJECTED_WITH_RECORDED_FAILURE)
        assertThat(account.failedLoginCount).isEqualTo(1)
    }

    @Test
    fun `successful authentication clears previous failures atomically`() {
        val account = account(failedLoginCount = 2)

        assertThat(account.authenticatePassword(passwordMatches = true, now))
            .isEqualTo(PasswordAuthenticationResult.AUTHENTICATED)
        assertThat(account.failedLoginCount).isZero()
        assertThat(account.lockedUntil).isNull()
    }

    @Test
    fun `reconstitution rejects a lock preceding account creation`() {
        assertThatIllegalArgumentException().isThrownBy {
            Account.reconstitute(
                id = UUID.randomUUID(),
                email = CanonicalEmail.parse("user@example.ru"),
                passwordHash = "hash",
                createdAt = now,
                failedLoginCount = 5,
                lockedUntil = now.minusSeconds(1),
            )
        }
    }

    private fun account(
        failedLoginCount: Int = 0,
        lockedUntil: Instant? = null,
    ): Account = Account.reconstitute(
        id = UUID.randomUUID(),
        email = CanonicalEmail.parse("user@example.ru"),
        passwordHash = "hash",
        createdAt = now.minusSeconds(10),
        failedLoginCount = failedLoginCount,
        lockedUntil = lockedUntil,
    )
}
