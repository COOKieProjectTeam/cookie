package com.cookie.identity.persistence

import com.cookie.identity.application.ports.RefreshSessionRepository
import com.cookie.identity.domain.RefreshRevokeReason
import com.cookie.identity.domain.RefreshSession
import com.cookie.identity.domain.RefreshSessionStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class JdbcRefreshSessionRepository(
    private val jdbc: JdbcTemplate,
) : RefreshSessionRepository {
    override fun findById(id: UUID): RefreshSession? = jdbc.query(
        "$SESSION_SELECT WHERE id = ?",
        ::mapSession,
        id,
    ).singleOrNull()

    override fun lockFamily(familyId: UUID) {
        jdbc.acquireTransactionAdvisoryLock("identity:refresh-family:$familyId")
    }

    override fun findFamilyForUpdate(familyId: UUID): List<RefreshSession> {
        requireActiveTransaction("Lock refresh-session family")
        return jdbc.query(
            """
            $SESSION_SELECT
            WHERE family_id = ?
            ORDER BY created_at, id
            FOR UPDATE
            """.trimIndent(),
            ::mapSession,
            familyId,
        )
    }

    override fun add(session: RefreshSession) {
        requireActiveTransaction("Add refresh session")
        requireSingleRow(
            "Insert refresh session",
            jdbc.update(
                """
                INSERT INTO refresh_sessions(
                    id, account_id, family_id, token_hash, status, device_id,
                    replaced_by_session_id, family_expires_at, created_at,
                    last_used_at, revoked_at, revoke_reason, reuse_detected_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                session.id,
                session.accountId,
                session.familyId,
                session.verifierHash,
                session.status.name,
                session.deviceId,
                session.replacedBySessionId,
                session.familyExpiresAt.asJdbcTimestamp(),
                session.createdAt.asJdbcTimestamp(),
                session.lastUsedAt?.asJdbcTimestamp(),
                session.revokedAt?.asJdbcTimestamp(),
                session.revokeReason?.name,
                session.reuseDetectedAt?.asJdbcTimestamp(),
            ),
        )
    }

    override fun save(session: RefreshSession) {
        requireActiveTransaction("Save refresh session")
        requireSingleRow(
            "Update refresh session",
            jdbc.update(
                """
                UPDATE refresh_sessions
                SET status = ?, replaced_by_session_id = ?, last_used_at = ?,
                    revoked_at = ?, revoke_reason = ?, reuse_detected_at = ?
                WHERE id = ? AND account_id = ? AND family_id = ?
                """.trimIndent(),
                session.status.name,
                session.replacedBySessionId,
                session.lastUsedAt?.asJdbcTimestamp(),
                session.revokedAt?.asJdbcTimestamp(),
                session.revokeReason?.name,
                session.reuseDetectedAt?.asJdbcTimestamp(),
                session.id,
                session.accountId,
                session.familyId,
            ),
        )
    }

    private fun mapSession(result: ResultSet, @Suppress("UNUSED_PARAMETER") rowNumber: Int): RefreshSession =
        RefreshSession.reconstitute(
            id = result.getObject("id", UUID::class.java),
            accountId = result.getObject("account_id", UUID::class.java),
            familyId = result.getObject("family_id", UUID::class.java),
            verifierHash = result.getString("token_hash"),
            status = RefreshSessionStatus.valueOf(result.getString("status")),
            deviceId = result.getString("device_id"),
            familyExpiresAt = result.getTimestamp("family_expires_at").toInstant(),
            createdAt = result.getTimestamp("created_at").toInstant(),
            replacedBySessionId = result.getObject("replaced_by_session_id", UUID::class.java),
            lastUsedAt = result.getTimestamp("last_used_at")?.toInstant(),
            revokedAt = result.getTimestamp("revoked_at")?.toInstant(),
            revokeReason = result.getString("revoke_reason")?.let(RefreshRevokeReason::valueOf),
            reuseDetectedAt = result.getTimestamp("reuse_detected_at")?.toInstant(),
        )

    private companion object {
        val SESSION_SELECT = """
            SELECT id, account_id, family_id, token_hash, status, device_id,
                   replaced_by_session_id, family_expires_at, created_at,
                   last_used_at, revoked_at, revoke_reason, reuse_detected_at
            FROM refresh_sessions
        """.trimIndent()
    }
}
