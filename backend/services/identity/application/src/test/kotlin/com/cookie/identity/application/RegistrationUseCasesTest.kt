package com.cookie.identity.application

import com.cookie.identity.application.ports.AccountRepository
import com.cookie.identity.application.ports.CurrentTimeProvider
import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.application.ports.IdentityEventRecorder
import com.cookie.identity.application.ports.PasswordHashing
import com.cookie.identity.application.ports.RateLimitRepository
import com.cookie.identity.application.ports.RegistrationAttemptRepository
import com.cookie.identity.application.ports.RegistrationSecretService
import com.cookie.identity.application.ports.TransactionRunner
import com.cookie.identity.domain.Account
import com.cookie.identity.domain.AccountActivated
import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.domain.LocaleTag
import com.cookie.identity.domain.NormalizedPassword
import com.cookie.identity.domain.PasswordPolicy
import com.cookie.identity.domain.RegistrationAttempt
import com.cookie.identity.domain.RegistrationProof
import com.cookie.identity.domain.RussianEmailAdmissionPolicy
import com.cookie.identity.domain.VerifierHash
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID

class RegistrationUseCasesTest {
    private val now = Instant.parse("2026-08-20T10:00:00Z")

    @Test
    fun `exact register retry consumes ip guard but bypasses email limit and password hashing`() {
        val fixture = Fixture()

        fixture.register()
        fixture.register()

        assertThat(fixture.rateLimits.scopes).hasSize(3)
        assertThat(fixture.rateLimits.scopes.count { it.startsWith("register:ip:") }).isEqualTo(2)
        assertThat(fixture.rateLimits.scopes.count { it.startsWith("register:email:") }).isEqualTo(1)
        assertThat(fixture.hashing.encodeCount).isEqualTo(1)
        assertThat(fixture.attempts.addCount).isEqualTo(1)
        assertThat(fixture.events.requests).hasSize(1)
    }

    @Test
    fun `same attempt and proof with changed password conflicts`() {
        val fixture = Fixture()
        fixture.register()

        assertThatThrownBy { fixture.register(password = "AnotherPassword-123") }
            .isInstanceOf(RegistrationAttemptConflictException::class.java)
        assertThat(fixture.attempts.addCount).isEqualTo(1)
    }

    @Test
    fun `same attempt and proof with changed locale conflicts`() {
        val fixture = Fixture()
        fixture.register(locale = "ru-RU")

        assertThatThrownBy { fixture.register(locale = "en-US") }
            .isInstanceOf(RegistrationAttemptConflictException::class.java)
        assertThat(fixture.attempts.addCount).isEqualTo(1)
    }

    @Test
    fun `completed attempt with changed payload still conflicts when account exists`() {
        val fixture = Fixture()
        fixture.register()
        fixture.completeRegistration()

        assertThatThrownBy { fixture.register(password = "AnotherPassword-123") }
            .isInstanceOf(RegistrationAttemptConflictException::class.java)
    }

    @Test
    fun `same proof cannot be reused with another attempt or email`() {
        val fixture = Fixture()
        fixture.register()

        assertThatThrownBy {
            fixture.register(attemptId = ATTEMPT_ID_2, email = "other@example.ru")
        }.isInstanceOf(RegistrationAttemptConflictException::class.java)

        assertThat(fixture.attempts.addCount).isEqualTo(1)
    }

    @Test
    fun `resend adds a token child to the same aggregate`() {
        val fixture = Fixture()
        fixture.register()
        fixture.clock = now.plusSeconds(61)

        fixture.resend()

        val attempt = fixture.attempts.findById(ATTEMPT_ID)!!
        assertThat(attempt.verificationTokenSnapshots()).hasSize(2)
        assertThat(fixture.attempts.addCount).isEqualTo(1)
        assertThat(fixture.attempts.saveCount).isEqualTo(1)
        assertThat(fixture.events.requests).hasSize(2)
    }

    @Test
    fun `exact resend retry during cooldown consumes ip guard but bypasses email limits`() {
        val fixture = Fixture()
        fixture.register()
        fixture.clock = now.plusSeconds(61)
        fixture.resend()
        val afterFirstResend = fixture.rateLimits.scopes.size

        fixture.resend()

        assertThat(afterFirstResend).isEqualTo(5)
        assertThat(fixture.rateLimits.scopes).hasSize(afterFirstResend + 1)
        assertThat(fixture.rateLimits.scopes.last()).startsWith("resend:ip:")
        assertThat(fixture.attempts.saveCount).isEqualTo(1)
        assertThat(fixture.events.requests).hasSize(2)
    }

