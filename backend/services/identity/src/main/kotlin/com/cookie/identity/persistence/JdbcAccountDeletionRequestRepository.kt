package com.cookie.identity.persistence

import com.cookie.identity.application.ports.AccountDeletionRequestRepository
import com.cookie.identity.domain.AccountDeletionRequest
import com.cookie.identity.domain.AccountDeletionRequestStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class JdbcAccountDeletionRequestRepository(
    private val jdbc: JdbcTemplate,
) : AccountDeletionRequestRepository {
    override fun findByAccountId(accountId: UUID): AccountDeletionRequest? = jdbc.query(
        """
        SELECT id, account_id, idempotency_key, status, requested_at
        FROM account_deletion_requests
        WHERE account_id = ?
        """.trimIndent(),
        ::mapRequest,
        accountId,
    ).singleOrNull()

    override fun add(request: AccountDeletionRequest) {
        requireActiveTransaction("Add account deletion request")
        requireSingleRow(
            "Insert account deletion request",
            jdbc.update(
                """
                INSERT INTO account_deletion_requests(
                    id, account_id, idempotency_key, status, requested_at
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                request.id,
                request.accountId,
                request.idempotencyKey,
                request.status.name,
                request.requestedAt.asJdbcTimestamp(),
            ),
        )
    }

    private fun mapRequest(
        result: ResultSet,
        @Suppress("UNUSED_PARAMETER") rowNumber: Int,
    ): AccountDeletionRequest = AccountDeletionRequest.reconstitute(
        id = result.getObject("id", UUID::class.java),
        accountId = result.getObject("account_id", UUID::class.java),
        idempotencyKey = result.getObject("idempotency_key", UUID::class.java),
        status = AccountDeletionRequestStatus.valueOf(result.getString("status")),
        requestedAt = result.getTimestamp("requested_at").toInstant(),
    )
}
