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
        assertThat(registration.account.status).isEqualTo(AccountStatus.ACTIVE)
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
    fun `deletion request irreversibly closes account with compatibility lock`() {
        val account = account(failedLoginCount = 2, lockedUntil = now.plusSeconds(30))

        assertThat(account.requestDeletion(now)).isTrue()

        assertThat(account.status).isEqualTo(AccountStatus.DELETION_PENDING)
        assertThat(account.lockedUntil).isEqualTo(Account.DELETION_LOCKED_UNTIL)
        assertThat(account.failedLoginCount).isEqualTo(2)
    }

    @Test
    fun `repeated deletion request preserves the original transition`() {
        val account = account()
        account.requestDeletion(now)

        assertThat(account.requestDeletion(now.plusSeconds(30))).isFalse()

        assertThat(account.lockedUntil).isEqualTo(Account.DELETION_LOCKED_UNTIL)
    }

    @Test
    fun `pending deletion rejects either password without mutating lockout state`() {
        val account = account(failedLoginCount = 3)
        account.requestDeletion(now)
        val count = account.failedLoginCount
        val lockedUntil = account.lockedUntil

        assertThat(account.authenticatePassword(passwordMatches = true, now.plusSeconds(1)))
            .isEqualTo(PasswordAuthenticationResult.REJECTED)
        assertThat(account.authenticatePassword(passwordMatches = false, now.plusSeconds(2)))
            .isEqualTo(PasswordAuthenticationResult.REJECTED)

        assertThat(account.failedLoginCount).isEqualTo(count)
        assertThat(account.lockedUntil).isEqualTo(lockedUntil)
    }

    @Test
    fun `legacy reconstitution defaults to active lifecycle state`() {
        val account = Account.reconstitute(
            id = UUID.randomUUID(),
            email = CanonicalEmail.parse("user@example.ru"),
            passwordHash = "hash",
            createdAt = now.minusSeconds(1),
            failedLoginCount = 0,
            lockedUntil = null,
        )

        assertThat(account.status).isEqualTo(AccountStatus.ACTIVE)
    }

    @Test
    fun `pending lifecycle state can be reconstituted with its compatibility lock`() {
        val account = account(
            status = AccountStatus.DELETION_PENDING,
            lockedUntil = Account.DELETION_LOCKED_UNTIL,
        )

        assertThat(account.status).isEqualTo(AccountStatus.DELETION_PENDING)
        assertThat(account.lockedUntil).isEqualTo(Account.DELETION_LOCKED_UNTIL)
    }

    @Test
    fun `reconstitution rejects inconsistent lifecycle state`() {
        assertThatIllegalArgumentException().isThrownBy {
            account(
                status = AccountStatus.DELETION_PENDING,
                lockedUntil = now.plusSeconds(30),
            )
        }
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
        status: AccountStatus = AccountStatus.ACTIVE,
    ): Account = Account.reconstitute(
        id = UUID.randomUUID(),
        email = CanonicalEmail.parse("user@example.ru"),
        passwordHash = "hash",
        createdAt = now.minusSeconds(10),
        failedLoginCount = failedLoginCount,
        lockedUntil = lockedUntil,
        status = status,
    )
}
