package com.cookie.platform.messaging

import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.JetStreamSubscription
import io.nats.client.Message
import io.nats.client.PullSubscribeOptions
import io.nats.client.impl.NatsJetStreamMetaData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.time.Duration

class JetStreamPullConsumerTest {
    private class Poison : RuntimeException()

    private fun message(): Message = mock(Message::class.java).also {
        val metadata = mock(NatsJetStreamMetaData::class.java)
        `when`(metadata.deliveredCount()).thenReturn(1000)
        `when`(it.metaData()).thenReturn(metadata)
    }

    private fun runner(connection: () -> Connection, handle: (Message) -> Unit = {},
                       quarantine: (Message, String) -> Unit = { _, _ -> }): JetStreamPullConsumer =
        JetStreamPullConsumer(connection, "STREAM", "durable", handle, { if (it is Poison) "invalid" else null },
            quarantine, {}, {}, {}, ExponentialRetryDelay { 0.0 })

    private fun subscription(connection: Connection): JetStreamSubscription {
        val jetStream = mock(JetStream::class.java)
        val subscription = mock(JetStreamSubscription::class.java)
        `when`(connection.jetStream()).thenReturn(jetStream)
        `when`(jetStream.subscribe(isNull<String>(), any(PullSubscribeOptions::class.java))).thenReturn(subscription)
        `when`(subscription.isActive).thenReturn(true)
        `when`(subscription.fetch(20, Duration.ofSeconds(1))).thenReturn(emptyList())
        return subscription
    }

    @Test fun `failed quarantine and failed NAK do not prevent healthy batch item from being acknowledged`() {
        val connection = mock(Connection::class.java)
        val subscription = subscription(connection)
        val poison = message()
        val healthy = message()
        `when`(subscription.fetch(20, Duration.ofSeconds(1))).thenReturn(listOf(poison, healthy))
        doThrow(IllegalStateException("NAK offline")).`when`(poison).nakWithDelay(any(Duration::class.java))
        val runner = runner({ connection }, handle = { if (it === poison) throw Poison() },
            quarantine = { _, _ -> error("database offline") })
        runner.poll()
        verify(poison, never()).ack()
        verify(poison).nakWithDelay(Duration.ofSeconds(150))
        verify(healthy).ack()
    }

    @Test fun `failed ACK retries committed item and does not abort following batch item`() {
        val connection = mock(Connection::class.java)
        val subscription = subscription(connection)
        val first = message()
        val healthy = message()
        `when`(subscription.fetch(20, Duration.ofSeconds(1))).thenReturn(listOf(first, healthy))
        doThrow(IllegalStateException("ACK offline")).`when`(first).ack()
        runner({ connection }).poll()
        verify(first).nakWithDelay(Duration.ofSeconds(150))
        verify(healthy).ack()
    }

    @Test fun `quarantine completes before poison message is acknowledged`() {
        val poisoned = message()
        val actions = mutableListOf<String>()
        doAnswer { actions.add("ack"); null }.`when`(poisoned).ack()
        runner({ error("unused") }, handle = { throw Poison() }, quarantine = { _, _ -> actions.add("quarantine") })
            .consume(poisoned)
        assertThat(actions).containsExactly("quarantine", "ack")
    }

    @Test fun `connection replacement rebinds even when old subscription remains active`() {
        val first = mock(Connection::class.java)
        val second = mock(Connection::class.java)
        val old = subscription(first)
        val replacement = subscription(second)
        var current = first
        val runner = runner({ current })
        runner.poll()
        current = second
        runner.poll()
        verify(old).unsubscribe()
        verify(replacement).fetch(20, Duration.ofSeconds(1))
    }

    @Test fun `fetch failure clears subscription and next poll rebinds`() {
        val connection = mock(Connection::class.java)
        val first = subscription(connection)
        `when`(first.fetch(20, Duration.ofSeconds(1))).thenThrow(IllegalStateException("offline"))
        val runner = runner({ connection })
        runner.poll()
        verify(first).unsubscribe()
        val second = subscription(connection)
        runner.poll()
        verify(second).fetch(20, Duration.ofSeconds(1))
    }

    @Test fun `jitter remains within exponential bound and caps long outages at five minutes`() {
        val lower = ExponentialRetryDelay { 0.0 }
        val upper = ExponentialRetryDelay { 1.0 }
        assertThat(lower.delay(1)).isEqualTo(Duration.ofSeconds(1))
        assertThat(upper.delay(1)).isEqualTo(Duration.ofSeconds(2))
        assertThat(lower.delay(4)).isEqualTo(Duration.ofSeconds(8))
        assertThat(upper.delay(4)).isEqualTo(Duration.ofSeconds(16))
        assertThat(lower.delay(Long.MAX_VALUE)).isEqualTo(Duration.ofSeconds(150))
        assertThat(upper.delay(Long.MAX_VALUE)).isEqualTo(Duration.ofSeconds(300))
    }
}