    @Test
    fun `expired registration root cannot resend even before cleanup`() {
        val fixture = Fixture(policy = policy(attemptTtl = Duration.ofMinutes(2)))
        fixture.register()
        fixture.clock = now.plusSeconds(121)

        fixture.resend()

        assertThat(fixture.rateLimits.scopes).hasSize(3)
        assertThat(fixture.attempts.findById(ATTEMPT_ID)?.verificationTokenSnapshots()).hasSize(1)
        assertThat(fixture.attempts.saveCount).isZero()
    }

    @Test
    fun `exact register retry after attempt expiry is a no-op until client starts a new attempt`() {
        val fixture = Fixture(policy = policy(attemptTtl = Duration.ofMinutes(2)))
        fixture.register()
        fixture.clock = now.plusSeconds(121)

        fixture.register()

        assertThat(fixture.rateLimits.scopes).hasSize(3)
        assertThat(fixture.hashing.encodeCount).isEqualTo(1)
        assertThat(fixture.attempts.addCount).isEqualTo(1)
    }

    @Test
    fun `scrubbed expired tombstone keeps exact registration retry a no-op`() {
        val fixture = Fixture(policy = policy(attemptTtl = Duration.ofMinutes(2)))
        fixture.register()
        fixture.clock = now.plusSeconds(121)
        fixture.abandonAttempt()

        fixture.register()

        assertThat(fixture.rateLimits.scopes).hasSize(3)
        assertThat(fixture.hashing.encodeCount).isEqualTo(1)
        assertThat(fixture.attempts.addCount).isEqualTo(1)
        assertThat(fixture.attempts.findById(ATTEMPT_ID)?.isAbandoned).isTrue()
    }

    private inner class Fixture(private val policy: IdentityPolicy = policy()) {
        var clock: Instant = now
        val attempts = InMemoryAttempts()
        val accounts = MutableAccounts()
        val secrets = DeterministicRegistrationSecrets()
        val rateLimits = RecordingRateLimits()
        val hashing = RecordingPasswordHashing()
        val events = RecordingEvents()
        private val issuer = RegistrationAttemptIssuer(
            attempts, secrets, SequenceIds(TOKEN_1, TOKEN_2), events, policy,
        )
        private val register = RegisterWithEmailHandler(
            accounts, attempts, ImmediateTransactions(), RussianEmailAdmissionPolicy(), PasswordPolicy(),
            hashing, secrets, IdentityRateLimiter(rateLimits, TEST_RATE_LIMIT_SCOPE_HASHER),
            issuer, CurrentTimeProvider { clock },
        )
        private val resend = ResendEmailVerificationHandler(
            accounts, attempts, ImmediateTransactions(),
            IdentityRateLimiter(rateLimits, TEST_RATE_LIMIT_SCOPE_HASHER), secrets,
            issuer, policy, CurrentTimeProvider { clock },
        )

        fun register(
            attemptId: UUID = ATTEMPT_ID,
            email: String = EMAIL,
            password: String = PASSWORD,
            locale: String? = "ru-RU",
        ) = register.execute(
            attemptId, email, password, PROOF, locale, IP,
        )

        fun resend() = resend.execute(ATTEMPT_ID, EMAIL, PROOF, IP)

        fun completeRegistration() {
            val account = Account.register(ACCOUNT_ID, CanonicalEmail.parse(EMAIL), "encoded-password", clock).account
            accounts.account = account
            attempts.findById(ATTEMPT_ID)!!.complete(
                TOKEN_1,
                tokenVerifierMatches = true,
                registrationProofMatches = true,
                accountId = account.id,
                now = clock.plusSeconds(1),
            )
        }

        fun abandonAttempt() {
            attempts.findById(ATTEMPT_ID)!!.abandon(clock)
        }
    }

