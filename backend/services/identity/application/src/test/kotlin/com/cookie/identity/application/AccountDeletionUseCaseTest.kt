package com.cookie.identity.application

import com.cookie.identity.application.ports.AccessTokenVerifier
import com.cookie.identity.application.ports.AccountDeletionRequestRepository
import com.cookie.identity.application.ports.AccountRepository
import com.cookie.identity.application.ports.CurrentTimeProvider
import com.cookie.identity.application.ports.IdentityEventRecorder
import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.application.ports.PasswordHashing
import com.cookie.identity.application.ports.RateLimitRepository
import com.cookie.identity.application.ports.RefreshFamilyRepository
import com.cookie.identity.application.ports.TransactionRunner
import com.cookie.identity.domain.Account
import com.cookie.identity.domain.AccountActivated
import com.cookie.identity.domain.AccountDeletionRequest
import com.cookie.identity.domain.AccountDeletionRequestStatus
import com.cookie.identity.domain.AccountDeletionRequested
import com.cookie.identity.domain.AccountStatus
import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.domain.LocaleTag
import com.cookie.identity.domain.PasswordPolicy
import com.cookie.identity.domain.RefreshFamily
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class AccountDeletionUseCaseTest {
    @Test
    fun `successful request closes account revokes sessions and records one event atomically`() {
        val account = activeAccount()
        val transactions = ImmediateTransactions()
        val accounts = InMemoryAccounts(account, transactions)
        val requests = InMemoryDeletionRequests(transactions)
        val families = RecordingFamilies(transactions)
        val events = RecordingEvents(transactions)
        val hashing = RecordingPasswordHashing(transactions) { _, encoded -> encoded == PASSWORD_HASH }
        val rates = RecordingRates()
        val handler = handler(
            accounts = accounts,
            deletionRequests = requests,
            families = families,
            transactions = transactions,
            passwordHashing = hashing,
            rateLimits = rates,
            ids = FixedId(DELETION_REQUEST_ID),
            events = events,
            accountId = account.id,
        )

        val result = handler.execute(command(KEY_1))

        assertThat(result.deletionRequestId).isEqualTo(DELETION_REQUEST_ID)
        assertThat(result.status).isEqualTo(AccountDeletionRequestStatus.DELETION_PENDING)
        assertThat(result.requestedAt).isEqualTo(NOW)
        assertThat(account.status).isEqualTo(AccountStatus.DELETION_PENDING)
        assertThat(account.lockedUntil).isEqualTo(Account.DELETION_LOCKED_UNTIL)
        assertThat(accounts.saveCount).isEqualTo(1)
        assertThat(requests.addCount).isEqualTo(1)
        assertThat(requests.request?.idempotencyKey).isEqualTo(KEY_1)
        assertThat(families.revocations).containsExactly(account.id to NOW)
        assertThat(events.deletions).containsExactly(
            AccountDeletionRequested(account.id, DELETION_REQUEST_ID, NOW),
        )
        assertThat(hashing.encodedValues).containsExactly(PASSWORD_HASH)
        assertThat(rates.scopes).hasSize(2)
        assertThat(rates.scopes[0]).startsWith("account-deletion:ip:")
        assertThat(rates.scopes[1]).startsWith("account-deletion:account:")
    }

    @Test
    fun `same and different idempotency keys return the original request without repeated password work`() {
        val account = activeAccount()
        val transactions = ImmediateTransactions()
        val accounts = InMemoryAccounts(account, transactions)
        val requests = InMemoryDeletionRequests(transactions)
        val families = RecordingFamilies(transactions)
        val events = RecordingEvents(transactions)
        val hashing = RecordingPasswordHashing(transactions) { _, _ -> true }
        val handler = handler(
            accounts = accounts,
            deletionRequests = requests,
            families = families,
            transactions = transactions,
            passwordHashing = hashing,
            ids = FixedId(DELETION_REQUEST_ID),
            events = events,
            accountId = account.id,
        )

        val first = handler.execute(command(KEY_1))
        val sameKeyRetry = handler.execute(command(KEY_1, password = "wrong-on-retry"))
        val differentKeyRetry = handler.execute(command(KEY_2, password = "wrong-on-retry"))

        assertThat(sameKeyRetry).isEqualTo(first)
        assertThat(differentKeyRetry).isEqualTo(first)
        assertThat(hashing.matchesCount).isEqualTo(1)
        assertThat(accounts.saveCount).isEqualTo(1)
        assertThat(requests.addCount).isEqualTo(1)
        assertThat(families.revocations).hasSize(1)
        assertThat(events.deletions).hasSize(1)
    }

    @Test
    fun `request that loses the account lock returns the request created by the winner`() {
        val account = activeAccount()
        val existing = AccountDeletionRequest.start(DELETION_REQUEST_ID, account.id, KEY_2, NOW).request
        val transactions = ImmediateTransactions()
        val accounts = InMemoryAccounts(account, transactions)
        val requests = DelayedVisibleDeletionRequest(existing, transactions)
        val families = RecordingFamilies(transactions)
        val events = RecordingEvents(transactions)
        val hashing = RecordingPasswordHashing(transactions) { _, _ -> true }
        val ids = CountingIds()
        val handler = handler(
            accounts = accounts,
            deletionRequests = requests,
            families = families,
            transactions = transactions,
            passwordHashing = hashing,
            ids = ids,
            events = events,
            accountId = account.id,
        )

        val result = handler.execute(command(KEY_1))

        assertThat(result.deletionRequestId).isEqualTo(existing.id)
        assertThat(accounts.idForUpdateCount).isEqualTo(1)
        assertThat(accounts.saveCount).isZero()
        assertThat(ids.calls).isZero()
        assertThat(families.revocations).isEmpty()
        assertThat(events.deletions).isEmpty()
    }

    @Test
    fun `password hashing is retried outside transaction when credential snapshot changes`() {
        val accountId = UUID.randomUUID()
        val old = activeAccount(accountId, "old-hash")
        val current = activeAccount(accountId, "new-hash")
        val transactions = ImmediateTransactions()
        val accounts = ChangingAccounts(
            snapshots = listOf(old, current),
            locked = listOf(current, current),
            transactions = transactions,
        )
        val requests = InMemoryDeletionRequests(transactions)
        val families = RecordingFamilies(transactions)
        val events = RecordingEvents(transactions)
        val hashing = RecordingPasswordHashing(transactions) { _, encoded -> encoded == "new-hash" }
        val handler = handler(
            accounts = accounts,
            deletionRequests = requests,
            families = families,
            transactions = transactions,
            passwordHashing = hashing,
            ids = FixedId(DELETION_REQUEST_ID),
            events = events,
            accountId = accountId,
        )

        handler.execute(command(KEY_1))

        assertThat(hashing.encodedValues).containsExactly("old-hash", "new-hash")
        assertThat(accounts.lockCount).isEqualTo(2)
        assertThat(current.status).isEqualTo(AccountStatus.DELETION_PENDING)
        assertThat(requests.addCount).isEqualTo(1)
        assertThat(events.deletions).hasSize(1)
    }

    @Test
    fun `wrong password records authentication failure but creates no deletion effects`() {
        val account = activeAccount()
        val transactions = ImmediateTransactions()
        val accounts = InMemoryAccounts(account, transactions)
        val requests = InMemoryDeletionRequests(transactions)
        val families = RecordingFamilies(transactions)
        val events = RecordingEvents(transactions)
        val handler = handler(
            accounts = accounts,
            deletionRequests = requests,
            families = families,
            transactions = transactions,
            passwordHashing = RecordingPasswordHashing(transactions) { _, _ -> false },
            ids = CountingIds(),
            events = events,
            accountId = account.id,
        )

        assertThatThrownBy { handler.execute(command(KEY_1)) }
            .isInstanceOf(InvalidCredentialsException::class.java)

        assertThat(account.status).isEqualTo(AccountStatus.ACTIVE)
        assertThat(account.failedLoginCount).isEqualTo(1)
        assertThat(accounts.saveCount).isEqualTo(1)
        assertThat(requests.addCount).isZero()
        assertThat(families.revocations).isEmpty()
        assertThat(events.deletions).isEmpty()
    }

    @Test
    fun `pending account without request evidence is rejected and never reopened`() {
        val account = activeAccount().also { it.requestDeletion(NOW) }
        val transactions = ImmediateTransactions()
        val requests = InMemoryDeletionRequests(transactions)
        val families = RecordingFamilies(transactions)
        val events = RecordingEvents(transactions)
        val accounts = InMemoryAccounts(account, transactions)
        val handler = handler(
            accounts = accounts,
            deletionRequests = requests,
            families = families,
            transactions = transactions,
            passwordHashing = RecordingPasswordHashing(transactions) { _, _ -> true },
            ids = CountingIds(),
            events = events,
            accountId = account.id,
        )

        assertThatThrownBy { handler.execute(command(KEY_1)) }
            .isInstanceOf(InvalidCredentialsException::class.java)

        assertThat(account.status).isEqualTo(AccountStatus.DELETION_PENDING)
        assertThat(accounts.saveCount).isZero()
        assertThat(requests.addCount).isZero()
        assertThat(families.revocations).isEmpty()
        assertThat(events.deletions).isEmpty()
    }

    @Test
    fun `non-v4 idempotency key is rejected after ip limit and before jwt verification`() {
        val account = activeAccount()
        val transactions = ImmediateTransactions()
        val verifier = RecordingVerifier(account.id)
        val rates = RecordingRates()
        val handler = handler(
            accounts = InMemoryAccounts(account, transactions),
            deletionRequests = InMemoryDeletionRequests(transactions),
            families = RecordingFamilies(transactions),
            transactions = transactions,
            passwordHashing = RecordingPasswordHashing(transactions) { _, _ -> true },
            rateLimits = rates,
            accessTokens = verifier,
            accountId = account.id,
        )

        assertThatThrownBy { handler.execute(command(UUID(0, 0))) }
            .isInstanceOf(com.cookie.identity.domain.InvalidInputException::class.java)

        assertThat(rates.scopes).singleElement().asString().startsWith("account-deletion:ip:")
        assertThat(verifier.calls).isZero()
    }

    @Test
    fun `account rate limit is applied after jwt verification and before password hashing`() {
        val account = activeAccount()
        val transactions = ImmediateTransactions()
        val verifier = RecordingVerifier(account.id)
        val hashing = RecordingPasswordHashing(transactions) { _, _ -> true }
        val rates = SaturatedAccountDeletionRates()
        val accounts = InMemoryAccounts(account, transactions)
        val handler = handler(
            accounts = accounts,
            deletionRequests = InMemoryDeletionRequests(transactions),
            families = RecordingFamilies(transactions),
            transactions = transactions,
            passwordHashing = hashing,
            rateLimits = rates,
            accessTokens = verifier,
            accountId = account.id,
        )

        assertThatThrownBy { handler.execute(command(KEY_1)) }
            .isInstanceOf(RateLimitExceededException::class.java)

        assertThat(verifier.calls).isEqualTo(1)
        assertThat(rates.scopes).hasSize(2)
        assertThat(rates.scopes[0]).startsWith("account-deletion:ip:")
        assertThat(rates.scopes[1]).startsWith("account-deletion:account:")
        assertThat(hashing.matchesCount).isZero()
        assertThat(accounts.idForUpdateCount).isZero()
    }

    @Test
    fun `accepted retry keeps one result but still respects the documented account abuse ceiling`() {
        val account = activeAccount()
        val transactions = ImmediateTransactions()
        val accounts = InMemoryAccounts(account, transactions)
        val requests = InMemoryDeletionRequests(transactions)
        val families = RecordingFamilies(transactions)
        val events = RecordingEvents(transactions)
        val hashing = RecordingPasswordHashing(transactions) { _, _ -> true }
        val rates = CountingRates()
        val handler = handler(
            accounts = accounts,
            deletionRequests = requests,
            families = families,
            transactions = transactions,
            passwordHashing = hashing,
            rateLimits = rates,
            ids = FixedId(DELETION_REQUEST_ID),
            events = events,
            accountId = account.id,
        )

        val accepted = handler.execute(command(KEY_1))
        repeat(9) {
            assertThat(handler.execute(command(KEY_1))).isEqualTo(accepted)
        }

        assertThatThrownBy { handler.execute(command(KEY_1)) }
            .isInstanceOf(RateLimitExceededException::class.java)
        assertThat(hashing.matchesCount).isEqualTo(1)
        assertThat(requests.addCount).isEqualTo(1)
        assertThat(events.deletions).hasSize(1)
    }

    @Test
    fun `command string representation redacts every request credential`() {
        val command = AccountDeletionCommand(ACCESS_TOKEN, PASSWORD, KEY_1, IP)

        assertThat(command.toString()).doesNotContain(ACCESS_TOKEN, PASSWORD, KEY_1.toString(), IP)
        assertThat(command.toString()).contains("[redacted]")
    }

    private fun handler(
        accounts: AccountRepository,
        deletionRequests: AccountDeletionRequestRepository,
        families: RefreshFamilyRepository,
        transactions: ImmediateTransactions,
        passwordHashing: PasswordHashing,
        ids: IdGenerator = CountingIds(),
        events: IdentityEventRecorder = RecordingEvents(transactions),
        rateLimits: RateLimitRepository = RecordingRates(),
        accessTokens: AccessTokenVerifier? = null,
        accountId: UUID,
    ) = RequestAccountDeletionHandler(
        accounts = accounts,
        deletionRequests = deletionRequests,
        families = families,
        transactions = transactions,
        accessTokens = accessTokens ?: RecordingVerifier(accountId),
        passwordPolicy = PasswordPolicy(),
        passwordHashing = passwordHashing,
        rateLimiter = IdentityRateLimiter(rateLimits, TEST_RATE_LIMIT_SCOPE_HASHER),
        ids = ids,
        events = events,
        currentTime = CurrentTimeProvider { NOW },
    )

    private fun command(key: UUID, password: String = PASSWORD) = AccountDeletionCommand(
        accessToken = ACCESS_TOKEN,
        currentPassword = password,
        idempotencyKey = key,
        ip = IP,
    )

    private fun activeAccount(id: UUID = ACCOUNT_ID, hash: String = PASSWORD_HASH): Account = Account.reconstitute(
        id = id,
        email = CanonicalEmail.parse("user@example.ru"),
        passwordHash = hash,
        createdAt = NOW.minusSeconds(3600),
        failedLoginCount = 0,
        lockedUntil = null,
    )

    private class ImmediateTransactions : TransactionRunner {
        var inTransaction = false
            private set

        override fun <T : Any> required(block: () -> T): T = within(block)
        override fun requiredUnit(block: () -> Unit) = within(block)

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

    private class InMemoryAccounts(
        private var account: Account?,
        private val transactions: ImmediateTransactions,
    ) : AccountRepository {
        var saveCount = 0
            private set
        var idForUpdateCount = 0
            private set

        override fun lockRegistration(email: CanonicalEmail) = error("Not used")
        override fun findByEmail(email: CanonicalEmail): Account? = error("Not used")
        override fun findById(accountId: UUID): Account? = account?.takeIf { it.id == accountId }
        override fun findByEmailForUpdate(email: CanonicalEmail): Account? = error("Not used")
        override fun findByIdForUpdate(accountId: UUID): Account? {
            check(transactions.inTransaction)
            idForUpdateCount += 1
            return account?.takeIf { it.id == accountId }
        }
        override fun add(account: Account) = error("Not used")
        override fun save(account: Account) {
            check(transactions.inTransaction)
            check(this.account?.id == account.id)
            this.account = account
            saveCount += 1
        }
    }

    private class ChangingAccounts(
        snapshots: List<Account>,
        locked: List<Account>,
        private val transactions: ImmediateTransactions,
    ) : AccountRepository {
        private val snapshots = snapshots.iterator()
        private val locked = locked.iterator()
        var lockCount = 0
            private set

        override fun lockRegistration(email: CanonicalEmail) = error("Not used")
        override fun findByEmail(email: CanonicalEmail): Account? = error("Not used")
        override fun findById(accountId: UUID): Account? = snapshots.next()
        override fun findByEmailForUpdate(email: CanonicalEmail): Account? = error("Not used")
        override fun findByIdForUpdate(accountId: UUID): Account? {
            check(transactions.inTransaction)
            lockCount += 1
            return locked.next()
        }
        override fun add(account: Account) = error("Not used")
        override fun save(account: Account) {
            check(transactions.inTransaction)
        }
    }

    private class InMemoryDeletionRequests(
        private val transactions: ImmediateTransactions,
    ) : AccountDeletionRequestRepository {
        var request: AccountDeletionRequest? = null
            private set
        var addCount = 0
            private set

        override fun findByAccountId(accountId: UUID): AccountDeletionRequest? =
            request?.takeIf { it.accountId == accountId }

        override fun add(request: AccountDeletionRequest) {
            check(transactions.inTransaction)
            check(this.request == null)
            this.request = request
            addCount += 1
        }
    }

    private class DelayedVisibleDeletionRequest(
        private val request: AccountDeletionRequest,
        private val transactions: ImmediateTransactions,
    ) : AccountDeletionRequestRepository {
        private var reads = 0

        override fun findByAccountId(accountId: UUID): AccountDeletionRequest? {
            reads += 1
            return request.takeIf { reads > 1 && it.accountId == accountId }
        }

        override fun add(request: AccountDeletionRequest) {
            check(transactions.inTransaction)
            error("Winner request must be reused")
        }
    }

    private class RecordingFamilies(
        private val transactions: ImmediateTransactions,
    ) : RefreshFamilyRepository {
        val revocations = mutableListOf<Pair<UUID, Instant>>()

        override fun findCredentialLookup(id: UUID): RefreshCredentialLookup? = error("Not used")
        override fun findByCredentialIdForUpdate(credentialId: UUID): RefreshFamily? = error("Not used")
        override fun revokeAllForAccount(accountId: UUID, now: Instant) {
            check(transactions.inTransaction)
            revocations += accountId to now
        }
        override fun add(family: RefreshFamily) = error("Not used")
        override fun save(family: RefreshFamily) = error("Not used")
    }

    private class RecordingPasswordHashing(
        private val transactions: ImmediateTransactions,
        private val result: (String, String) -> Boolean,
    ) : PasswordHashing {
        override val dummyHash = "dummy-hash"
        val encodedValues = mutableListOf<String>()
        var matchesCount = 0
            private set

        override fun encode(password: String): String = error("Not used")
        override fun matches(password: String, encoded: String): Boolean {
            check(!transactions.inTransaction)
            matchesCount += 1
            encodedValues += encoded
            return result(password, encoded)
        }
    }

    private class RecordingRates : RateLimitRepository {
        val scopes = mutableListOf<String>()
        override fun consume(scopeKey: String, window: Duration): RateLimitWindow {
            scopes += scopeKey
            return RateLimitWindow(1, window.seconds)
        }
    }

    private class SaturatedAccountDeletionRates : RateLimitRepository {
        val scopes = mutableListOf<String>()
        override fun consume(scopeKey: String, window: Duration): RateLimitWindow {
            scopes += scopeKey
            val count = if (scopeKey.startsWith("account-deletion:account:")) 11 else 1
            return RateLimitWindow(count, window.seconds)
        }
    }

    private class CountingRates : RateLimitRepository {
        private val counts = mutableMapOf<String, Int>()

        override fun consume(scopeKey: String, window: Duration): RateLimitWindow {
            val count = counts.merge(scopeKey, 1, Int::plus) ?: error("Missing rate-limit count")
            return RateLimitWindow(count, window.seconds)
        }
    }

    private class RecordingVerifier(val accountId: UUID) : AccessTokenVerifier {
        var calls = 0
            private set
        override fun verify(rawToken: String, now: Instant): VerifiedAccessToken {
            calls += 1
            return VerifiedAccessToken(accountId)
        }
    }

    private class RecordingEvents(
        private val transactions: ImmediateTransactions,
    ) : IdentityEventRecorder {
        val deletions = mutableListOf<AccountDeletionRequested>()

        override fun verificationRequested(
            registrationAttemptId: UUID,
            email: CanonicalEmail,
            locale: LocaleTag?,
            rawToken: String,
            expiresAt: Instant,
            now: Instant,
        ) = error("Not used")

        override fun accountActivated(event: AccountActivated) = error("Not used")

        override fun accountDeletionRequested(event: AccountDeletionRequested) {
            check(transactions.inTransaction)
            deletions += event
        }
    }

    private class FixedId(private val id: UUID) : IdGenerator {
        private var used = false
        override fun next(): UUID {
            check(!used)
            used = true
            return id
        }
    }

    private class CountingIds : IdGenerator {
        var calls = 0
            private set
        override fun next(): UUID {
            calls += 1
            return UUID.randomUUID()
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-20T10:00:00Z")
        val ACCOUNT_ID: UUID = UUID.fromString("0198c2f5-4c00-7000-8000-000000000001")
        val DELETION_REQUEST_ID: UUID = UUID.fromString("0198c2f5-4c00-7000-8000-000000000002")
        val KEY_1: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val KEY_2: UUID = UUID.fromString("10000000-0000-4000-8000-000000000002")
        const val ACCESS_TOKEN = "signed.access.jwt"
        const val PASSWORD = "CorrectPassword-123"
        const val PASSWORD_HASH = "encoded-password"
        const val IP = "192.0.2.10"
    }
}
