package com.cookie.identity.application

import com.cookie.identity.application.ports.AccessTokenProvider
import com.cookie.identity.application.ports.AccountRepository
import com.cookie.identity.application.ports.CurrentTimeProvider
import com.cookie.identity.application.ports.IdentityEventRecorder
import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.application.ports.PasswordHashing
import com.cookie.identity.application.ports.RateLimitRepository
import com.cookie.identity.application.ports.RefreshFamilyRepository
import com.cookie.identity.application.ports.RefreshTokenService
import com.cookie.identity.application.ports.TransactionRunner
import com.cookie.identity.domain.Account
import com.cookie.identity.domain.AccountActivated
import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.domain.DeviceId
import com.cookie.identity.domain.InvalidDeviceIdException
import com.cookie.identity.domain.InvalidInputException
import com.cookie.identity.domain.InvalidPasswordException
import com.cookie.identity.domain.LocaleTag
import com.cookie.identity.domain.PasswordPolicy
import com.cookie.identity.domain.RefreshFamily
import com.cookie.identity.domain.RefreshFamilyRevokeReason
import com.cookie.identity.domain.RefreshFamilyStatus
import com.cookie.identity.domain.VerifierHash
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID

class AuthenticationUseCasesTest {
    private val now = Instant.parse("2026-08-20T10:00:00Z")
    private val currentTime = CurrentTimeProvider { now }

    @Test
    fun `malformed login still consumes the ip guard before validation`() {
        val rateLimits = RecordingRateLimits(now)
        val handler = LoginWithEmailHandler(
            accounts = InMemoryAccountRepository(),
            transactions = ImmediateTransactions(),
            passwordPolicy = PasswordPolicy(),
            passwordHashing = object : PasswordHashing {
                override val dummyHash = "dummy"
                override fun encode(password: String) = error("Not used")
                override fun matches(password: String, encoded: String) = error("Not used")
            },
            rateLimiter = IdentityRateLimiter(rateLimits, TEST_RATE_LIMIT_SCOPE_HASHER),
            sessionIssuer = unusedSessionIssuer(),
            currentTime = currentTime,
        )

        assertThatThrownBy { handler.execute("not-an-email", "", null, "127.0.0.1") }
            .isInstanceOf(InvalidPasswordException::class.java)
        assertThat(rateLimits.scopes).singleElement().asString().startsWith("login:ip:")
    }

