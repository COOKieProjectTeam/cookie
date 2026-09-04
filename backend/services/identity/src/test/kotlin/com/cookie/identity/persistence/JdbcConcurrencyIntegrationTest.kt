package com.cookie.identity.persistence

import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.domain.LocaleTag
import com.cookie.identity.domain.RegistrationAttempt
import com.cookie.identity.domain.VerifierHash
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcConcurrencyIntegrationTest {
    private lateinit var dataSource: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactions: TransactionTemplate

    @BeforeAll
    fun migrate() {
        dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(dataSource).load().migrate()
        jdbc = JdbcTemplate(dataSource)
        transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
    }

    @BeforeEach
    fun cleanState() {
        jdbc.update(
            "TRUNCATE TABLE outbox_events, rate_limit_buckets, registration_verification_tokens, " +
                "registration_attempts, " +
                "refresh_credentials, refresh_families, email_credentials, accounts",
        )
    }

    @Test
    fun `concurrent rate limit consumption assigns every attempt exactly once`() {
        val repository = JdbcRateLimitRepository(jdbc)
        val start = CyclicBarrier(CONCURRENT_ATTEMPTS)
        val executor = Executors.newFixedThreadPool(CONCURRENT_ATTEMPTS)
        try {
            val attempts = (1..CONCURRENT_ATTEMPTS).map {
                CompletableFuture.supplyAsync(
                    {
                        start.await()
                        repository.consume("concurrent-rate-limit", Duration.ofMinutes(1)).attemptCount
                    },
                    executor,
                )
            }

            assertThat(attempts.map { it.join() }.sorted())
                .containsExactlyElementsOf((1..CONCURRENT_ATTEMPTS).toList())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `reset rate limit window uses one database timestamp`() {
        val repository = JdbcRateLimitRepository(jdbc)
        jdbc.update(
            """
            INSERT INTO rate_limit_buckets(scope_key, window_started_at, attempt_count, expires_at)
            VALUES ('expired-rate-limit', statement_timestamp() - interval '2 hours', 7,
                    statement_timestamp() - interval '1 hour')
            """.trimIndent(),
        )

        val window = repository.consume("expired-rate-limit", Duration.ofMinutes(1))
        val storedSeconds = requireNotNull(
            jdbc.queryForObject(
                """
                SELECT EXTRACT(EPOCH FROM (expires_at - window_started_at))::bigint
                FROM rate_limit_buckets WHERE scope_key = 'expired-rate-limit'
                """.trimIndent(),
                Long::class.java,
            ),
        )

        assertThat(window.attemptCount).isEqualTo(1)
        assertThat(storedSeconds).isEqualTo(60L)
    }

    @Test
    fun `stale outbox claimant cannot publish and cleanup skips rows locked by another replica`() {
        val repository = JdbcOutboxRepository(jdbc)
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val eventId = insertOutbox(now.minusSeconds(120), publishedAt = null)
        val initialBacklog = repository.backlogSnapshot()
        assertThat(initialBacklog.pendingCount).isEqualTo(1)
        assertThat(initialBacklog.oldestPendingAgeSeconds).isGreaterThanOrEqualTo(119.0)
        val firstClaim = repository.claim(1, Duration.ofMinutes(1), UUID.randomUUID()).single()
        jdbc.update(
            "UPDATE outbox_events SET claimed_until = statement_timestamp() - interval '1 second' WHERE event_id = ?",
            eventId,
        )
        val secondClaim = repository.claim(1, Duration.ofMinutes(1), UUID.randomUUID()).single()

        assertThat(repository.markPublished(eventId, firstClaim.claimId)).isFalse()
        assertThat(repository.release(eventId, firstClaim.claimId, Duration.ZERO, "stale claimant")).isNull()
        assertThat(repository.markPublished(eventId, secondClaim.claimId)).isTrue()

        repeat(PUBLISHED_EVENTS) { offset ->
            insertOutbox(now.minusSeconds(120L + offset), publishedAt = now.minusSeconds(60))
        }
        val cleanupCutoff = now.plusSeconds(60)
        dataSource.connection.use { blocker ->
            blocker.autoCommit = false
            try {
                blocker.prepareStatement(
                    """
                    SELECT event_id
                    FROM outbox_events
                    WHERE published_at < ?
                    ORDER BY published_at, event_id
                    LIMIT ?
                    FOR UPDATE
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, cleanupCutoff.asJdbcTimestamp())
                    statement.setInt(2, PUBLISHED_EVENTS)
                    statement.executeQuery().use { rows ->
                        var lockedRows = 0
                        while (rows.next()) lockedRows += 1
                        assertThat(lockedRows).isEqualTo(PUBLISHED_EVENTS)
                    }
                }

                val concurrentCleanup = CompletableFuture.supplyAsync {
                    repository.deletePublishedBefore(cleanupCutoff, PUBLISHED_EVENTS)
                }
                assertThat(concurrentCleanup.get(5, TimeUnit.SECONDS)).isEqualTo(1)
            } finally {
                blocker.rollback()
            }
        }

        assertThat(repository.deletePublishedBefore(cleanupCutoff, PUBLISHED_EVENTS)).isEqualTo(PUBLISHED_EVENTS)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Int::class.java)).isZero()
    }

    @Test
    fun `registration retention scrubs expired attempts before bounded tombstone deletion`() {
        val repository = JdbcMaintenanceRepository(jdbc)
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val oldAccountId = insertAccount(now.minus(40, ChronoUnit.DAYS))
        val recentAccountId = insertAccount(now.minus(2, ChronoUnit.DAYS))
        val expiredPendingId = insertRegistrationAttempt(
            createdAt = now.minus(2, ChronoUnit.HOURS),
            expiresAt = now.minus(1, ChronoUnit.HOURS),
        )
        val livePendingId = insertRegistrationAttempt(
            createdAt = now.minus(10, ChronoUnit.MINUTES),
            expiresAt = now.plus(20, ChronoUnit.MINUTES),
        )
        val oldAbandonedId = insertRegistrationAttempt(
            createdAt = now.minus(40, ChronoUnit.DAYS),
            expiresAt = now.minus(40, ChronoUnit.DAYS).plus(30, ChronoUnit.MINUTES),
            abandonedAt = now.minus(40, ChronoUnit.DAYS).plus(31, ChronoUnit.MINUTES),
        )
        val recentAbandonedId = insertRegistrationAttempt(
            createdAt = now.minus(2, ChronoUnit.DAYS),
            expiresAt = now.minus(2, ChronoUnit.DAYS).plus(30, ChronoUnit.MINUTES),
            abandonedAt = now.minus(2, ChronoUnit.DAYS).plus(31, ChronoUnit.MINUTES),
        )
        val oldCompletedId = insertRegistrationAttempt(
            createdAt = now.minus(40, ChronoUnit.DAYS),
            expiresAt = now.minus(40, ChronoUnit.DAYS).plus(30, ChronoUnit.MINUTES),
            completedAt = now.minus(40, ChronoUnit.DAYS).plus(1, ChronoUnit.MINUTES),
            activatedAccountId = oldAccountId,
        )
        val recentCompletedId = insertRegistrationAttempt(
            createdAt = now.minus(2, ChronoUnit.DAYS),
            expiresAt = now.minus(2, ChronoUnit.DAYS).plus(30, ChronoUnit.MINUTES),
            completedAt = now.minus(2, ChronoUnit.DAYS).plus(1, ChronoUnit.MINUTES),
            activatedAccountId = recentAccountId,
        )

        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM registration_verification_tokens", Int::class.java),
        ).isEqualTo(6)
        assertThat(jdbc.update("DELETE FROM accounts WHERE id = ?", recentAccountId)).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM registration_attempts WHERE id = ?",
                Int::class.java,
                recentCompletedId,
            ),
        ).isEqualTo(1)

        assertThat(repository.abandonExpiredRegistrationAttempts(now, batchSize = 100)).isEqualTo(1)
        assertThat(repository.abandonExpiredRegistrationAttempts(now, batchSize = 100)).isZero()
        assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*)
                FROM registration_attempts
                WHERE id = ? AND abandoned_at = ?
                  AND pending_password_hash IS NULL AND locale IS NULL
                """.trimIndent(),
                Int::class.java,
                expiredPendingId,
                now.asJdbcTimestamp(),
            ),
        ).isEqualTo(1)

        val deleted = repository.deleteRegistrationAttemptTombstones(
            abandonedBefore = now.minus(30, ChronoUnit.DAYS),
            completedBefore = now.minus(30, ChronoUnit.DAYS),
            batchSize = 100,
        )

        assertThat(deleted).isEqualTo(2)
        val remaining = jdbc.queryForList("SELECT id FROM registration_attempts ORDER BY id", UUID::class.java)
        assertThat(remaining).containsExactlyInAnyOrder(
            expiredPendingId,
            livePendingId,
            recentAbandonedId,
            recentCompletedId,
        )
        assertThat(remaining).doesNotContain(oldAbandonedId, oldCompletedId)
        val remainingTokenParents = jdbc.queryForList(
            "SELECT attempt_id FROM registration_verification_tokens ORDER BY attempt_id",
            UUID::class.java,
        )
        assertThat(remainingTokenParents).containsExactlyInAnyOrder(
            expiredPendingId,
            livePendingId,
            recentAbandonedId,
            recentCompletedId,
        )
    }

    @Test
    fun `registration repository saves one aggregate and abandons losing secrets after completion`() {
        val repository = JdbcRegistrationAttemptRepository(jdbc)
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val email = CanonicalEmail.reconstitute("repository@example.ru")
        val winningAttempt = newRegistrationAttempt(email, now)
        val losingAttempt = newRegistrationAttempt(email, now.plusSeconds(1))

        transactions.executeWithoutResult {
            repository.add(winningAttempt)
            repository.add(losingAttempt)
        }

        assertThat(repository.findByProof(winningAttempt.registrationProofHash)?.id)
            .isEqualTo(winningAttempt.id)
        assertThat(repository.findByProof(losingAttempt.registrationProofHash)?.id)
            .isEqualTo(losingAttempt.id)

        val secondTokenId = UUID.randomUUID()
        transactions.executeWithoutResult {
            val locked = requireNotNull(repository.findByIdForUpdate(winningAttempt.id))
            locked.issueVerificationToken(
                id = secondTokenId,
                verifierHash = VerifierHash.fromSha256Hex("d".repeat(64)),
                expiresAt = now.plus(40, ChronoUnit.MINUTES),
                cooldown = Duration.ZERO,
                now = now.plus(10, ChronoUnit.MINUTES),
            )
            repository.save(locked)
        }
        assertThat(repository.findByTokenId(secondTokenId)?.verificationTokenSnapshots()).hasSize(2)

        val accountId = UUID.randomUUID()
        val abandonedLosers = requireNotNull(
            transactions.execute {
                jdbc.update(
                    "INSERT INTO accounts(id, created_at) VALUES (?, ?)",
                    accountId,
                    now.plus(20, ChronoUnit.MINUTES).asJdbcTimestamp(),
                )
                val locked = requireNotNull(repository.findByTokenIdForUpdate(secondTokenId))
                locked.complete(
                    tokenId = secondTokenId,
                    tokenVerifierMatches = true,
                    registrationProofMatches = true,
                    accountId = accountId,
                    now = now.plus(20, ChronoUnit.MINUTES),
                )
                repository.save(locked)
                repository.abandonPendingByEmailExcept(email, locked.id, now.plus(20, ChronoUnit.MINUTES))
            },
        )

        assertThat(abandonedLosers).isEqualTo(1)
        val completed = requireNotNull(repository.findById(winningAttempt.id))
        assertThat(completed.isCompleted).isTrue()
        assertThat(completed.pendingPasswordHash).isNull()
        assertThat(completed.locale).isNull()
        assertThat(completed.verificationTokenSnapshots().count { it.isRedeemed }).isEqualTo(1)
        val abandoned = requireNotNull(repository.findById(losingAttempt.id))
        assertThat(abandoned.isAbandoned).isTrue()
        assertThat(abandoned.pendingPasswordHash).isNull()
        assertThat(abandoned.locale).isNull()
        assertThat(repository.findByProof(losingAttempt.registrationProofHash)?.id).isEqualTo(losingAttempt.id)
        assertThat(
            transactions.execute {
                repository.abandonPendingByEmailExcept(
                    email,
                    winningAttempt.id,
                    now.plus(21, ChronoUnit.MINUTES),
                )
            },
        ).isZero()
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM registration_verification_tokens WHERE attempt_id = ?",
                Int::class.java,
                losingAttempt.id,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `registration proof is globally unique across email addresses`() {
        val repository = JdbcRegistrationAttemptRepository(jdbc)
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val proofHash = VerifierHash.fromSha256Hex("e".repeat(64))
        val first = newRegistrationAttempt(
            CanonicalEmail.reconstitute("first@example.ru"),
            now,
            proofHash,
        )
        val conflicting = newRegistrationAttempt(
            CanonicalEmail.reconstitute("second@example.ru"),
            now.plusSeconds(1),
            proofHash,
        )

        transactions.executeWithoutResult { repository.add(first) }

        assertThat(repository.findByProof(proofHash)?.id).isEqualTo(first.id)
        assertThatThrownBy {
            transactions.executeWithoutResult { repository.add(conflicting) }
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(repository.findByProof(proofHash)?.id).isEqualTo(first.id)
    }

    @Test
    fun `registration creation locks both client id and proof for the transaction`() {
        val repository = JdbcRegistrationAttemptRepository(jdbc)
        val attemptId = UUID.randomUUID()
        val proofHash = VerifierHash.fromSha256Hex("e".repeat(64))
        val locksAcquired = CountDownLatch(1)
        val releaseLocks = CountDownLatch(1)
        val holder = CompletableFuture.runAsync {
            transactions.executeWithoutResult {
                repository.lockCreationKeys(attemptId, proofHash)
                locksAcquired.countDown()
                check(releaseLocks.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release advisory locks" }
            }
        }

        try {
            assertThat(locksAcquired.await(5, TimeUnit.SECONDS)).isTrue()
            dataSource.connection.use { contender ->
                contender.autoCommit = false
                try {
                    assertThat(
                        contender.canAcquireAdvisoryLock("identity:registration-attempt:$attemptId"),
                    ).isFalse()
                    assertThat(
                        contender.canAcquireAdvisoryLock("identity:registration-proof:${proofHash.value}"),
                    ).isFalse()
                } finally {
                    contender.rollback()
                }
            }
        } finally {
            releaseLocks.countDown()
            holder.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `registration token database constraints enforce root expiry copy lifetime and single redemption`() {
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val attemptExpiresAt = now.plus(1, ChronoUnit.HOURS)
        val attemptId = insertRegistrationAttempt(now, attemptExpiresAt)

        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO registration_verification_tokens(
                    id, attempt_id, attempt_expires_at, verifier_hash,
                    issued_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                attemptId,
                attemptExpiresAt.asJdbcTimestamp(),
                "f".repeat(64),
                now.plus(10, ChronoUnit.MINUTES).asJdbcTimestamp(),
                attemptExpiresAt.plusSeconds(1).asJdbcTimestamp(),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO registration_verification_tokens(
                    id, attempt_id, attempt_expires_at, verifier_hash,
                    issued_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                attemptId,
                attemptExpiresAt.plusSeconds(1).asJdbcTimestamp(),
                "f".repeat(64),
                now.plus(10, ChronoUnit.MINUTES).asJdbcTimestamp(),
                now.plus(20, ChronoUnit.MINUTES).asJdbcTimestamp(),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        assertThatThrownBy {
            jdbc.update(
                "UPDATE registration_verification_tokens SET redeemed_at = ? WHERE attempt_id = ?",
                now.asJdbcTimestamp(),
                attemptId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        // insertRegistrationAttempt issues the child one second after `now` and gives it a 30-minute lifetime.
        val tokenExpiresAt = now.plus(30, ChronoUnit.MINUTES).plusSeconds(1)
        assertThatThrownBy {
            jdbc.update(
                "UPDATE registration_verification_tokens SET redeemed_at = ? WHERE attempt_id = ?",
                tokenExpiresAt.asJdbcTimestamp(),
                attemptId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        assertThat(
            jdbc.update(
                "UPDATE registration_verification_tokens SET redeemed_at = ? WHERE attempt_id = ?",
                now.plus(10, ChronoUnit.MINUTES).asJdbcTimestamp(),
                attemptId,
            ),
        ).isEqualTo(1)
        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO registration_verification_tokens(
                    id, attempt_id, attempt_expires_at, verifier_hash,
                    issued_at, expires_at, redeemed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                attemptId,
                attemptExpiresAt.asJdbcTimestamp(),
                "a".repeat(64),
                now.plus(2, ChronoUnit.MINUTES).asJdbcTimestamp(),
                now.plus(20, ChronoUnit.MINUTES).asJdbcTimestamp(),
                now.plus(15, ChronoUnit.MINUTES).asJdbcTimestamp(),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun insertOutbox(occurredAt: Instant, publishedAt: Instant?): UUID {
        val eventId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO outbox_events(
                event_id, event_type, event_version, aggregate_type, aggregate_id,
                payload, occurred_at, available_at, published_at
            ) VALUES (?, 'test.event', 1, 'test', ?, '{}'::jsonb, ?, ?, ?)
            """.trimIndent(),
            eventId,
            eventId.toString(),
            occurredAt.asJdbcTimestamp(),
            occurredAt.asJdbcTimestamp(),
            publishedAt?.asJdbcTimestamp(),
        )
        return eventId
    }

    private fun insertAccount(createdAt: Instant): UUID = UUID.randomUUID().also { id ->
        jdbc.update("INSERT INTO accounts(id, created_at) VALUES (?, ?)", id, createdAt.asJdbcTimestamp())
    }

    private fun newRegistrationAttempt(
        email: CanonicalEmail,
        now: Instant,
        proofHash: VerifierHash = uniqueVerifierHash(),
    ): RegistrationAttempt =
        RegistrationAttempt.start(
            id = UUID.randomUUID(),
            email = email,
            registrationProofHash = proofHash,
            requestFingerprint = VerifierHash.fromSha256Hex("b".repeat(64)),
            locale = LocaleTag.parse("ru-RU"),
            pendingPasswordHash = "argon-hash",
            expiresAt = now.plus(1, ChronoUnit.HOURS),
            firstTokenId = UUID.randomUUID(),
            firstTokenVerifierHash = VerifierHash.fromSha256Hex("c".repeat(64)),
            firstTokenExpiresAt = now.plus(30, ChronoUnit.MINUTES),
            now = now,
        )

    private fun insertRegistrationAttempt(
        createdAt: Instant,
        expiresAt: Instant,
        completedAt: Instant? = null,
        abandonedAt: Instant? = null,
        activatedAccountId: UUID? = null,
    ): UUID = UUID.randomUUID().also { id ->
        jdbc.update(
            """
            INSERT INTO registration_attempts(
                id, email, registration_proof_hash, request_fingerprint, locale,
                pending_password_hash, expires_at, completed_at, abandoned_at,
                activated_account_id, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            "retention-$id@example.ru",
            uniqueVerifierHash().value,
            "b".repeat(64),
            if (completedAt == null && abandonedAt == null) "ru-RU" else null,
            if (completedAt == null && abandonedAt == null) "argon-hash" else null,
            expiresAt.asJdbcTimestamp(),
            completedAt?.asJdbcTimestamp(),
            abandonedAt?.asJdbcTimestamp(),
            activatedAccountId,
            createdAt.asJdbcTimestamp(),
        )
        val tokenIssuedAt = createdAt.plusSeconds(1)
        val tokenExpiresAt = minOf(expiresAt, tokenIssuedAt.plus(30, ChronoUnit.MINUTES))
        jdbc.update(
            """
            INSERT INTO registration_verification_tokens(
                id, attempt_id, attempt_expires_at, verifier_hash,
                issued_at, expires_at, redeemed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            id,
            expiresAt.asJdbcTimestamp(),
            "c".repeat(64),
            tokenIssuedAt.asJdbcTimestamp(),
            tokenExpiresAt.asJdbcTimestamp(),
            completedAt?.asJdbcTimestamp(),
        )
    }

    companion object {
        private const val CONCURRENT_ATTEMPTS = 8
        private const val PUBLISHED_EVENTS = 20

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("identity-concurrency")
            .withUsername("identity")
            .withPassword("identity")
    }
}

private fun uniqueVerifierHash(): VerifierHash {
    val randomHex = UUID.randomUUID().toString().replace("-", "")
    return VerifierHash.fromSha256Hex(randomHex + randomHex)
}

private fun java.sql.Connection.canAcquireAdvisoryLock(key: String): Boolean =
    prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
        statement.setString(1, key)
        statement.executeQuery().use { rows ->
            check(rows.next()) { "Advisory-lock query returned no row" }
            rows.getBoolean(1)
        }
    }
