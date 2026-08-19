package com.cookie.identity.messaging

import com.cookie.identity.persistence.IdentityRepository
import com.cookie.platform.postgres.Transactions
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

@Component
class InboxProcessor(
    private val repository: IdentityRepository,
    private val transactions: Transactions,
    private val clock: Clock,
) {
    fun process(
        consumer: String,
        eventId: UUID,
        eventType: String = "unknown",
        effect: () -> Unit,
    ): Boolean =
        transactions.required {
            if (!repository.insertInbox(consumer, eventId, eventType, clock.instant())) return@required false
            effect()
            true
        }
}
