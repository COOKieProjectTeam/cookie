package com.cookie.identity.persistence

import com.cookie.identity.application.ports.RefreshFamilyRepository
import com.cookie.identity.application.RefreshCredentialLookup
import com.cookie.identity.domain.DeviceId
import com.cookie.identity.domain.RefreshCredential
import com.cookie.identity.domain.RefreshFamily
import com.cookie.identity.domain.RefreshFamilyRevokeReason
import com.cookie.identity.domain.RefreshFamilyStatus
import com.cookie.identity.domain.VerifierHash
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Repository
class JdbcRefreshFamilyRepository(
    private val jdbc: JdbcTemplate,
) : RefreshFamilyRepository {
    override fun findCredentialLookup(id: UUID): RefreshCredentialLookup? = jdbc.query(
        "SELECT family_id, verifier_hash FROM refresh_credentials WHERE id = ?",
        { result, _ ->
            RefreshCredentialLookup(
                familyId = result.getObject("family_id", UUID::class.java),
                verifierHash = VerifierHash.fromSha256Hex(result.getString("verifier_hash")),
            )
        },
        id,
    ).singleOrNull()

    override fun findByCredentialIdForUpdate(credentialId: UUID): RefreshFamily? {
        requireActiveTransaction("Lock refresh family")
        val family = jdbc.query(
            """
            $FAMILY_SELECT
            JOIN refresh_credentials presented ON presented.family_id = f.id
            WHERE presented.id = ?
            FOR UPDATE OF f
            """.trimIndent(),
            ::mapFamilyRow,
            credentialId,
        ).singleOrNull() ?: return null

        // The root lock is acquired in a separate statement intentionally. Under
        // READ COMMITTED this second query observes a rotation that committed
        // while the first statement was waiting, rather than hydrating a stale
        // presented/current pair from the locking statement's old snapshot.
        val credentials = jdbc.query(
            """
            $CREDENTIAL_SELECT
            WHERE c.family_id = ?
              AND (c.id = ? OR c.redeemed_at IS NULL)
            ORDER BY c.created_at, c.id
            FOR UPDATE
            """.trimIndent(),
            ::mapCredential,
            family.id,
            credentialId,
        )
        return RefreshFamily.reconstitute(
            id = family.id,
            accountId = family.accountId,
            deviceId = family.deviceId,
            expiresAt = family.expiresAt,
            createdAt = family.createdAt,
            status = family.status,
            lastActivityAt = family.lastActivityAt,
            revokedAt = family.revokedAt,
            revokeReason = family.revokeReason,
            reuseDetectedAt = family.reuseDetectedAt,
            credentials = credentials,
        )
    }

    override fun add(family: RefreshFamily) {
        requireActiveTransaction("Add refresh family")
        val credentials = family.credentialSnapshots()
        check(credentials.size == 1 && !credentials.single().isRedeemed) {
            "A new refresh family must contain exactly one unredeemed credential"
        }
        requireSingleRow(
            "Insert refresh family",
            jdbc.update(
                """
                INSERT INTO refresh_families(
                    id, account_id, device_id, status, expires_at, created_at,
                    last_activity_at, revoked_at, revoke_reason, reuse_detected_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                family.id,
                family.accountId,
                family.deviceId?.value,
                family.status.name,
                family.expiresAt.asJdbcTimestamp(),
                family.createdAt.asJdbcTimestamp(),
                family.lastActivityAt.asJdbcTimestamp(),
                family.revokedAt?.asJdbcTimestamp(),
                family.revokeReason?.name,
                family.reuseDetectedAt?.asJdbcTimestamp(),
            ),
        )
        insertCredential(credentials.single())
    }

    override fun save(family: RefreshFamily) {
        requireActiveTransaction("Save refresh family")
        requireSingleRow(
            "Update refresh family",
            jdbc.update(
                """
                UPDATE refresh_families
                SET status = ?, last_activity_at = ?, revoked_at = ?,
                    revoke_reason = ?, reuse_detected_at = ?
                WHERE id = ? AND account_id = ?
                """.trimIndent(),
                family.status.name,
                family.lastActivityAt.asJdbcTimestamp(),
                family.revokedAt?.asJdbcTimestamp(),
                family.revokeReason?.name,
                family.reuseDetectedAt?.asJdbcTimestamp(),
                family.id,
                family.accountId,
            ),
        )
        family.credentialSnapshots().forEach(::saveOrInsertCredential)
    }

    private fun saveOrInsertCredential(credential: RefreshCredential) {
        val updated = jdbc.update(
            """
            UPDATE refresh_credentials
            SET redeemed_at = ?, replaced_by_credential_id = ?,
                rotation_idempotency_key = ?, retry_until = ?
            WHERE id = ? AND family_id = ?
            """.trimIndent(),
            credential.redeemedAt?.asJdbcTimestamp(),
            credential.replacedByCredentialId,
            credential.rotationIdempotencyKey,
            credential.retryUntil?.asJdbcTimestamp(),
            credential.id,
            credential.familyId,
        )
        when (updated) {
            0 -> insertCredential(credential)
            1 -> Unit
            else -> error("Update refresh credential affected $updated rows instead of at most one")
        }
    }

    private fun insertCredential(credential: RefreshCredential) {
        requireSingleRow(
            "Insert refresh credential",
            jdbc.update(
                """
                INSERT INTO refresh_credentials(
                    id, family_id, verifier_hash, created_at, redeemed_at,
                    replaced_by_credential_id, rotation_idempotency_key, retry_until
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                credential.id,
                credential.familyId,
                credential.verifierHash.value,
                credential.createdAt.asJdbcTimestamp(),
                credential.redeemedAt?.asJdbcTimestamp(),
                credential.replacedByCredentialId,
                credential.rotationIdempotencyKey,
                credential.retryUntil?.asJdbcTimestamp(),
            ),
        )
    }

    private fun mapCredential(result: ResultSet, @Suppress("UNUSED_PARAMETER") rowNumber: Int): RefreshCredential =
        RefreshCredential.reconstitute(
            id = result.getObject("id", UUID::class.java),
            familyId = result.getObject("family_id", UUID::class.java),
            verifierHash = VerifierHash.fromSha256Hex(result.getString("verifier_hash")),
            createdAt = result.getTimestamp("created_at").toInstant(),
            redeemedAt = result.getTimestamp("redeemed_at")?.toInstant(),
            replacedByCredentialId = result.getObject("replaced_by_credential_id", UUID::class.java),
            rotationIdempotencyKey = result.getObject("rotation_idempotency_key", UUID::class.java),
            retryUntil = result.getTimestamp("retry_until")?.toInstant(),
        )

    private fun mapFamilyRow(result: ResultSet, @Suppress("UNUSED_PARAMETER") rowNumber: Int) = FamilyRow(
        id = result.getObject("id", UUID::class.java),
        accountId = result.getObject("account_id", UUID::class.java),
        deviceId = result.getString("device_id")?.let(DeviceId::reconstitute),
        status = RefreshFamilyStatus.valueOf(result.getString("status")),
        expiresAt = result.getTimestamp("expires_at").toInstant(),
        createdAt = result.getTimestamp("created_at").toInstant(),
        lastActivityAt = result.getTimestamp("last_activity_at").toInstant(),
        revokedAt = result.getTimestamp("revoked_at")?.toInstant(),
        revokeReason = result.getString("revoke_reason")?.let(RefreshFamilyRevokeReason::valueOf),
        reuseDetectedAt = result.getTimestamp("reuse_detected_at")?.toInstant(),
    )

    private data class FamilyRow(
        val id: UUID,
        val accountId: UUID,
        val deviceId: DeviceId?,
        val status: RefreshFamilyStatus,
        val expiresAt: Instant,
        val createdAt: Instant,
        val lastActivityAt: Instant,
        val revokedAt: Instant?,
        val revokeReason: RefreshFamilyRevokeReason?,
        val reuseDetectedAt: Instant?,
    )

    private companion object {
        val FAMILY_SELECT = """
            SELECT f.id, f.account_id, f.device_id, f.status, f.expires_at,
                   f.created_at, f.last_activity_at, f.revoked_at,
                   f.revoke_reason, f.reuse_detected_at
            FROM refresh_families f
        """.trimIndent()

        val CREDENTIAL_SELECT = """
            SELECT c.id, c.family_id, c.verifier_hash, c.created_at,
                   c.redeemed_at, c.replaced_by_credential_id,
                   c.rotation_idempotency_key, c.retry_until
            FROM refresh_credentials c
        """.trimIndent()
    }
}
