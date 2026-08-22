package com.cookie.identity.application

import com.cookie.identity.application.ports.AccessTokenProvider
import com.cookie.identity.application.ports.AccountRepository
import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.application.ports.PasswordHashing
import com.cookie.identity.application.ports.RateLimitRepository
import com.cookie.identity.application.ports.RefreshSessionRepository
import com.cookie.identity.application.ports.SecretTokenService
import com.cookie.identity.application.ports.TransactionRunner
import com.cookie.identity.domain.Account
import com.cookie.identity.domain.AccountStatus
import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.domain.RefreshRevokeReason
import com.cookie.identity.domain.RefreshSession
import com.cookie.identity.domain.RefreshSessionStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AuthenticationUseCasesTest {
    private val now = Instant.parse("2026-08-20T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `correct password for locked account is hashed outside transaction and does not extend lock`() {
        val account = activeAccount(failedLoginCount = 5, lockedUntil = now.plusSeconds(30))
        val transactions = ImmediateTransactions()
        val accounts = InMemoryAccountRepository(account)
        val hashing = object : PasswordHashing {
            override val dummyHash: String = "dummy"

            override fun encode(password: String): String = error("Not used")

            override fun matches(password: String, encoded: String): Boolean {
                assertThat(transactions.inTransaction).isFalse()
                return true
            }
        }
        val handler = LoginWithEmailHandler(
            accounts = accounts,
            transactions = transactions,
            passwordHashing = hashing,
            rateLimiter = rateLimiter(),
            sessionIssuer = unusedSessionIssuer(),
            clock = clock,
        )

        assertThatThrownBy {
            handler.execute(account.email.value, "CorrectPassword-123", null, "127.0.0.1")
        }.isInstanceOf(InvalidCredentialsException::class.java)
        assertThat(account.failedLoginCount).isEqualTo(5)
        assertThat(account.lockedUntil).isEqualTo(now.plusSeconds(30))
        assertThat(accounts.saveCount).isZero()
    }

    @Test
    fun `logout with a rotated token revokes its whole logical session family`() {
        val familyId = UUID.randomUUID()
        val original = activeSession(familyId)
        val replacement = activeSession(familyId)
        original.rotate(replacement.id, now.minusSeconds(1))
        val sessions = InMemoryRefreshSessions(listOf(original, replacement))
        val tokens = object : SecretTokenService {
            override fun create(id: UUID): GeneratedSecretToken = error("Not used")
            override fun parse(value: String): ParsedSecretToken = ParsedSecretToken(original.id, "b".repeat(64))
            override fun verifierMatches(expectedHex: String, actualHex: String): Boolean = true
        }
        val handler = LogoutHandler(sessions, ImmediateTransactions(), tokens, rateLimiter(), clock)

        handler.execute("valid-rotated-token", "127.0.0.1")

        assertThat(sessions.family).allSatisfy { session ->
            assertThat(session.status).isEqualTo(RefreshSessionStatus.REVOKED)
            assertThat(session.revokeReason).isEqualTo(RefreshRevokeReason.LOGOUT)
        }
    }

    private fun rateLimiter() = IdentityRateLimiter(
        repository = object : RateLimitRepository {
            override fun consume(scopeKey: String, window: Duration): RateLimitWindow =
                RateLimitWindow(1, now.plus(window))
        },
        clock = clock,
    )

    private fun unusedSessionIssuer() = SessionIssuer(
        sessions = InMemoryRefreshSessions(emptyList()),
        tokens = object : SecretTokenService {
            override fun create(id: UUID): GeneratedSecretToken = error("Unexpected token creation")
            override fun parse(value: String): ParsedSecretToken = error("Unexpected token parsing")
            override fun verifierMatches(expectedHex: String, actualHex: String): Boolean = false
        },
        ids = object : IdGenerator {
            override fun next(): UUID = UUID.randomUUID()
        },
        accessTokens = object : AccessTokenProvider {
            override fun issue(accountId: UUID, sessionId: UUID): IssuedAccessToken = error("Unexpected issue")
            override fun publicKeys(): List<PublicJwk> = emptyList()
        },
        policy = IdentityPolicy(Duration.ofDays(30), Duration.ofMinutes(30), Duration.ofMinutes(1)),
    )

    private fun activeAccount(failedLoginCount: Int, lockedUntil: Instant?): Account = Account.reconstitute(
        id = UUID.randomUUID(),
        email = CanonicalEmail.parse("user@example.ru"),
        passwordHash = "encoded-password",
        createdAt = now.minusSeconds(100),
        status = AccountStatus.ACTIVE,
        activatedAt = now.minusSeconds(90),
        emailVerifiedAt = now.minusSeconds(90),
        failedLoginCount = failedLoginCount,
        lockedUntil = lockedUntil,
    )

    private fun activeSession(familyId: UUID): RefreshSession = RefreshSession.active(
        id = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        familyId = familyId,
        verifierHash = "a".repeat(64),
        deviceId = "device",
        familyExpiresAt = now.plusSeconds(3600),
        now = now.minusSeconds(60),
    )

    private class ImmediateTransactions : TransactionRunner {
        var inTransaction: Boolean = false
            private set

        override fun <T : Any> required(block: () -> T): T = within(block)
        override fun requiredUnit(block: () -> Unit) = within(block)
        override fun <T : Any> requiredNullable(block: () -> T?): T? = within(block)

        private fun <T> within(block: () -> T): T {
            check(!inTransaction)
            inTransaction = true
            return try {
                block()
            } finally {
                inTransaction = false
            }
        }
    }

    private class InMemoryAccountRepository(private val account: Account) : AccountRepository {
        var saveCount: Int = 0
            private set

        override fun lockRegistration(email: CanonicalEmail) = Unit
        override fun findByEmail(email: CanonicalEmail): Account? = account.takeIf { it.email == email }
        override fun findByEmailForUpdate(email: CanonicalEmail): Account? = findByEmail(email)
        override fun findByIdForUpdate(accountId: UUID): Account? = account.takeIf { it.id == accountId }
        override fun add(account: Account) = error("Not used")
        override fun save(account: Account) {
            saveCount += 1
        }
    }

    private class InMemoryRefreshSessions(val family: List<RefreshSession>) : RefreshSessionRepository {
        override fun findById(id: UUID): RefreshSession? = family.singleOrNull { it.id == id }
        override fun lockFamily(familyId: UUID) = Unit
        override fun findFamilyForUpdate(familyId: UUID): List<RefreshSession> =
            family.filter { it.familyId == familyId }

        override fun add(session: RefreshSession) = error("Not used")
        override fun save(session: RefreshSession) = Unit
    }
}