    @Test
    fun `invalid device id is rejected inside the use case after consuming the ip guard`() {
        val rateLimits = RecordingRateLimits(now)
        val handler = LoginWithEmailHandler(
            accounts = InMemoryAccountRepository(),
            transactions = ImmediateTransactions(),
            passwordPolicy = PasswordPolicy(),
            passwordHashing = object : PasswordHashing {
                override val dummyHash = "dummy"
                override fun encode(password: String) = error("Not used")
                override fun matches(password: String, encoded: String) = error("Not used")
            },
            rateLimiter = IdentityRateLimiter(rateLimits, TEST_RATE_LIMIT_SCOPE_HASHER),
            sessionIssuer = unusedSessionIssuer(),
            currentTime = currentTime,
        )

        assertThatThrownBy {
            handler.execute("user@example.ru", "CorrectPassword-123", " ", "127.0.0.1")
        }.isInstanceOf(InvalidDeviceIdException::class.java)
        assertThat(rateLimits.scopes).singleElement().asString().startsWith("login:ip:")
    }

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
                assertThat(password).isEqualTo("CorrectPassword-é")
                return true
            }
        }
        val handler = LoginWithEmailHandler(
            accounts = accounts,
            transactions = transactions,
            passwordPolicy = PasswordPolicy(),
            passwordHashing = hashing,
            rateLimiter = rateLimiter(),
            sessionIssuer = unusedSessionIssuer(),
            currentTime = currentTime,
        )

        assertThatThrownBy {
            handler.execute(account.email.value, "CorrectPassword-e\u0301", null, "127.0.0.1")
        }.isInstanceOf(InvalidCredentialsException::class.java)
        assertThat(account.failedLoginCount).isEqualTo(5)
        assertThat(account.lockedUntil).isEqualTo(now.plusSeconds(30))
        assertThat(accounts.saveCount).isZero()
    }

    @Test
    fun `same predecessor and idempotency key return the same replacement refresh token`() {
        val familyId = UUID.randomUUID()
        val predecessorId = UUID.randomUUID()
        val replacementId = UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID()
        val family = activeFamily(familyId, predecessorId)
        val families = InMemoryRefreshFamilies(family)
        val tokens = DeterministicSecretTokens(predecessorId, verifierHash("a"))
        val handler = RefreshSessionHandler(
            families = families,
            transactions = ImmediateTransactions(),
            refreshTokens = tokens,
            rateLimiter = rateLimiter(),
            sessionIssuer = sessionIssuer(families, tokens, replacementId),
            currentTime = currentTime,
        )

        val first = handler.execute("old-refresh-token", idempotencyKey, "127.0.0.1")
        val retry = handler.execute("old-refresh-token", idempotencyKey, "127.0.0.1")

        assertThat(retry.refreshToken).isEqualTo(first.refreshToken)
        assertThat(family.currentCredentialId).isEqualTo(replacementId)
        assertThat(family.credentialSnapshots()).hasSize(2)
        assertThat(families.saveCount).isEqualTo(1)
    }

    @Test
    fun `refresh rejects a predictable idempotency key after consuming the ip guard`() {
        val family = activeFamily(UUID.randomUUID(), UUID.randomUUID())
        val families = InMemoryRefreshFamilies(family)
        val tokens = DeterministicSecretTokens(family.currentCredentialId, verifierHash("a"))
        val rateLimits = RecordingRateLimits(now)
        val handler = RefreshSessionHandler(
            families,
            ImmediateTransactions(),
            tokens,
            IdentityRateLimiter(rateLimits, TEST_RATE_LIMIT_SCOPE_HASHER),
            sessionIssuer(families, tokens, UUID.randomUUID()),
            currentTime,
        )

        assertThatThrownBy {
            handler.execute("current-refresh-token", UUID(0, 0), "127.0.0.1")
        }.isInstanceOf(InvalidInputException::class.java)

        assertThat(rateLimits.scopes).singleElement().asString().startsWith("refresh:ip:")
        assertThat(families.forUpdateCount).isZero()
    }

    @Test
    fun `exact refresh retry bypasses a saturated family bucket`() {
        val predecessorId = UUID.randomUUID()
        val replacementId = UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID()
        val family = activeFamily(UUID.randomUUID(), predecessorId)
        family.rotateCurrentTo(
            predecessorId,
            replacementId,
            verifierHash("b"),
            idempotencyKey,
            family.expiresAt,
            now.minusSeconds(1),
        )
        val families = InMemoryRefreshFamilies(family)
        val tokens = DeterministicSecretTokens(predecessorId, verifierHash("a"))
        val rateLimits = SaturatedRefreshFamilyRates()
        val handler = RefreshSessionHandler(
            families,
            ImmediateTransactions(),
            tokens,
            IdentityRateLimiter(rateLimits, TEST_RATE_LIMIT_SCOPE_HASHER),
            sessionIssuer(families, tokens, UUID.randomUUID()),
            currentTime,
        )

        val retry = handler.execute("old-refresh-token", idempotencyKey, "127.0.0.1")

        assertThat(retry.refreshToken)
            .isEqualTo("successor:old-refresh-token:$replacementId:$idempotencyKey")
        assertThat(rateLimits.scopes).singleElement().asString().startsWith("refresh:ip:")
        assertThat(families.saveCount).isZero()
    }

    @Test
    fun `exact refresh retry remains subject to the ip ceiling`() {
        val family = activeFamily(UUID.randomUUID(), UUID.randomUUID())
        val families = InMemoryRefreshFamilies(family)
        val rateLimits = SaturatedRefreshIpRates()
        val handler = RefreshSessionHandler(
            families,
            ImmediateTransactions(),
            DeterministicSecretTokens(family.currentCredentialId, verifierHash("a")),
            IdentityRateLimiter(rateLimits, TEST_RATE_LIMIT_SCOPE_HASHER),
            unusedSessionIssuer(),
            currentTime,
        )

        assertThatThrownBy {
            handler.execute("refresh-token", UUID.randomUUID(), "127.0.0.1")
        }.isInstanceOf(RateLimitExceededException::class.java)

        assertThat(rateLimits.scopes).singleElement().asString().startsWith("refresh:ip:")
        assertThat(families.forUpdateCount).isZero()
    }

    @Test
    fun `new refresh rotation is rejected by a saturated family bucket before mutation`() {
        val predecessorId = UUID.randomUUID()
        val family = activeFamily(UUID.randomUUID(), predecessorId)
        val families = InMemoryRefreshFamilies(family)
        val tokens = DeterministicSecretTokens(predecessorId, verifierHash("a"))
        val rateLimits = SaturatedRefreshFamilyRates()
        val handler = RefreshSessionHandler(
            families,
            ImmediateTransactions(),
            tokens,
            IdentityRateLimiter(rateLimits, TEST_RATE_LIMIT_SCOPE_HASHER),
            sessionIssuer(families, tokens, UUID.randomUUID()),
            currentTime,
        )

        assertThatThrownBy {
            handler.execute("current-refresh-token", UUID.randomUUID(), "127.0.0.1")
        }.isInstanceOf(RateLimitExceededException::class.java)

        assertThat(family.currentCredentialId).isEqualTo(predecessorId)
        assertThat(family.credentialSnapshots()).hasSize(1)
        assertThat(families.saveCount).isZero()
        assertThat(rateLimits.scopes).anyMatch { it.startsWith("refresh:family:") }
    }

    @Test
    fun `wrong verifier does not consume the refresh family rate limit`() {
        val family = activeFamily(UUID.randomUUID(), UUID.randomUUID())
        val families = InMemoryRefreshFamilies(family)
        val tokens = DeterministicSecretTokens(family.currentCredentialId, verifierHash("b"))
        val rateLimits = RecordingRateLimits(now)
        val handler = RefreshSessionHandler(
            families = families,
            transactions = ImmediateTransactions(),
            refreshTokens = tokens,
            rateLimiter = IdentityRateLimiter(rateLimits, TEST_RATE_LIMIT_SCOPE_HASHER),
            sessionIssuer = sessionIssuer(families, tokens, UUID.randomUUID()),
            currentTime = currentTime,
        )

        assertThatThrownBy {
            handler.execute("forged-refresh-token", UUID.randomUUID(), "127.0.0.1")
        }.isInstanceOf(InvalidTokenException::class.java)

        assertThat(rateLimits.scopes).anyMatch { it.startsWith("refresh:ip:") }
        assertThat(rateLimits.scopes).noneMatch { it.startsWith("refresh:family:") }
        assertThat(families.forUpdateCount).isZero()
    }

    @Test
    fun `redeemed predecessor with different idempotency key revokes and saves family`() {
        val predecessorId = UUID.randomUUID()
        val replacementId = UUID.randomUUID()
        val originalKey = UUID.randomUUID()
        val family = activeFamily(UUID.randomUUID(), predecessorId)
        family.rotateCurrentTo(
            predecessorId, replacementId, verifierHash("b"), originalKey,
            retryUntil = family.expiresAt,
            now = now.minusSeconds(1),
        )
        val families = InMemoryRefreshFamilies(family)
        val tokens = DeterministicSecretTokens(predecessorId, verifierHash("a"))
        val rateLimits = SaturatedRefreshFamilyRates()
        val handler = RefreshSessionHandler(
            families, ImmediateTransactions(), tokens,
            IdentityRateLimiter(rateLimits, TEST_RATE_LIMIT_SCOPE_HASHER),
            sessionIssuer(families, tokens, UUID.randomUUID()), currentTime,
        )

        assertThatThrownBy { handler.execute("old-refresh-token", UUID.randomUUID(), "127.0.0.1") }
            .isInstanceOf(InvalidTokenException::class.java)
        assertThat(family.status).isEqualTo(RefreshFamilyStatus.REVOKED)
        assertThat(family.revokeReason).isEqualTo(RefreshFamilyRevokeReason.TOKEN_REUSE_DETECTED)
        assertThat(families.saveCount).isEqualTo(1)
        assertThat(rateLimits.scopes).singleElement().asString().startsWith("refresh:ip:")
    }

    @Test
    fun `same-key refresh retry is stale only after its successor rotates`() {
        val predecessorId = UUID.randomUUID()
        val successorId = UUID.randomUUID()
        val key = UUID.randomUUID()
        val family = activeFamily(UUID.randomUUID(), predecessorId)
        family.rotateCurrentTo(
            predecessorId, successorId, verifierHash("b"), key,
            retryUntil = family.expiresAt,
            now = now.minusSeconds(2),
        )
        family.rotateCurrentTo(
            successorId, UUID.randomUUID(), verifierHash("c"), UUID.randomUUID(),
            retryUntil = family.expiresAt,
            now = now.minusSeconds(1),
        )
        val families = InMemoryRefreshFamilies(family)
        val tokens = DeterministicSecretTokens(predecessorId, verifierHash("a"))
        val rateLimits = RecordingRateLimits(now)
        val handler = RefreshSessionHandler(
            families, ImmediateTransactions(), tokens,
            IdentityRateLimiter(rateLimits, TEST_RATE_LIMIT_SCOPE_HASHER),
            sessionIssuer(families, tokens, UUID.randomUUID()), currentTime,
        )

        assertThatThrownBy { handler.execute("old-refresh-token", key, "127.0.0.1") }
            .isInstanceOf(InvalidTokenException::class.java)
        assertThat(family.status).isEqualTo(RefreshFamilyStatus.ACTIVE)
        assertThat(families.saveCount).isZero()
        assertThat(rateLimits.scopes).singleElement().asString().startsWith("refresh:ip:")
    }

    @Test
    fun `login retries password verification against changed credential snapshot`() {
        val id = UUID.randomUUID()
        val old = account(id, "old-hash")
        val current = account(id, "new-hash")
        val accounts = ChangingCredentialAccounts(listOf(old, current), listOf(current, current))
        val matchedHashes = mutableListOf<String>()
        val hashing = object : PasswordHashing {
            override val dummyHash = "dummy"
            override fun encode(password: String) = error("Not used")
            override fun matches(password: String, encoded: String): Boolean {
                matchedHashes += encoded
                return encoded == "new-hash"
            }
        }
        val loginFamilies = InMemoryRefreshFamilies()
        val loginTokens = DeterministicSecretTokens(UUID.randomUUID(), verifierHash("a"))
        val handler = LoginWithEmailHandler(
            accounts, ImmediateTransactions(), PasswordPolicy(), hashing, rateLimiter(),
            sessionIssuer(loginFamilies, loginTokens, UUID.randomUUID()), currentTime,
        )

        val rawDeviceId = "ios-installation-123"
        val issued = handler.execute(current.email.value, "CorrectPassword-123", rawDeviceId, "127.0.0.1")

        assertThat(issued.accountId).isEqualTo(id)
        assertThat(loginFamilies.family?.deviceId).isEqualTo(DeviceId.parse(rawDeviceId))
        assertThat(matchedHashes).containsExactly("old-hash", "new-hash")
        assertThat(accounts.saveCount).isEqualTo(1)
    }

    @Test
    fun `login fails unavailable after credential changes exhaust snapshot retries`() {
        val id = UUID.randomUUID()
        val first = account(id, "hash-1")
        val second = account(id, "hash-2")
        val third = account(id, "hash-3")
        val accounts = ChangingCredentialAccounts(listOf(first, second), listOf(second, third))
        val handler = LoginWithEmailHandler(
            accounts, ImmediateTransactions(), PasswordPolicy(), object : PasswordHashing {
                override val dummyHash = "dummy"
                override fun encode(password: String) = error("Not used")
                override fun matches(password: String, encoded: String) = true
            }, rateLimiter(), unusedSessionIssuer(), currentTime,
        )

        assertThatThrownBy {
            handler.execute(first.email.value, "CorrectPassword-123", null, "127.0.0.1")
        }.isInstanceOf(IdentityUnavailableException::class.java)
        assertThat(accounts.saveCount).isZero()
    }

    @Test
    fun `logout with a redeemed credential revokes the refresh family root`() {
        val familyId = UUID.randomUUID()
        val originalId = UUID.randomUUID()
        val replacementId = UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID()
        val family = activeFamily(familyId, originalId)
        family.rotateCurrentTo(
            presentedCredentialId = originalId,
            replacementCredentialId = replacementId,
            replacementVerifierHash = verifierHash("b"),
            idempotencyKey = idempotencyKey,
            retryUntil = family.expiresAt,
            now = now.minusSeconds(1),
        )
        val families = InMemoryRefreshFamilies(family)
        val tokens = DeterministicSecretTokens(originalId, verifierHash("a"))
        val handler = LogoutHandler(families, ImmediateTransactions(), tokens, rateLimiter(), currentTime)

        handler.execute("valid-rotated-token", "127.0.0.1")

        assertThat(family.status).isEqualTo(RefreshFamilyStatus.REVOKED)
        assertThat(family.revokeReason).isEqualTo(RefreshFamilyRevokeReason.LOGOUT)
        assertThat(family.revokedAt).isEqualTo(now)
        assertThat(families.saveCount).isEqualTo(1)
    }

    private fun rateLimiter() = IdentityRateLimiter(
        repository = object : RateLimitRepository {
            override fun consume(scopeKey: String, window: Duration): RateLimitWindow =
                RateLimitWindow(1, window.seconds)
        },
        scopeHasher = TEST_RATE_LIMIT_SCOPE_HASHER,
    )

    private fun unusedSessionIssuer() = SessionIssuer(
        families = InMemoryRefreshFamilies(),
        tokens = object : RefreshTokenService {
            override fun create(id: UUID): GeneratedSecretToken = error("Unexpected token creation")
            override fun createRefreshSuccessor(
                predecessorRawToken: String,
                replacementId: UUID,
                idempotencyKey: UUID,
            ): GeneratedSecretToken = error("Unexpected successor creation")
            override fun parse(value: String): ParsedSecretToken = error("Unexpected token parsing")
            override fun verifierMatches(expected: VerifierHash, actual: VerifierHash): Boolean = false
        },
        ids = object : IdGenerator {
            override fun next(): UUID = UUID.randomUUID()
        },
        accessTokens = object : AccessTokenProvider {
            override fun issue(accountId: UUID, sessionId: UUID, now: Instant): IssuedAccessToken =
                error("Unexpected issue")
            override fun publicKeys(): List<PublicJwk> = emptyList()
        },
        policy = policy(),
    )

    private fun sessionIssuer(
        families: RefreshFamilyRepository,
        tokens: RefreshTokenService,
        replacementId: UUID,
    ) = SessionIssuer(
        families = families,
        tokens = tokens,
        ids = object : IdGenerator {
            override fun next(): UUID = replacementId
        },
        accessTokens = object : AccessTokenProvider {
            override fun issue(accountId: UUID, sessionId: UUID, now: Instant) =
                IssuedAccessToken("access-token", 900)
            override fun publicKeys(): List<PublicJwk> = emptyList()
        },
        policy = policy(),
    )

    private fun policy() = IdentityPolicy(
        refreshFamilyTtl = Duration.ofDays(30),
        registrationAttemptTtl = Duration.ofHours(24),
        verificationTokenTtl = Duration.ofMinutes(30),
        verificationResendCooldown = Duration.ofMinutes(1),
    )

    private fun activeAccount(failedLoginCount: Int, lockedUntil: Instant?): Account = Account.reconstitute(
        id = UUID.randomUUID(),
        email = CanonicalEmail.parse("user@example.ru"),
        passwordHash = "encoded-password",
        createdAt = now.minusSeconds(100),
        failedLoginCount = failedLoginCount,
        lockedUntil = lockedUntil,
    )

    private fun account(id: UUID, passwordHash: String): Account = Account.reconstitute(
        id = id,
        email = CanonicalEmail.parse("user@example.ru"),
        passwordHash = passwordHash,
        createdAt = now.minusSeconds(100),
        failedLoginCount = 0,
        lockedUntil = null,
    )

    private fun activeFamily(familyId: UUID, credentialId: UUID): RefreshFamily = RefreshFamily.start(
        id = familyId,
        accountId = UUID.randomUUID(),
        firstCredentialId = credentialId,
        firstVerifierHash = verifierHash("a"),
        deviceId = DeviceId.parse("device"),
        expiresAt = now.plusSeconds(3600),
        now = now.minusSeconds(60),
    )

    private fun verifierHash(character: String): VerifierHash =
        VerifierHash.fromSha256Hex(character.repeat(64))

    private class ImmediateTransactions : TransactionRunner {
        var inTransaction: Boolean = false
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

    private class InMemoryAccountRepository(initial: Account? = null) : AccountRepository {
        var account: Account? = initial
            private set
        var addCount: Int = 0
            private set
        var saveCount: Int = 0
            private set

        override fun lockRegistration(email: CanonicalEmail) = Unit
        override fun findByEmail(email: CanonicalEmail): Account? = account?.takeIf { it.email == email }
        override fun findByEmailForUpdate(email: CanonicalEmail): Account? = findByEmail(email)
        override fun findByIdForUpdate(accountId: UUID): Account? = account?.takeIf { it.id == accountId }
        override fun add(account: Account) {
            check(this.account == null)
            this.account = account
            addCount += 1
        }
        override fun save(account: Account) {
            check(this.account?.id == account.id)
            this.account = account
            saveCount += 1
        }
    }

    private class ChangingCredentialAccounts(
        snapshots: List<Account>,
        lockedValues: List<Account>,
    ) : AccountRepository {
        private val snapshots = ArrayDeque(snapshots)
        private val lockedValues = ArrayDeque(lockedValues)
        var saveCount = 0
        override fun lockRegistration(email: CanonicalEmail) = Unit
        override fun findByEmail(email: CanonicalEmail): Account? = snapshots.pollFirst()
        override fun findByEmailForUpdate(email: CanonicalEmail): Account? = lockedValues.pollFirst()
        override fun findByIdForUpdate(accountId: UUID): Account? = error("Not used")
        override fun add(account: Account) = error("Not used")
        override fun save(account: Account) { saveCount++ }
    }

    private class InMemoryRefreshFamilies(initial: RefreshFamily? = null) : RefreshFamilyRepository {
        var family: RefreshFamily? = initial
            private set
        var saveCount: Int = 0
            private set
        var forUpdateCount: Int = 0
            private set

        override fun findCredentialLookup(id: UUID): RefreshCredentialLookup? =
            family?.credentialSnapshots()?.singleOrNull { it.id == id }?.let { credential ->
                RefreshCredentialLookup(credential.familyId, credential.verifierHash)
            }

        override fun findByCredentialIdForUpdate(credentialId: UUID): RefreshFamily? {
            forUpdateCount += 1
            return family?.takeIf { candidate -> candidate.verifierHashFor(credentialId) != null }
        }

        override fun add(family: RefreshFamily) {
            check(this.family == null)
            this.family = family
        }

        override fun save(family: RefreshFamily) {
            check(this.family?.id == family.id)
            this.family = family
            saveCount += 1
        }
    }

    private class DeterministicSecretTokens(
        private val parsedId: UUID,
        private val presentedVerifier: VerifierHash,
    ) : RefreshTokenService {
        override fun create(id: UUID): GeneratedSecretToken = GeneratedSecretToken(
            id,
            "refresh:$id",
            VerifierHash.fromSha256Hex("f".repeat(64)),
        )

        override fun createRefreshSuccessor(
            predecessorRawToken: String,
            replacementId: UUID,
            idempotencyKey: UUID,
        ): GeneratedSecretToken = GeneratedSecretToken(
            replacementId,
            "successor:$predecessorRawToken:$replacementId:$idempotencyKey",
            VerifierHash.fromSha256Hex("c".repeat(64)),
        )

        override fun parse(value: String): ParsedSecretToken = ParsedSecretToken(parsedId, presentedVerifier)

        override fun verifierMatches(expected: VerifierHash, actual: VerifierHash): Boolean = expected == actual
    }

    private class RecordingRateLimits(private val now: Instant) : RateLimitRepository {
        val scopes = mutableListOf<String>()

        override fun consume(scopeKey: String, window: Duration): RateLimitWindow {
            scopes += scopeKey
            return RateLimitWindow(1, window.seconds)
        }
    }

    private class SaturatedRefreshFamilyRates : RateLimitRepository {
        val scopes = mutableListOf<String>()

        override fun consume(scopeKey: String, window: Duration): RateLimitWindow {
            scopes += scopeKey
            return if (scopeKey.startsWith("refresh:family:")) {
                RateLimitWindow(31, window.seconds)
            } else {
                RateLimitWindow(1, window.seconds)
            }
        }
    }

    private class SaturatedRefreshIpRates : RateLimitRepository {
        val scopes = mutableListOf<String>()

        override fun consume(scopeKey: String, window: Duration): RateLimitWindow {
            scopes += scopeKey
            return if (scopeKey.startsWith("refresh:ip:")) {
                RateLimitWindow(121, window.seconds)
            } else {
                RateLimitWindow(1, window.seconds)
            }
        }
    }

    private companion object {
    }
}
