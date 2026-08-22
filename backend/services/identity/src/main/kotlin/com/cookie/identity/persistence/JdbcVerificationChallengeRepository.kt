package com.cookie.identity.persistence

import com.cookie.identity.application.ports.VerificationChallengeRepository
import com.cookie.identity.domain.VerificationChallenge
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Repository
class JdbcVerificationChallengeRepository(
    private val jdbc: JdbcTemplate,
) : VerificationChallengeRepository {
    override fun findById(id: UUID): VerificationChallenge? = jdbc.query(
        "$CHALLENGE_SELECT WHERE id = ? AND purpose = 'EMAIL_VERIFICATION'",
        ::mapChallenge,
        id,
    ).singleOrNull()

    override fun findByIdForUpdate(id: UUID): VerificationChallenge? {
        requireActiveTransaction("Lock verification challenge by id")
        return jdbc.query(
            "$CHALLENGE_SELECT WHERE id = ? AND purpose = 'EMAIL_VERIFICATION' FOR UPDATE",
            ::mapChallenge,
            id,
        ).singleOrNull()
    }

    override fun findLatest(accountId: UUID): VerificationChallenge? = jdbc.query(
        """
        $CHALLENGE_SELECT
        WHERE account_id = ? AND purpose = 'EMAIL_VERIFICATION'
        ORDER BY created_at DESC, id DESC
        LIMIT 1
        """.trimIndent(),
        ::mapChallenge,
        accountId,
    ).singleOrNull()

    override fun findActiveForUpdate(accountId: UUID, now: Instant): List<VerificationChallenge> {
        requireActiveTransaction("Lock active verification challenges")
        return jdbc.query(
            """
            $CHALLENGE_SELECT
            WHERE account_id = ? AND purpose = 'EMAIL_VERIFICATION'
              AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > ?
            ORDER BY created_at, id
            FOR UPDATE
            """.trimIndent(),
            ::mapChallenge,
            accountId,
            now.asJdbcTimestamp(),
        )
    }

    override fun add(challenge: VerificationChallenge) {
        requireActiveTransaction("Add verification challenge")
        requireSingleRow(
            "Insert verification challenge",
            jdbc.update(
                """
                INSERT INTO auth_action_tokens(
                    id, account_id, purpose, token_hash, expires_at,
                    consumed_at, revoked_at, created_at
                ) VALUES (?, ?, 'EMAIL_VERIFICATION', ?, ?, ?, ?, ?)
                """.trimIndent(),
                challenge.id,
                challenge.accountId,
                challenge.verifierHash,
                challenge.expiresAt.asJdbcTimestamp(),
                challenge.consumedAt?.asJdbcTimestamp(),
                challenge.revokedAt?.asJdbcTimestamp(),
                challenge.createdAt.asJdbcTimestamp(),
            ),
        )
    }

    override fun save(challenge: VerificationChallenge) {
        requireActiveTransaction("Save verification challenge")
        requireSingleRow(
            "Update verification challenge",
            jdbc.update(
                """
                UPDATE auth_action_tokens
                SET consumed_at = ?, revoked_at = ?
                WHERE id = ? AND account_id = ? AND purpose = 'EMAIL_VERIFICATION'
                """.trimIndent(),
                challenge.consumedAt?.asJdbcTimestamp(),
                challenge.revokedAt?.asJdbcTimestamp(),
                challenge.id,
                challenge.accountId,
            ),
        )
    }

    private fun mapChallenge(
        result: ResultSet,
        @Suppress("UNUSED_PARAMETER") rowNumber: Int,
    ): VerificationChallenge = VerificationChallenge.reconstitute(
        id = result.getObject("id", UUID::class.java),
        accountId = result.getObject("account_id", UUID::class.java),
        verifierHash = result.getString("token_hash"),
        expiresAt = result.getTimestamp("expires_at").toInstant(),
        createdAt = result.getTimestamp("created_at").toInstant(),
        consumedAt = result.getTimestamp("consumed_at")?.toInstant(),
        revokedAt = result.getTimestamp("revoked_at")?.toInstant(),
    )

    private companion object {
        val CHALLENGE_SELECT = """
            SELECT id, account_id, token_hash, expires_at, consumed_at, revoked_at, created_at
            FROM auth_action_tokens
        """.trimIndent()
    }
}
