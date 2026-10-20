package com.cookie.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AccountDeletionRequestTest {
    private val now = Instant.parse("2026-08-20T10:00:00Z")

    @Test
    fun `start creates stable pending request and domain event together`() {
        val requestId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID()

        val started = AccountDeletionRequest.start(requestId, accountId, idempotencyKey, now)

        assertThat(started.request.id).isEqualTo(requestId)
        assertThat(started.request.accountId).isEqualTo(accountId)
        assertThat(started.request.idempotencyKey).isEqualTo(idempotencyKey)
        assertThat(started.request.status).isEqualTo(AccountDeletionRequestStatus.DELETION_PENDING)
        assertThat(started.request.requestedAt).isEqualTo(now)
        assertThat(started.event).isEqualTo(AccountDeletionRequested(accountId, requestId, now))
    }

    @Test
    fun `request rejects an idempotency key that is not RFC 4122 UUIDv4`() {
        val predictableKey = UUID.fromString("00000000-0000-1000-8000-000000000000")

        assertThatIllegalArgumentException().isThrownBy {
            AccountDeletionRequest.start(UUID.randomUUID(), UUID.randomUUID(), predictableKey, now)
        }
    }

    @Test
    fun `reconstitution preserves the only honest first-version status`() {
        val request = AccountDeletionRequest.reconstitute(
            id = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            idempotencyKey = UUID.randomUUID(),
            status = AccountDeletionRequestStatus.DELETION_PENDING,
            requestedAt = now,
        )

        assertThat(request.status).isEqualTo(AccountDeletionRequestStatus.DELETION_PENDING)
    }

    @Test
    fun `diagnostic representation does not expose idempotency key`() {
        val idempotencyKey = UUID.randomUUID()
        val request = AccountDeletionRequest.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            idempotencyKey,
            now,
        ).request

        assertThat(request.toString()).doesNotContain(idempotencyKey.toString())
    }
}
