package com.cookie.identity.messaging

import com.cookie.identity.persistence.JdbcOutboxRepository
import com.cookie.identity.persistence.OutboxBacklogSnapshot
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class OutboxMetricsTest {
    @Test
    fun `publishes bounded-cardinality backlog and outcome signals`() {
        val repository = mock(JdbcOutboxRepository::class.java)
        `when`(repository.backlogSnapshot()).thenReturn(OutboxBacklogSnapshot(7, 12.345))
        val registry = SimpleMeterRegistry()
        val metrics = OutboxMetrics(repository, registry)

        metrics.refreshBacklog()
        metrics.recordPublishAttempt("account.activated")
        metrics.recordPublishSuccess("account.activated")

        assertThat(registry.get("cookie.identity.outbox.pending").gauge().value()).isEqualTo(7.0)
        assertThat(registry.get("cookie.identity.outbox.oldest.age.seconds").gauge().value()).isEqualTo(12.345)
        assertThat(
            registry.get("cookie.identity.outbox.publish")
                .tags("event.type", "account.activated", "outcome", "attempt")
                .counter()
                .count(),
        ).isEqualTo(1.0)
    }
}
