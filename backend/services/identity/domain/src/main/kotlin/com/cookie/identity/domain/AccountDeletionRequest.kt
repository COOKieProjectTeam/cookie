package com.cookie.identity.domain

import java.time.Instant
import java.util.UUID

enum class AccountDeletionRequestStatus {
    DELETION_PENDING,
}

data class AccountDeletionRequested(
    val accountId: UUID,
    val deletionRequestId: UUID,
    val requestedAt: Instant,
)

data class AccountDeletionRequestStart(
    val request: AccountDeletionRequest,
    val event: AccountDeletionRequested,
)

/** Stable, irreversible process identity for distributed account deletion. */
class AccountDeletionRequest private constructor(
    val id: UUID,
    val accountId: UUID,
    val idempotencyKey: UUID,
    val status: AccountDeletionRequestStatus,
    val requestedAt: Instant,
) {
    init {
        require(idempotencyKey.version() == UUID_V4 && idempotencyKey.variant() == RFC_4122_VARIANT) {
            "Account deletion idempotency key must be an RFC 4122 UUIDv4"
        }
    }

    override fun toString(): String =
        "AccountDeletionRequest(id=$id,accountId=$accountId,status=$status)"

    companion object {
        fun start(
            id: UUID,
            accountId: UUID,
            idempotencyKey: UUID,
            now: Instant,
        ): AccountDeletionRequestStart {
            val request = AccountDeletionRequest(
                id = id,
                accountId = accountId,
                idempotencyKey = idempotencyKey,
                status = AccountDeletionRequestStatus.DELETION_PENDING,
                requestedAt = now,
            )
            return AccountDeletionRequestStart(
                request = request,
                event = AccountDeletionRequested(
                    accountId = accountId,
                    deletionRequestId = id,
                    requestedAt = now,
                ),
            )
        }

        fun reconstitute(
            id: UUID,
            accountId: UUID,
            idempotencyKey: UUID,
            status: AccountDeletionRequestStatus,
            requestedAt: Instant,
        ): AccountDeletionRequest = AccountDeletionRequest(
            id,
            accountId,
            idempotencyKey,
            status,
            requestedAt,
        )

        private const val UUID_V4 = 4
        private const val RFC_4122_VARIANT = 2
    }
}
