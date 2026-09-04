package com.cookie.identity.application

import com.cookie.identity.application.ports.AccountRepository
import com.cookie.identity.application.ports.CurrentTimeProvider
import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.application.ports.IdentityEventRecorder
import com.cookie.identity.application.ports.RateLimitRepository
import com.cookie.identity.application.ports.RegistrationAttemptRepository
import com.cookie.identity.application.ports.RegistrationSecretService
import com.cookie.identity.application.ports.TransactionRunner
import com.cookie.identity.domain.Account
import com.cookie.identity.domain.AccountActivated
import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.domain.LocaleTag
import com.cookie.identity.domain.NormalizedPassword
import com.cookie.identity.domain.RegistrationAttempt
import com.cookie.identity.domain.RegistrationProof
import com.cookie.identity.domain.VerifierHash
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ConfirmationUseCasesTest {
    private val now = Instant.parse("2026-08-20T10:00:00Z")

    @Test
    fun `exact completed token retry consumes ip guard but bypasses token limit`() {
        var clock = now
        val attempt = attempt(ATTEMPT_1, TOKEN_1, PROOF_HASH_1)
        val repository = InMemoryAttempts(attempt)
        val accounts = InMemoryAccounts()
        val rates = RecordingRates()
        val events = RecordingEvents()
        val handler = handler(repository, accounts, rates, events, CurrentTimeProvider { clock })

        handler.execute(RAW_TOKEN_1, PROOF_1, IP)
        val callsAfterCompletion = rates.scopes.size
        clock = now.plus(Duration.ofDays(2))
        handler.execute(RAW_TOKEN_1, PROOF_1, IP)

        assertThat(callsAfterCompletion).isEqualTo(2)
        assertThat(rates.scopes).hasSize(callsAfterCompletion + 1)
        assertThat(rates.scopes.count { it.startsWith("confirm:ip:") }).isEqualTo(2)
        assertThat(rates.scopes.count { it.startsWith("confirm:token:") }).isEqualTo(1)
        assertThat(accounts.addCount).isEqualTo(1)
        assertThat(repository.saveCount).isEqualTo(1)
        assertThat(events.activations).hasSize(1)
        assertThat(attempt.pendingPasswordHash).isNull()
        assertThat(attempt.locale).isNull()
    }

    @Test
    fun `sibling token is invalid after another child completed the attempt`() {
        val attempt = attempt(ATTEMPT_1, TOKEN_1, PROOF_HASH_1)
        attempt.issueVerificationToken(TOKEN_2, TOKEN_HASH_2, now.plusSeconds(1800), Duration.ZERO, now)
        val repository = InMemoryAttempts(attempt)
        val accounts = InMemoryAccounts()
        val rates = RecordingRates()
        val events = RecordingEvents()
        val secrets = FakeSecrets().also {
            it.tokens[RAW_TOKEN_2] = ParsedEmailVerificationToken(ATTEMPT_1, TOKEN_2, TOKEN_HASH_2)
        }
        val handler = handler(repository, accounts, rates, events, secrets = secrets)

        handler.execute(RAW_TOKEN_1, PROOF_1, IP)
        assertThatThrownBy { handler.execute(RAW_TOKEN_2, PROOF_1, IP) }
            .isInstanceOf(InvalidActionTokenException::class.java)

        assertThat(accounts.addCount).isEqualTo(1)
        assertThat(repository.saveCount).isEqualTo(1)
        assertThat(events.activations).hasSize(1)
    }

    @Test
    fun `first competing attempt wins and losing attempt becomes a scrubbed tombstone`() {
        val winner = attempt(ATTEMPT_1, TOKEN_1, PROOF_HASH_1)
        val loser = attempt(ATTEMPT_2, TOKEN_2, PROOF_HASH_2)
        val repository = InMemoryAttempts(winner, loser)
        val accounts = InMemoryAccounts()
        val events = RecordingEvents()
        val handler = handler(repository, accounts, RecordingRates(), events)

        handler.execute(RAW_TOKEN_1, PROOF_1, IP)
        val abandoned = repository.findById(ATTEMPT_2)
        assertThat(abandoned?.isAbandoned).isTrue()
        assertThat(abandoned?.pendingPasswordHash).isNull()
        assertThat(abandoned?.locale).isNull()
        assertThatThrownBy { handler.execute(RAW_TOKEN_2, PROOF_2, IP) }
            .isInstanceOf(InvalidActionTokenException::class.java)

        assertThat(accounts.addCount).isEqualTo(1)
        assertThat(events.activations).hasSize(1)
    }

    @Test
    fun `wrong token verifier consumes only ip rate limit`() {
        val attempt = attempt(ATTEMPT_1, TOKEN_1, PROOF_HASH_1)
        val rates = RecordingRates()
        val secrets = FakeSecrets().also {
            it.tokens[RAW_TOKEN_1] = ParsedEmailVerificationToken(ATTEMPT_1, TOKEN_1, TOKEN_HASH_2)
        }
        val handler = handler(InMemoryAttempts(attempt), InMemoryAccounts(), rates, RecordingEvents(), secrets = secrets)

        assertThatThrownBy { handler.execute(RAW_TOKEN_1, PROOF_1, IP) }
            .isInstanceOf(InvalidActionTokenException::class.java)
        assertThat(rates.scopes).anyMatch { it.startsWith("confirm:ip:") }
        assertThat(rates.scopes).noneMatch { it.startsWith("confirm:token:") }
    }

    @Test
    fun `malformed registration proof uses the action-token error and only the ip guard`() {
        val rates = RecordingRates()
        val handler = handler(
            InMemoryAttempts(attempt(ATTEMPT_1, TOKEN_1, PROOF_HASH_1)),
            InMemoryAccounts(),
            rates,
            RecordingEvents(),
        )

        assertThatThrownBy { handler.execute(RAW_TOKEN_1, "malformed", IP) }
            .isInstanceOf(InvalidActionTokenException::class.java)
        assertThat(rates.scopes).singleElement().asString().startsWith("confirm:ip:")
    }

    @Test
    fun `wrong proof consumes token rate limit before rejection`() {
        val rates = RecordingRates()
        val handler = handler(InMemoryAttempts(attempt(ATTEMPT_1, TOKEN_1, PROOF_HASH_1)), InMemoryAccounts(), rates, RecordingEvents())

        assertThatThrownBy { handler.execute(RAW_TOKEN_1, PROOF_2, IP) }
            .isInstanceOf(InvalidActionTokenException::class.java)
        assertThat(rates.scopes).anyMatch { it.startsWith("confirm:ip:") }
        assertThat(rates.scopes).anyMatch { it.startsWith("confirm:token:") }
    }

    @Test
    fun `confirmation racing with same presented winner is an idempotent success`() {
        val observed = attempt(ATTEMPT_1, TOKEN_1, PROOF_HASH_1)
        val locked = attempt(ATTEMPT_1, TOKEN_1, PROOF_HASH_1)
        locked.complete(TOKEN_1, true, true, ACCOUNT_ID, now)
        val accounts = InMemoryAccounts().also {
            it.account = Account.register(ACCOUNT_ID, EMAIL, "encoded-password", now).account
        }
        val repository = SnapshotRaceAttempts(observed, locked)
        val events = RecordingEvents()
        val handler = handler(repository, accounts, RecordingRates(), events)

        handler.execute(RAW_TOKEN_1, PROOF_1, IP)

        assertThat(accounts.addCount).isZero()
        assertThat(repository.saveCount).isZero()
        assertThat(events.activations).isEmpty()
    }

    @Test
    fun `confirmation racing with completed sibling is invalid`() {
        val observed = attemptWithSibling()
        val locked = attemptWithSibling().also { it.complete(TOKEN_1, true, true, ACCOUNT_ID, now) }
        val accounts = InMemoryAccounts().also {
            it.account = Account.register(ACCOUNT_ID, EMAIL, "encoded-password", now).account
        }
        val repository = SnapshotRaceAttempts(observed, locked)
        val secrets = FakeSecrets().also {
            it.tokens[RAW_TOKEN_2] = ParsedEmailVerificationToken(ATTEMPT_1, TOKEN_2, TOKEN_HASH_2)
        }
        val handler = handler(repository, accounts, RecordingRates(), RecordingEvents(), secrets = secrets)

        assertThatThrownBy { handler.execute(RAW_TOKEN_2, PROOF_1, IP) }
            .isInstanceOf(InvalidActionTokenException::class.java)
        assertThat(accounts.addCount).isZero()
        assertThat(repository.saveCount).isZero()
    }

    private fun handler(
        attempts: RegistrationAttemptRepository,
        accounts: InMemoryAccounts,
        rates: RecordingRates,
        events: RecordingEvents,
        currentTime: CurrentTimeProvider = CurrentTimeProvider { now },
        secrets: FakeSecrets = FakeSecrets(),
    ) = ConfirmEmailHandler(
        accounts, attempts, ImmediateTransactions(), secrets, FixedId(ACCOUNT_ID),
        IdentityRateLimiter(rates, TEST_RATE_LIMIT_SCOPE_HASHER), events, currentTime,
    )

    private fun attempt(id: UUID, tokenId: UUID, proofHash: VerifierHash) = RegistrationAttempt.start(
        id = id, email = EMAIL, registrationProofHash = proofHash, requestFingerprint = FINGERPRINT,
        locale = LocaleTag.parseOrNull("ru-RU"), pendingPasswordHash = "encoded-password",
        expiresAt = now.plusSeconds(3600), firstTokenId = tokenId,
        firstTokenVerifierHash = if (tokenId == TOKEN_1) TOKEN_HASH_1 else TOKEN_HASH_2,
        firstTokenExpiresAt = now.plusSeconds(1800), now = now.minusSeconds(1),
    )

    private fun attemptWithSibling(): RegistrationAttempt =
        attempt(ATTEMPT_1, TOKEN_1, PROOF_HASH_1).also {
            it.issueVerificationToken(TOKEN_2, TOKEN_HASH_2, now.plusSeconds(1800), Duration.ZERO, now)
        }

    private class FakeSecrets : RegistrationSecretService {
        val tokens = mutableMapOf(
            RAW_TOKEN_1 to ParsedEmailVerificationToken(ATTEMPT_1, TOKEN_1, TOKEN_HASH_1),
            RAW_TOKEN_2 to ParsedEmailVerificationToken(ATTEMPT_2, TOKEN_2, TOKEN_HASH_2),
        )
        override fun createEmailVerificationToken(attemptId: UUID, tokenId: UUID) = error("Not used")
        override fun parseEmailVerificationToken(value: String) = tokens[value] ?: throw InvalidTokenException()
        override fun hashRegistrationProof(proof: RegistrationProof) = when (proof.value) {
            PROOF_1 -> PROOF_HASH_1
            PROOF_2 -> PROOF_HASH_2
            else -> error("Unexpected proof")
        }
        override fun registrationRequestFingerprint(
            proof: RegistrationProof, attemptId: UUID, email: CanonicalEmail,
            password: NormalizedPassword, locale: LocaleTag?,
        ) = error("Not used")
        override fun verifierMatches(expected: VerifierHash, actual: VerifierHash) = expected == actual
    }

    private class InMemoryAttempts(vararg attempts: RegistrationAttempt) : RegistrationAttemptRepository {
        private val values = attempts.associateByTo(linkedMapOf()) { it.id }
        var saveCount = 0
        override fun findById(id: UUID) = values[id]
        override fun findByIdForUpdate(id: UUID) = values[id]
        override fun findByTokenId(tokenId: UUID) = values.values.singleOrNull { it.verificationTokenVerifierHash(tokenId) != null }
        override fun findByTokenIdForUpdate(tokenId: UUID) = findByTokenId(tokenId)
        override fun findByProof(proofHash: VerifierHash) = values.values.singleOrNull {
            it.registrationProofHash == proofHash
        }
        override fun lockCreationKeys(attemptId: UUID, proofHash: VerifierHash) = Unit
        override fun add(attempt: RegistrationAttempt) { values[attempt.id] = attempt }
        override fun save(attempt: RegistrationAttempt) { check(values[attempt.id] === attempt); saveCount++ }
        override fun abandonPendingByEmailExcept(
            email: CanonicalEmail,
            winningAttemptId: UUID,
            now: Instant,
        ): Int {
            val losing = values.values.filter {
                it.email == email && it.id != winningAttemptId && !it.isCompleted && !it.isAbandoned
            }
            losing.forEach { it.abandon(now) }
            return losing.size
        }
    }

    private class SnapshotRaceAttempts(
        private val observed: RegistrationAttempt,
        private val locked: RegistrationAttempt,
    ) : RegistrationAttemptRepository {
        var saveCount = 0
        override fun findById(id: UUID) = observed.takeIf { it.id == id }
        override fun findByIdForUpdate(id: UUID) = locked.takeIf { it.id == id }
        override fun findByTokenId(tokenId: UUID) = observed.takeIf {
            it.verificationTokenVerifierHash(tokenId) != null
        }
        override fun findByTokenIdForUpdate(tokenId: UUID) = locked.takeIf {
            it.verificationTokenVerifierHash(tokenId) != null
        }
        override fun findByProof(proofHash: VerifierHash) = observed.takeIf {
            it.registrationProofHash == proofHash
        }
        override fun lockCreationKeys(attemptId: UUID, proofHash: VerifierHash) = Unit
        override fun add(attempt: RegistrationAttempt) = error("Not used")
        override fun save(attempt: RegistrationAttempt) { saveCount++ }
        override fun abandonPendingByEmailExcept(
            email: CanonicalEmail,
            winningAttemptId: UUID,
            now: Instant,
        ): Int = error("Not used")
    }

    private class InMemoryAccounts : AccountRepository {
        var account: Account? = null
        var addCount = 0
        override fun lockRegistration(email: CanonicalEmail) = Unit
        override fun findByEmail(email: CanonicalEmail) = account?.takeIf { it.email == email }
        override fun findByEmailForUpdate(email: CanonicalEmail) = findByEmail(email)
        override fun findByIdForUpdate(accountId: UUID) = account?.takeIf { it.id == accountId }
        override fun add(account: Account) { check(this.account == null); this.account = account; addCount++ }
        override fun save(account: Account) = error("Not used")
    }

    private class RecordingRates : RateLimitRepository {
        val scopes = mutableListOf<String>()
        override fun consume(scopeKey: String, window: Duration): RateLimitWindow {
            scopes += scopeKey
            return RateLimitWindow(1, window.seconds)
        }
    }

    private class RecordingEvents : IdentityEventRecorder {
        val activations = mutableListOf<AccountActivated>()
        override fun verificationRequested(
            registrationAttemptId: UUID, email: CanonicalEmail, locale: LocaleTag?, rawToken: String,
            expiresAt: Instant, now: Instant,
        ) = error("Not used")
        override fun accountActivated(event: AccountActivated) { activations += event }
    }

    private class ImmediateTransactions : TransactionRunner {
        override fun <T : Any> required(block: () -> T) = block()
        override fun requiredUnit(block: () -> Unit) = block()
    }
    private class FixedId(private val id: UUID) : IdGenerator { override fun next() = id }

    private companion object {
        val ATTEMPT_1: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val ATTEMPT_2: UUID = UUID.fromString("10000000-0000-0000-0000-000000000002")
        val TOKEN_1: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
        val TOKEN_2: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val ACCOUNT_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000001")
        val EMAIL: CanonicalEmail = CanonicalEmail.parse("pending@example.ru")
        val TOKEN_HASH_1: VerifierHash = VerifierHash.fromSha256Hex("a".repeat(64))
        val TOKEN_HASH_2: VerifierHash = VerifierHash.fromSha256Hex("b".repeat(64))
        val PROOF_HASH_1: VerifierHash = VerifierHash.fromSha256Hex("c".repeat(64))
        val PROOF_HASH_2: VerifierHash = VerifierHash.fromSha256Hex("d".repeat(64))
        val FINGERPRINT: VerifierHash = VerifierHash.fromSha256Hex("e".repeat(64))
        const val PROOF_1 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val PROOF_2 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE"
        const val RAW_TOKEN_1 = "token-1"
        const val RAW_TOKEN_2 = "token-2"
        const val IP = "127.0.0.1"
    }
}