    private class InMemoryAttempts : RegistrationAttemptRepository {
        private val values = linkedMapOf<UUID, RegistrationAttempt>()
        var addCount = 0
        var saveCount = 0
        override fun findById(id: UUID) = values[id]
        override fun findByIdForUpdate(id: UUID) = values[id]
        override fun findByTokenId(tokenId: UUID) = values.values.singleOrNull {
            it.verificationTokenVerifierHash(tokenId) != null
        }
        override fun findByTokenIdForUpdate(tokenId: UUID) = findByTokenId(tokenId)
        override fun findByProof(proofHash: VerifierHash) = values.values.singleOrNull {
            it.registrationProofHash == proofHash
        }
        override fun lockCreationKeys(attemptId: UUID, proofHash: VerifierHash) = Unit
        override fun add(attempt: RegistrationAttempt) { values[attempt.id] = attempt; addCount++ }
        override fun save(attempt: RegistrationAttempt) { check(values[attempt.id] === attempt); saveCount++ }
        override fun abandonPendingByEmailExcept(email: CanonicalEmail, winningAttemptId: UUID, now: Instant): Int = 0
    }

    private class DeterministicRegistrationSecrets : RegistrationSecretService {
        override fun createEmailVerificationToken(attemptId: UUID, tokenId: UUID) =
            GeneratedEmailVerificationToken(attemptId, tokenId, "token:$tokenId", hash("token:$tokenId"))
        override fun parseEmailVerificationToken(value: String): ParsedEmailVerificationToken = error("Not used")
        override fun hashRegistrationProof(proof: RegistrationProof) = hash("proof:${proof.value}")
        override fun registrationRequestFingerprint(
            proof: RegistrationProof, attemptId: UUID, email: CanonicalEmail,
            password: NormalizedPassword, locale: LocaleTag?,
        ) = hash("${proof.value}|$attemptId|${email.value}|${password.value}|${locale?.value}")
        override fun verifierMatches(expected: VerifierHash, actual: VerifierHash) = expected == actual
    }

    private class RecordingPasswordHashing : PasswordHashing {
        override val dummyHash = "dummy"
        var encodeCount = 0
        override fun encode(password: String): String { encodeCount++; return "encoded:$password" }
        override fun matches(password: String, encoded: String) = error("Not used")
    }

    private class RecordingRateLimits : RateLimitRepository {
        val scopes = mutableListOf<String>()
        override fun consume(scopeKey: String, window: Duration): RateLimitWindow {
            scopes += scopeKey
            return RateLimitWindow(1, window.seconds)
        }
    }

    private class RecordingEvents : IdentityEventRecorder {
        val requests = mutableListOf<String>()
        override fun verificationRequested(
            registrationAttemptId: UUID, email: CanonicalEmail, locale: LocaleTag?, rawToken: String,
            expiresAt: Instant, now: Instant,
        ) { requests += rawToken }
        override fun accountActivated(event: AccountActivated) = error("Not used")
    }

    private class SequenceIds(vararg ids: UUID) : IdGenerator {
        private val ids = ArrayDeque(ids.toList())
        override fun next(): UUID = ids.removeFirst()
    }

    private class ImmediateTransactions : TransactionRunner {
        override fun <T : Any> required(block: () -> T) = block()
        override fun requiredUnit(block: () -> Unit) = block()
    }

    private class MutableAccounts : AccountRepository {
        var account: Account? = null
        override fun lockRegistration(email: CanonicalEmail) = Unit
        override fun findByEmail(email: CanonicalEmail): Account? = account?.takeIf { it.email == email }
        override fun findByEmailForUpdate(email: CanonicalEmail): Account? = findByEmail(email)
        override fun findByIdForUpdate(accountId: UUID): Account? = account?.takeIf { it.id == accountId }
        override fun add(account: Account) { check(this.account == null); this.account = account }
        override fun save(account: Account) = error("Not used")
    }

    private fun policy(attemptTtl: Duration = Duration.ofHours(24)) = IdentityPolicy(
        refreshFamilyTtl = Duration.ofDays(30), registrationAttemptTtl = attemptTtl,
        verificationTokenTtl = minOf(Duration.ofMinutes(30), attemptTtl),
        verificationResendCooldown = Duration.ofMinutes(1),
    )

    private companion object {
        val ATTEMPT_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val ATTEMPT_ID_2: UUID = UUID.fromString("10000000-0000-0000-0000-000000000002")
        val TOKEN_1: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
        val TOKEN_2: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val ACCOUNT_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000001")
        const val EMAIL = "pending@example.ru"
        const val PASSWORD = "CorrectPassword-123"
        const val PROOF = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val IP = "127.0.0.1"

        fun hash(value: String): VerifierHash = VerifierHash.fromSha256Hex(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) },
        )
    }
}
