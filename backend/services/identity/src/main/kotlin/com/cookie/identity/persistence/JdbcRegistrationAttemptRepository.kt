package com.cookie.identity.persistence

import com.cookie.identity.application.ports.RegistrationAttemptRepository
import com.cookie.identity.domain.CanonicalEmail
import com.cookie.identity.domain.LocaleTag
import com.cookie.identity.domain.RegistrationAttempt
import com.cookie.identity.domain.RegistrationVerificationToken
import com.cookie.identity.domain.VerifierHash
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Repository
class JdbcRegistrationAttemptRepository(
    private val jdbc: JdbcTemplate,
) : RegistrationAttemptRepository {
    override fun findById(id: UUID): RegistrationAttempt? = loadById(id)

    override fun findByIdForUpdate(id: UUID): RegistrationAttempt? {
        requireActiveTransaction("Lock registration attempt by id")
        return lockRootById(id)?.let(::loadById)
    }

    override fun findByTokenId(tokenId: UUID): RegistrationAttempt? = loadAggregate(
        """
        $ATTEMPT_WITH_TOKENS_SELECT
        WHERE a.id = (
            SELECT presented.attempt_id
            FROM registration_verification_tokens presented
            WHERE presented.id = ?
        )
        ORDER BY token.issued_at, token.id
        """.trimIndent(),
        tokenId,
    )

    override fun findByTokenIdForUpdate(tokenId: UUID): RegistrationAttempt? {
        requireActiveTransaction("Lock registration attempt by verification token")
        val attemptId = jdbc.query(
            """
            SELECT a.id
            FROM registration_attempts a
            JOIN registration_verification_tokens token ON token.attempt_id = a.id
            WHERE token.id = ?
            FOR UPDATE OF a
            """.trimIndent(),
            { result, _ -> result.getObject("id", UUID::class.java) },
            tokenId,
        ).singleOrNull() ?: return null

        // Hydrate after acquiring the aggregate-root lock. Under READ COMMITTED
        // this observes a resend/completion that committed while this statement
        // was waiting, instead of using the locking statement's old snapshot.
        return loadById(attemptId)
    }

    override fun findByProof(proofHash: VerifierHash): RegistrationAttempt? = loadAggregate(
        """
        $ATTEMPT_WITH_TOKENS_SELECT
        WHERE a.registration_proof_hash = ?
        ORDER BY token.issued_at, token.id
        """.trimIndent(),
        proofHash.value,
    )

    override fun lockCreationKeys(attemptId: UUID, proofHash: VerifierHash) {
        requireActiveTransaction("Lock registration creation keys")
        // Every caller takes these namespaced locks in the same order. The
        // PostgreSQL hash may collide, which can only cause safe over-serialization.
        jdbc.acquireTransactionAdvisoryLock("identity:registration-attempt:$attemptId")
        jdbc.acquireTransactionAdvisoryLock("identity:registration-proof:${proofHash.value}")
    }

    override fun add(attempt: RegistrationAttempt) {
        requireActiveTransaction("Add registration attempt")
        val tokens = attempt.verificationTokenSnapshots()
        check(!attempt.isCompleted && !attempt.isAbandoned && tokens.size == 1 && tokens.none { it.isRedeemed }) {
            "A new registration attempt must contain exactly one unredeemed verification token"
        }
        requireSingleRow(
            "Insert registration attempt",
            jdbc.update(
                """
                INSERT INTO registration_attempts(
                    id, email, registration_proof_hash, request_fingerprint,
                    locale, pending_password_hash, expires_at, completed_at,
                    abandoned_at, activated_account_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                attempt.id,
                attempt.email.value,
                attempt.registrationProofHash.value,
                attempt.requestFingerprint.value,
                attempt.locale?.value,
                attempt.pendingPasswordHash,
                attempt.expiresAt.asJdbcTimestamp(),
                attempt.completedAt?.asJdbcTimestamp(),
                attempt.abandonedAt?.asJdbcTimestamp(),
                attempt.activatedAccountId,
                attempt.createdAt.asJdbcTimestamp(),
            ),
        )
        insertToken(tokens.single(), attempt.expiresAt)
    }

    override fun save(attempt: RegistrationAttempt) {
        requireActiveTransaction("Save registration attempt")
        val stored = findByIdForUpdate(attempt.id)
            ?: error("Registration attempt ${attempt.id} no longer exists")
        verifySameRoot(stored, attempt)

        val storedTokens = stored.verificationTokenSnapshots().associateBy { it.id }
        val targetTokens = attempt.verificationTokenSnapshots().associateBy { it.id }
        check(targetTokens.keys.containsAll(storedTokens.keys)) {
            "Registration attempt cannot discard persisted verification tokens"
        }
        storedTokens.forEach { (id, token) -> verifySameToken(token, targetTokens.getValue(id)) }

        if (attempt.isCompleted) {
            saveCompletion(stored, attempt, storedTokens, targetTokens)
        } else {
            saveIssuedToken(stored, attempt, storedTokens, targetTokens)
        }
    }

    override fun abandonPendingByEmailExcept(
        email: CanonicalEmail,
        winningAttemptId: UUID,
        now: Instant,
    ): Int {
        requireActiveTransaction("Abandon losing registration attempts")
        return jdbc.update(
            """
            UPDATE registration_attempts losing
            SET locale = NULL,
                pending_password_hash = NULL,
                abandoned_at = ?
            WHERE losing.email = ?
              AND losing.id <> ?
              AND losing.completed_at IS NULL
              AND losing.abandoned_at IS NULL
              AND EXISTS (
                  SELECT 1
                  FROM registration_attempts winner
                  WHERE winner.id = ?
                    AND winner.email = ?
                    AND winner.completed_at IS NOT NULL
              )
            """.trimIndent(),
            now.asJdbcTimestamp(),
            email.value,
            winningAttemptId,
            winningAttemptId,
            email.value,
        )
    }

    private fun saveIssuedToken(
        stored: RegistrationAttempt,
        target: RegistrationAttempt,
        storedTokens: Map<UUID, RegistrationVerificationToken>,
        targetTokens: Map<UUID, RegistrationVerificationToken>,
    ) {
        check(!stored.isCompleted) { "Completed registration attempt cannot issue another token" }
        check(!stored.isAbandoned) { "Abandoned registration attempt cannot issue another token" }
        check(
            stored.locale == target.locale &&
                stored.pendingPasswordHash == target.pendingPasswordHash &&
                target.completedAt == null &&
                target.activatedAccountId == null,
        ) { "Issuing a verification token cannot change registration attempt state" }
        val addedTokens = targetTokens.keys - storedTokens.keys
        check(addedTokens.size == 1) {
            "Saving a pending registration attempt must add exactly one verification token"
        }
        insertToken(targetTokens.getValue(addedTokens.single()), target.expiresAt)
    }

    private fun saveCompletion(
        stored: RegistrationAttempt,
        target: RegistrationAttempt,
        storedTokens: Map<UUID, RegistrationVerificationToken>,
        targetTokens: Map<UUID, RegistrationVerificationToken>,
    ) {
        check(!stored.isCompleted) { "Registration attempt is already completed" }
        check(!stored.isAbandoned) { "Abandoned registration attempt cannot be completed" }
        check(targetTokens.keys == storedTokens.keys) {
            "Completing a registration attempt cannot add verification tokens"
        }
        check(target.locale == null && target.pendingPasswordHash == null) {
            "Completed registration secrets must be scrubbed"
        }
        val redeemedTokens = targetTokens.values.filter { it.isRedeemed }
        check(redeemedTokens.size == 1 && storedTokens.values.none { it.isRedeemed }) {
            "Registration completion must redeem exactly one verification token"
        }
        val redeemedToken = redeemedTokens.single()
        val redeemedAt = checkNotNull(redeemedToken.redeemedAt)
        val completedAt = checkNotNull(target.completedAt)
        val activatedAccountId = checkNotNull(target.activatedAccountId)
        requireSingleRow(
            "Redeem registration verification token",
            jdbc.update(
                """
                UPDATE registration_verification_tokens
                SET redeemed_at = ?
                WHERE id = ? AND attempt_id = ? AND redeemed_at IS NULL
                """.trimIndent(),
                redeemedAt.asJdbcTimestamp(),
                redeemedToken.id,
                target.id,
            ),
        )
        requireSingleRow(
            "Complete registration attempt",
            jdbc.update(
                """
                UPDATE registration_attempts
                SET locale = NULL, pending_password_hash = NULL,
                    completed_at = ?, activated_account_id = ?
                WHERE id = ? AND completed_at IS NULL AND abandoned_at IS NULL
                  AND activated_account_id IS NULL
                  AND pending_password_hash IS NOT NULL
                """.trimIndent(),
                completedAt.asJdbcTimestamp(),
                activatedAccountId,
                target.id,
            ),
        )
    }

    private fun verifySameRoot(stored: RegistrationAttempt, target: RegistrationAttempt) {
        check(
            stored.email == target.email &&
                stored.registrationProofHash == target.registrationProofHash &&
                stored.requestFingerprint == target.requestFingerprint &&
                stored.expiresAt == target.expiresAt &&
                stored.createdAt == target.createdAt,
        ) { "Immutable registration attempt state was changed" }
    }

    private fun verifySameToken(stored: RegistrationVerificationToken, target: RegistrationVerificationToken) {
        check(
            stored.attemptId == target.attemptId &&
                stored.verifierHash == target.verifierHash &&
                stored.issuedAt == target.issuedAt &&
                stored.expiresAt == target.expiresAt &&
                (stored.redeemedAt == null || stored.redeemedAt == target.redeemedAt),
        ) { "Immutable registration verification token state was changed" }
    }

    private fun lockRootById(id: UUID): UUID? = jdbc.query(
        "SELECT id FROM registration_attempts WHERE id = ? FOR UPDATE",
        { result, _ -> result.getObject("id", UUID::class.java) },
        id,
    ).singleOrNull()

    private fun loadById(id: UUID): RegistrationAttempt? = loadAggregate(
        "$ATTEMPT_WITH_TOKENS_SELECT WHERE a.id = ? ORDER BY token.issued_at, token.id",
        id,
    )

    private fun loadAggregate(sql: String, vararg parameters: Any): RegistrationAttempt? {
        val rows = jdbc.query(
            sql,
            ::mapAttemptTokenRow,
            *parameters,
        )
        val row = rows.firstOrNull()?.attempt ?: return null
        val tokens = rows.mapNotNull(AttemptTokenRow::token)
        return RegistrationAttempt.reconstitute(
            id = row.id,
            email = row.email,
            registrationProofHash = row.registrationProofHash,
            requestFingerprint = row.requestFingerprint,
            locale = row.locale,
            pendingPasswordHash = row.pendingPasswordHash,
            expiresAt = row.expiresAt,
            createdAt = row.createdAt,
            completedAt = row.completedAt,
            activatedAccountId = row.activatedAccountId,
            abandonedAt = row.abandonedAt,
            verificationTokens = tokens,
        )
    }

    private fun insertToken(token: RegistrationVerificationToken, attemptExpiresAt: Instant) {
        requireSingleRow(
            "Insert registration verification token",
            jdbc.update(
                """
                INSERT INTO registration_verification_tokens(
                    id, attempt_id, attempt_expires_at, verifier_hash,
                    issued_at, expires_at, redeemed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                token.id,
                token.attemptId,
                attemptExpiresAt.asJdbcTimestamp(),
                token.verifierHash.value,
                token.issuedAt.asJdbcTimestamp(),
                token.expiresAt.asJdbcTimestamp(),
                token.redeemedAt?.asJdbcTimestamp(),
            ),
        )
    }

    private fun mapAttemptTokenRow(
        result: ResultSet,
        @Suppress("UNUSED_PARAMETER") rowNumber: Int,
    ): AttemptTokenRow {
        val attempt = AttemptRow(
            id = result.getObject("attempt_id", UUID::class.java),
            email = CanonicalEmail.reconstitute(result.getString("attempt_email")),
            registrationProofHash = VerifierHash.fromSha256Hex(result.getString("registration_proof_hash")),
            requestFingerprint = VerifierHash.fromSha256Hex(result.getString("request_fingerprint")),
            locale = result.getString("attempt_locale")?.let(LocaleTag::reconstitute),
            pendingPasswordHash = result.getString("pending_password_hash"),
            expiresAt = result.getTimestamp("attempt_expires_at").toInstant(),
            createdAt = result.getTimestamp("attempt_created_at").toInstant(),
            completedAt = result.getTimestamp("attempt_completed_at")?.toInstant(),
            abandonedAt = result.getTimestamp("attempt_abandoned_at")?.toInstant(),
            activatedAccountId = result.getObject("activated_account_id", UUID::class.java),
        )
        val tokenId = result.getObject("token_id", UUID::class.java)
        val token = tokenId?.let {
            RegistrationVerificationToken.reconstitute(
                id = it,
                attemptId = result.getObject("token_attempt_id", UUID::class.java),
                verifierHash = VerifierHash.fromSha256Hex(result.getString("token_verifier_hash")),
                issuedAt = result.getTimestamp("token_issued_at").toInstant(),
                expiresAt = result.getTimestamp("token_expires_at").toInstant(),
                redeemedAt = result.getTimestamp("token_redeemed_at")?.toInstant(),
            )
        }
        return AttemptTokenRow(attempt, token)
    }

    private data class AttemptTokenRow(
        val attempt: AttemptRow,
        val token: RegistrationVerificationToken?,
    )

    private data class AttemptRow(
        val id: UUID,
        val email: CanonicalEmail,
        val registrationProofHash: VerifierHash,
        val requestFingerprint: VerifierHash,
        val locale: LocaleTag?,
        val pendingPasswordHash: String?,
        val expiresAt: Instant,
        val createdAt: Instant,
        val completedAt: Instant?,
        val abandonedAt: Instant?,
        val activatedAccountId: UUID?,
    )

    private companion object {
        val ATTEMPT_WITH_TOKENS_SELECT = """
            SELECT a.id AS attempt_id, a.email AS attempt_email,
                   a.registration_proof_hash, a.request_fingerprint,
                   a.locale AS attempt_locale, a.pending_password_hash,
                   a.expires_at AS attempt_expires_at,
                   a.completed_at AS attempt_completed_at,
                   a.abandoned_at AS attempt_abandoned_at,
                   a.activated_account_id, a.created_at AS attempt_created_at,
                   token.id AS token_id, token.attempt_id AS token_attempt_id,
                   token.verifier_hash AS token_verifier_hash,
                   token.issued_at AS token_issued_at,
                   token.expires_at AS token_expires_at,
                   token.redeemed_at AS token_redeemed_at
            FROM registration_attempts a
            LEFT JOIN registration_verification_tokens token ON token.attempt_id = a.id
        """.trimIndent()
    }
}
