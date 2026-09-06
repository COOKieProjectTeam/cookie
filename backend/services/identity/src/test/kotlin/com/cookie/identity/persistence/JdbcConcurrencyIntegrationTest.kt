package com.cookie.identity.persistence

import com.cookie.identity.application.AccountDeletionCommand
import com.cookie.identity.application.GeneratedSecretToken
import com.cookie.identity.application.IdentityPolicy
import com.cookie.identity.application.IdentityRateLimiter
import com.cookie.identity.application.InvalidCredentialsException
import com.cookie.identity.application.InvalidTokenException
import com.cookie.identity.application.IssuedAccessToken
import com.cookie.identity.application.LoginWithEmailHandler
import com.cookie.identity.application.ParsedSecretToken
import com.cookie.identity.application.PublicJwk
import com.cookie.identity.application.RateLimitWindow
import com.cookie.identity.application.RefreshSessionHandler
import com.cookie.identity.application.RequestAccountDeletionHandler
import com.cookie.identity.application.SessionIssuer
import com.cookie.identity.application.VerifiedAccessToken
import com.cookie.identity.application.ports.AccessTokenProvider
import com.cookie.identity.application.ports.AccessTokenVerifier
import com.cookie.identity.application.ports.AccountRepository
import com.cookie.identity.application.ports.CurrentTimeProvider
import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.application.ports.IdentityEventRecorder
import com.cookie.identity.application.ports.PasswordHashing
import com.cookie.identity.application.ports.RateLimitRepository
import com.cookie.identity.application.ports.RateLimitScopeHasher
import com.cookie.identity.application.ports.RefreshTokenService
import com.cookie.identity.application.ports.TransactionRunner
import com.cookie.identity.domain.Account
import com.cookie.identity.domain.AccountActivated
import com.cookie.identity.domain.AccountDeletionRequest
import com.cookie.identity.domain.AccountDeletionRequested
import com.cookie.identity.domain.AccountStatus
import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.domain.LocaleTag
import com.cookie.identity.domain.PasswordPolicy
import com.cookie.identity.domain.RefreshFamily
import com.cookie.identity.domain.RefreshFamilyRevokeReason
import com.cookie.identity.domain.RegistrationAttempt
import com.cookie.identity.domain.VerifierHash
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
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
                "refresh_credentials, refresh_families, account_deletion_requests, " +
                "email_credentials, accounts",
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
    fun `refresh schema retains the compatibility retry deadline`() {
        assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*)::integer
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'refresh_credentials'
                  AND column_name = 'retry_until'
                """.trimIndent(),
                Int::class.java,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `account deletion schema enforces lifecycle idempotency ownership and timestamp constraints`() {
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val firstAccountId = insertAccount(now)
        val secondAccountId = insertAccount(now)
        val invalidKeyAccountId = insertAccount(now)
        val invalidStatusAccountId = insertAccount(now)
        val sharedKey = UUID.fromString("11111111-1111-4111-8111-111111111111")

        insertDeletionRequest(firstAccountId, sharedKey, now)
        insertDeletionRequest(secondAccountId, sharedKey, now.plusSeconds(1))

        assertThat(
            jdbc.queryForObject(
                """
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'account_deletion_requests'::regclass
                  AND conname = 'uq_account_deletion_requests_account_key'
                """.trimIndent(),
                String::class.java,
            ),
        ).contains("UNIQUE (account_id, idempotency_key)")
        assertThat(
            jdbc.queryForObject(
                """
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'account_deletion_requests'
                  AND column_name = 'requested_at'
                """.trimIndent(),
                String::class.java,
            ),
        ).isEqualTo("timestamp with time zone")
        assertThat(
            jdbc.queryForObject(
                """
                SELECT confdeltype::text
                FROM pg_constraint
                WHERE conrelid = 'account_deletion_requests'::regclass
                  AND conname = 'fk_account_deletion_requests_account'
                """.trimIndent(),
                String::class.java,
            ),
        ).isEqualTo("r")
        assertThat(
            jdbc.queryForObject(
                """
                SELECT convalidated
                FROM pg_constraint
                WHERE conrelid = 'accounts'::regclass
                  AND conname = 'ck_accounts_status'
                """.trimIndent(),
                Boolean::class.java,
            ),
        ).isFalse()

        assertThatThrownBy {
            insertDeletionRequest(
                accountId = invalidKeyAccountId,
                idempotencyKey = UUID.fromString("11111111-1111-1111-8111-111111111111"),
                requestedAt = now,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertDeletionRequest(firstAccountId, UUID.randomUUID(), now.plusSeconds(2))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insertDeletionRequest(
                accountId = invalidStatusAccountId,
                idempotencyKey = UUID.randomUUID(),
                requestedAt = now,
                status = "COMPLETED",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbc.update("UPDATE accounts SET status = 'DELETED' WHERE id = ?", invalidStatusAccountId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbc.update("DELETE FROM accounts WHERE id = ?", firstAccountId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_requests WHERE id IS NOT NULL",
                Int::class.java,
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `account and deletion request repositories persist the pending lifecycle state`() {
        val accountRepository = JdbcAccountRepository(jdbc)
        val deletionRepository = JdbcAccountDeletionRequestRepository(jdbc)
        val createdAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS)
        val requestedAt = createdAt.plusSeconds(30)
        val account = Account.register(
            id = UUID.randomUUID(),
            email = CanonicalEmail.reconstitute("deletion-repository@example.ru"),
            passwordHash = "argon-hash",
            now = createdAt,
        ).account

        transactions.executeWithoutResult { accountRepository.add(account) }
        assertThat(requireNotNull(accountRepository.findById(account.id)).status)
            .isEqualTo(AccountStatus.ACTIVE)

        val requestStart = AccountDeletionRequest.start(
            id = UUID.randomUUID(),
            accountId = account.id,
            idempotencyKey = UUID.randomUUID(),
            now = requestedAt,
        )
        transactions.executeWithoutResult {
            val locked = requireNotNull(accountRepository.findByIdForUpdate(account.id))
            assertThat(locked.requestDeletion(requestedAt)).isTrue()
            accountRepository.save(locked)
            deletionRepository.add(requestStart.request)
        }

        val persistedAccount = requireNotNull(accountRepository.findById(account.id))
        assertThat(persistedAccount.status).isEqualTo(AccountStatus.DELETION_PENDING)
        assertThat(persistedAccount.lockedUntil).isEqualTo(Account.DELETION_LOCKED_UNTIL)
        val persistedRequest = requireNotNull(deletionRepository.findByAccountId(account.id))
        assertThat(persistedRequest.id).isEqualTo(requestStart.request.id)
        assertThat(persistedRequest.accountId).isEqualTo(account.id)
        assertThat(persistedRequest.idempotencyKey).isEqualTo(requestStart.request.idempotencyKey)
        assertThat(persistedRequest.status).isEqualTo(requestStart.request.status)
        assertThat(persistedRequest.requestedAt).isEqualTo(requestedAt)
    }

    @Test
    fun `revoking an account closes only its active refresh families`() {
        val repository = JdbcRefreshFamilyRepository(jdbc)
        val createdAt = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MICROS)
        val deletionTime = createdAt.plusSeconds(30)
        val accountId = insertAccount(createdAt)
        val otherAccountId = insertAccount(createdAt)
        val firstCredentialId = UUID.randomUUID()
        val secondCredentialId = UUID.randomUUID()
        val first = activeRefreshFamily(accountId, firstCredentialId, createdAt)
        val later = activeRefreshFamily(accountId, secondCredentialId, createdAt.plusSeconds(60))
        val alreadyRevoked = activeRefreshFamily(accountId, UUID.randomUUID(), createdAt).also {
            it.revoke(RefreshFamilyRevokeReason.TOKEN_REUSE_DETECTED, createdAt.plusSeconds(10))
        }
        val otherAccountFamily = activeRefreshFamily(otherAccountId, UUID.randomUUID(), createdAt)

        transactions.executeWithoutResult {
            repository.add(first)
            repository.add(later)
            repository.add(alreadyRevoked)
            repository.add(otherAccountFamily)
        }
        assertThat(requireNotNull(repository.findCredentialLookup(firstCredentialId)).accountId)
            .isEqualTo(accountId)

        transactions.executeWithoutResult { repository.revokeAllForAccount(accountId, deletionTime) }

        assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*) FROM refresh_families
                WHERE account_id = ? AND status = 'REVOKED'
                """.trimIndent(),
                Int::class.java,
                accountId,
            ),
        ).isEqualTo(3)
        assertThat(refreshFamilyState(first.id))
            .containsExactly("REVOKED", deletionTime, "LOGOUT")
        assertThat(refreshFamilyState(later.id))
            .containsExactly("REVOKED", later.lastActivityAt, "LOGOUT")
        assertThat(refreshFamilyState(alreadyRevoked.id))
            .containsExactly(
                "REVOKED",
                alreadyRevoked.revokedAt,
                RefreshFamilyRevokeReason.TOKEN_REUSE_DETECTED.name,
            )
        assertThat(refreshFamilyState(otherAccountFamily.id))
            .containsExactly("ACTIVE", null, null)
    }

    @Test
    fun `login and deletion serialize safely in either account lock order`() {
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)

        val loginFirst = accountFixture("login-first", now)
        val loginFirstFamilies = JdbcRefreshFamilyRepository(jdbc)
        val loginFirstLock = heldLock()
        val deletionWaiter = blockedWaiter()
        val loginFirstResult = forceBlockedOrder(
            holderLock = loginFirstLock,
            waiter = deletionWaiter,
            holder = {
                loginHandler(
                    accounts = hookedAccounts(
                        afterEmailLock = loginFirstLock::hold,
                    ),
                    families = loginFirstFamilies,
                    now = now,
                ).execute(loginFirst.email.value, TEST_PASSWORD, null, TEST_IP)
            },
            contender = {
                deletionHandler(
                    accountId = loginFirst.accountId,
                    accounts = hookedAccounts(
                        beforeIdLock = deletionWaiter::captureBackend,
                    ),
                    families = loginFirstFamilies,
                    now = now,
                ).execute(deletionCommand())
            },
        )

        assertThat(loginFirstResult.first.accountId).isEqualTo(loginFirst.accountId)
        assertDeletionInvariant(loginFirst.accountId)
        assertThat(refreshFamilyCounts(loginFirst.accountId)).containsExactly(1, 0)

        val deletionFirst = accountFixture("deletion-first", now)
        val deletionFirstFamilies = JdbcRefreshFamilyRepository(jdbc)
        val deletionFirstLock = heldLock()
        val loginWaiter = blockedWaiter()
        val deletionFirstResult = forceBlockedOrder(
            holderLock = deletionFirstLock,
            waiter = loginWaiter,
            holder = {
                deletionHandler(
                    accountId = deletionFirst.accountId,
                    accounts = hookedAccounts(
                        afterIdLock = deletionFirstLock::hold,
                    ),
                    families = deletionFirstFamilies,
                    now = now,
                ).execute(deletionCommand())
            },
            contender = {
                try {
                    loginHandler(
                        accounts = hookedAccounts(
                            beforeEmailLock = loginWaiter::captureBackend,
                        ),
                        families = deletionFirstFamilies,
                        now = now,
                    ).execute(deletionFirst.email.value, TEST_PASSWORD, null, TEST_IP)
                    true
                } catch (_: InvalidCredentialsException) {
                    false
                }
            },
        )

        assertThat(deletionFirstResult.second).isFalse()
        assertDeletionInvariant(deletionFirst.accountId)
        assertThat(refreshFamilyCounts(deletionFirst.accountId)).containsExactly(0, 0)
    }

    @Test
    fun `refresh and deletion serialize safely in either account lock order`() {
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)

        val refreshFirst = accountFixture("refresh-first", now)
        val refreshFirstFamilies = JdbcRefreshFamilyRepository(jdbc)
        val refreshFirstTokens = TestRefreshTokens()
        val refreshFirstCredential = seedRefreshFamily(
            refreshFirst.accountId,
            refreshFirstFamilies,
            refreshFirstTokens,
            now,
        )
        val refreshFirstLock = heldLock()
        val deletionWaiter = blockedWaiter()
        val refreshFirstResult = forceBlockedOrder(
            holderLock = refreshFirstLock,
            waiter = deletionWaiter,
            holder = {
                refreshHandler(
                    accounts = hookedAccounts(
                        afterIdLock = refreshFirstLock::hold,
                    ),
                    families = refreshFirstFamilies,
                    tokens = refreshFirstTokens,
                    now = now,
                ).execute(refreshFirstCredential, UUID.randomUUID(), TEST_IP)
            },
            contender = {
                deletionHandler(
                    accountId = refreshFirst.accountId,
                    accounts = hookedAccounts(
                        beforeIdLock = deletionWaiter::captureBackend,
                    ),
                    families = refreshFirstFamilies,
                    now = now,
                ).execute(deletionCommand())
            },
        )

        assertThat(refreshFirstResult.first.accountId).isEqualTo(refreshFirst.accountId)
        assertDeletionInvariant(refreshFirst.accountId)
        assertThat(refreshFamilyCounts(refreshFirst.accountId)).containsExactly(1, 0)
        assertThat(refreshCredentialCount(refreshFirst.accountId)).isEqualTo(2)

        val deletionFirst = accountFixture("refresh-after-deletion", now)
        val deletionFirstFamilies = JdbcRefreshFamilyRepository(jdbc)
        val deletionFirstTokens = TestRefreshTokens()
        val deletionFirstCredential = seedRefreshFamily(
            deletionFirst.accountId,
            deletionFirstFamilies,
            deletionFirstTokens,
            now,
        )
        val deletionFirstLock = heldLock()
        val refreshWaiter = blockedWaiter()
        val deletionFirstResult = forceBlockedOrder(
            holderLock = deletionFirstLock,
            waiter = refreshWaiter,
            holder = {
                deletionHandler(
                    accountId = deletionFirst.accountId,
                    accounts = hookedAccounts(
                        afterIdLock = deletionFirstLock::hold,
                    ),
                    families = deletionFirstFamilies,
                    now = now,
                ).execute(deletionCommand())
            },
            contender = {
                try {
                    refreshHandler(
                        accounts = hookedAccounts(
                            beforeIdLock = refreshWaiter::captureBackend,
                        ),
                        families = deletionFirstFamilies,
                        tokens = deletionFirstTokens,
                        now = now,
                    ).execute(deletionFirstCredential, UUID.randomUUID(), TEST_IP)
                    true
                } catch (_: InvalidTokenException) {
                    false
                }
            },
        )

        assertThat(deletionFirstResult.second).isFalse()
        assertDeletionInvariant(deletionFirst.accountId)
        assertThat(refreshFamilyCounts(deletionFirst.accountId)).containsExactly(1, 0)
        assertThat(refreshCredentialCount(deletionFirst.accountId)).isEqualTo(1)
    }

    @Test
    fun `V4 upgrades a V3 database and keeps old shaped account inserts active`() {
        val schema = "identity_upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        jdbc.execute("CREATE SCHEMA $schema")
        val flywayV3 = Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .target(MigrationVersion.fromVersion("3"))
            .load()
        flywayV3.migrate()

        val accountBeforeUpgrade = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO $schema.accounts(id, created_at) VALUES (?, ?)",
            accountBeforeUpgrade,
            Instant.now().truncatedTo(ChronoUnit.MICROS).asJdbcTimestamp(),
        )
        assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = ? AND table_name = 'accounts' AND column_name = 'status'
                """.trimIndent(),
                Int::class.java,
                schema,
            ),
        ).isZero()

        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .load()
            .migrate()

        val accountAfterUpgrade = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO $schema.accounts(id, created_at) VALUES (?, ?)",
            accountAfterUpgrade,
            Instant.now().truncatedTo(ChronoUnit.MICROS).asJdbcTimestamp(),
        )
        assertThat(
            jdbc.queryForList(
                "SELECT status FROM $schema.accounts ORDER BY id",
                String::class.java,
            ),
        ).containsExactlyInAnyOrder("ACTIVE", "ACTIVE")
        assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = ? AND table_name = 'account_deletion_requests'
                """.trimIndent(),
                Int::class.java,
                schema,
            ),
        ).isEqualTo(1)
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

    private fun accountFixture(label: String, now: Instant): AccountFixture {
        val email = CanonicalEmail.reconstitute(
            "$label-${UUID.randomUUID().toString().take(8)}@example.ru",
        )
        val account = Account.register(
            id = UUID.randomUUID(),
            email = email,
            passwordHash = TEST_PASSWORD_HASH,
            now = now.minusSeconds(60),
        ).account
        transactions.executeWithoutResult { JdbcAccountRepository(jdbc).add(account) }
        return AccountFixture(account.id, email)
    }

    private fun hookedAccounts(
        beforeIdLock: () -> Unit = {},
        afterIdLock: () -> Unit = {},
        beforeEmailLock: () -> Unit = {},
        afterEmailLock: () -> Unit = {},
    ): AccountRepository = HookedAccountRepository(
        delegate = JdbcAccountRepository(jdbc),
        beforeIdLock = beforeIdLock,
        afterIdLock = afterIdLock,
        beforeEmailLock = beforeEmailLock,
        afterEmailLock = afterEmailLock,
    )

    private fun loginHandler(
        accounts: AccountRepository,
        families: JdbcRefreshFamilyRepository,
        now: Instant,
    ): LoginWithEmailHandler {
        val tokens = TestRefreshTokens()
        return LoginWithEmailHandler(
            accounts = accounts,
            transactions = TemplateTransactionRunner(transactions),
            passwordPolicy = PasswordPolicy(),
            passwordHashing = TestPasswordHashing,
            rateLimiter = testRateLimiter(),
            sessionIssuer = sessionIssuer(families, tokens),
            currentTime = CurrentTimeProvider { now },
        )
    }

    private fun refreshHandler(
        accounts: AccountRepository,
        families: JdbcRefreshFamilyRepository,
        tokens: TestRefreshTokens,
        now: Instant,
    ): RefreshSessionHandler = RefreshSessionHandler(
        accounts = accounts,
        families = families,
        transactions = TemplateTransactionRunner(transactions),
        refreshTokens = tokens,
        rateLimiter = testRateLimiter(),
        sessionIssuer = sessionIssuer(families, tokens),
        currentTime = CurrentTimeProvider { now },
    )

    private fun deletionHandler(
        accountId: UUID,
        accounts: AccountRepository,
        families: JdbcRefreshFamilyRepository,
        now: Instant,
    ): RequestAccountDeletionHandler = RequestAccountDeletionHandler(
        accounts = accounts,
        deletionRequests = JdbcAccountDeletionRequestRepository(jdbc),
        families = families,
        transactions = TemplateTransactionRunner(transactions),
        accessTokens = AccessTokenVerifier { _, _ -> VerifiedAccessToken(accountId) },
        passwordPolicy = PasswordPolicy(),
        passwordHashing = TestPasswordHashing,
        rateLimiter = testRateLimiter(),
        ids = RandomIds,
        events = NoOpIdentityEvents,
        currentTime = CurrentTimeProvider { now },
    )

    private fun deletionCommand(): AccountDeletionCommand = AccountDeletionCommand(
        accessToken = "test-access-token",
        currentPassword = TEST_PASSWORD,
        idempotencyKey = UUID.randomUUID(),
        ip = TEST_IP,
    )

    private fun sessionIssuer(
        families: JdbcRefreshFamilyRepository,
        tokens: TestRefreshTokens,
    ): SessionIssuer = SessionIssuer(
        families = families,
        tokens = tokens,
        ids = RandomIds,
        accessTokens = TestAccessTokens,
        policy = IdentityPolicy(
            refreshFamilyTtl = Duration.ofDays(30),
            registrationAttemptTtl = Duration.ofHours(24),
            verificationTokenTtl = Duration.ofMinutes(30),
            verificationResendCooldown = Duration.ofMinutes(1),
        ),
    )

    private fun seedRefreshFamily(
        accountId: UUID,
        families: JdbcRefreshFamilyRepository,
        tokens: TestRefreshTokens,
        now: Instant,
    ): String {
        val credentialId = UUID.randomUUID()
        val credential = tokens.create(credentialId)
        val family = RefreshFamily.start(
            id = UUID.randomUUID(),
            accountId = accountId,
            firstCredentialId = credentialId,
            firstVerifierHash = credential.verifierHash,
            deviceId = null,
            expiresAt = now.plus(30, ChronoUnit.DAYS),
            now = now,
        )
        transactions.executeWithoutResult { families.add(family) }
        return credential.value
    }

    private fun testRateLimiter(): IdentityRateLimiter = IdentityRateLimiter(
        repository = object : RateLimitRepository {
            override fun consume(scopeKey: String, window: Duration): RateLimitWindow =
                RateLimitWindow(attemptCount = 1, retryAfterSeconds = window.seconds)
        },
        scopeHasher = RateLimitScopeHasher { namespace, value -> "$namespace:$value" },
    )

    private fun assertDeletionInvariant(accountId: UUID) {
        assertThat(
            jdbc.queryForObject("SELECT status FROM accounts WHERE id = ?", String::class.java, accountId),
        ).isEqualTo(AccountStatus.DELETION_PENDING.name)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_deletion_requests WHERE account_id = ?",
                Int::class.java,
                accountId,
            ),
        ).isEqualTo(1)
        assertThat(refreshFamilyCounts(accountId).last()).isZero()
    }

    private fun refreshFamilyCounts(accountId: UUID): List<Int> = jdbc.query(
        """
        SELECT count(*)::integer AS total,
               count(*) FILTER (WHERE status = 'ACTIVE')::integer AS active
        FROM refresh_families
        WHERE account_id = ?
        """.trimIndent(),
        { result, _ -> listOf(result.getInt("total"), result.getInt("active")) },
        accountId,
    ).single()

    private fun refreshCredentialCount(accountId: UUID): Int = requireNotNull(
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM refresh_credentials c
            JOIN refresh_families f ON f.id = c.family_id
            WHERE f.account_id = ?
            """.trimIndent(),
            Int::class.java,
            accountId,
        ),
    )

    private fun heldLock(): HeldLock = HeldLock()

    private fun blockedWaiter(): BlockedWaiter = BlockedWaiter(jdbc)

    private fun <H, C> forceBlockedOrder(
        holderLock: HeldLock,
        waiter: BlockedWaiter,
        holder: () -> H,
        contender: () -> C,
    ): Pair<H, C> {
        val executor = Executors.newFixedThreadPool(2)
        val holderFuture = CompletableFuture.supplyAsync(holder, executor)
        try {
            check(holderLock.acquired.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out waiting for the first transaction to hold the account lock"
            }
            val contenderFuture = CompletableFuture.supplyAsync(contender, executor)
            val contenderPid = waiter.backendPid.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            awaitDatabaseBlock(contenderPid)
            holderLock.release.countDown()
            return holderFuture.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS) to
                contenderFuture.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            holderLock.release.countDown()
            executor.shutdownNow()
        }
    }

    private fun awaitDatabaseBlock(backendPid: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RACE_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val blockerCount = requireNotNull(
                jdbc.queryForObject(
                    "SELECT cardinality(pg_blocking_pids(?))",
                    Int::class.java,
                    backendPid,
                ),
            )
            if (blockerCount > 0) return
            Thread.sleep(BLOCK_POLL_MILLISECONDS)
        }
        error("PostgreSQL backend $backendPid did not block on the held account lock")
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

    private fun insertDeletionRequest(
        accountId: UUID,
        idempotencyKey: UUID,
        requestedAt: Instant,
        status: String = "DELETION_PENDING",
    ): UUID = UUID.randomUUID().also { id ->
        jdbc.update(
            """
            INSERT INTO account_deletion_requests(
                id, account_id, idempotency_key, status, requested_at
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            accountId,
            idempotencyKey,
            status,
            requestedAt.asJdbcTimestamp(),
        )
    }

    private fun activeRefreshFamily(
        accountId: UUID,
        credentialId: UUID,
        createdAt: Instant,
    ): RefreshFamily = RefreshFamily.start(
        id = UUID.randomUUID(),
        accountId = accountId,
        firstCredentialId = credentialId,
        firstVerifierHash = uniqueVerifierHash(),
        deviceId = null,
        expiresAt = createdAt.plus(30, ChronoUnit.DAYS),
        now = createdAt,
    )

    private fun refreshFamilyState(familyId: UUID): List<Any?> = jdbc.query(
        """
        SELECT status, revoked_at, revoke_reason
        FROM refresh_families
        WHERE id = ?
        """.trimIndent(),
        { result, _ ->
            listOf(
                result.getString("status"),
                result.getTimestamp("revoked_at")?.toInstant(),
                result.getString("revoke_reason"),
            )
        },
        familyId,
    ).single()

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

    private data class AccountFixture(
        val accountId: UUID,
        val email: CanonicalEmail,
    )

    private class HeldLock {
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun hold() {
            acquired.countDown()
            check(release.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out waiting to release the held account lock"
            }
        }
    }

    private class BlockedWaiter(
        private val jdbc: JdbcTemplate,
    ) {
        val backendPid = CompletableFuture<Int>()

        fun captureBackend() {
            val pid = requireNotNull(
                jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java),
            )
            check(backendPid.complete(pid)) { "Contender backend was captured more than once" }
        }
    }

    private class HookedAccountRepository(
        private val delegate: AccountRepository,
        private val beforeIdLock: () -> Unit,
        private val afterIdLock: () -> Unit,
        private val beforeEmailLock: () -> Unit,
        private val afterEmailLock: () -> Unit,
    ) : AccountRepository {
        override fun lockRegistration(email: CanonicalEmail) = delegate.lockRegistration(email)

        override fun findByEmail(email: CanonicalEmail): Account? = delegate.findByEmail(email)

        override fun findById(accountId: UUID): Account? = delegate.findById(accountId)

        override fun findByEmailForUpdate(email: CanonicalEmail): Account? {
            beforeEmailLock()
            return delegate.findByEmailForUpdate(email).also { afterEmailLock() }
        }

        override fun findByIdForUpdate(accountId: UUID): Account? {
            beforeIdLock()
            return delegate.findByIdForUpdate(accountId).also { afterIdLock() }
        }

        override fun add(account: Account) = delegate.add(account)

        override fun save(account: Account) = delegate.save(account)
    }

    private class TemplateTransactionRunner(
        private val transactions: TransactionTemplate,
    ) : TransactionRunner {
        override fun <T : Any> required(block: () -> T): T = requireNotNull(
            transactions.execute { block() },
        )

        override fun requiredUnit(block: () -> Unit) {
            transactions.executeWithoutResult { block() }
        }
    }

    private class TestRefreshTokens : RefreshTokenService {
        private val parsedTokens = java.util.concurrent.ConcurrentHashMap<String, ParsedSecretToken>()

        override fun create(id: UUID): GeneratedSecretToken {
            val value = "test-refresh.$id.${UUID.randomUUID()}"
            val parsed = ParsedSecretToken(id, uniqueVerifierHash())
            parsedTokens[value] = parsed
            return GeneratedSecretToken(id, value, parsed.verifierHash)
        }

        override fun createRefreshSuccessor(
            predecessorRawToken: String,
            replacementId: UUID,
            idempotencyKey: UUID,
        ): GeneratedSecretToken = create(replacementId)

        override fun parse(value: String): ParsedSecretToken =
            parsedTokens[value] ?: throw InvalidTokenException()

        override fun verifierMatches(expected: VerifierHash, actual: VerifierHash): Boolean = expected == actual
    }

    private object RandomIds : IdGenerator {
        override fun next(): UUID = UUID.randomUUID()
    }

    private object TestPasswordHashing : PasswordHashing {
        override val dummyHash: String = "test-dummy-hash"

        override fun encode(password: String): String = error("Password encoding is not used by these race tests")

        override fun matches(password: String, encoded: String): Boolean =
            password == TEST_PASSWORD && encoded == TEST_PASSWORD_HASH
    }

    private object TestAccessTokens : AccessTokenProvider {
        override fun issue(accountId: UUID, sessionId: UUID, now: Instant): IssuedAccessToken =
            IssuedAccessToken("test-access-token", 900)

        override fun publicKeys(): List<PublicJwk> = emptyList()
    }

    private object NoOpIdentityEvents : IdentityEventRecorder {
        override fun verificationRequested(
            registrationAttemptId: UUID,
            email: CanonicalEmail,
            locale: LocaleTag?,
            rawToken: String,
            expiresAt: Instant,
            now: Instant,
        ) = error("Verification events are not used by these race tests")

        override fun accountActivated(event: AccountActivated) =
            error("Activation events are not used by these race tests")

        override fun accountDeletionRequested(event: AccountDeletionRequested) = Unit
    }

    companion object {
        private const val CONCURRENT_ATTEMPTS = 8
        private const val PUBLISHED_EVENTS = 20
        private const val RACE_TIMEOUT_SECONDS = 15L
        private const val BLOCK_POLL_MILLISECONDS = 10L
        private const val TEST_PASSWORD = "CorrectPassword-123"
        private const val TEST_PASSWORD_HASH = "encoded-test-password"
        private const val TEST_IP = "192.0.2.44"

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
