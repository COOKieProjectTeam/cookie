package com.cookie.nutrition

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.nats.client.JetStream
import io.nats.client.api.PublishAck
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class OutboxInterruptionTest {
    @Test fun `release failure does not erase interrupted publisher status`() {
        val future=object : CompletableFuture<PublishAck>() {
            override fun get(timeout:Long,unit:TimeUnit):PublishAck = throw InterruptedException("synthetic interrupt")
        }
        val stream=mock(JetStream::class.java) { invocation ->
            if(invocation.method.name=="publishAsync") future else RETURNS_DEFAULTS.answer(invocation)
        }
        val nats=mock(NatsJetStreamConnection::class.java)
        `when`(nats.jetStream()).thenReturn(stream)
        val publisher=spy(NutritionOutbox(mock(JdbcTemplate::class.java),nats,SimpleMeterRegistry()))
        val claim=NutritionOutbox.Claim(UUID.randomUUID(),UUID.randomUUID(),"cookie.events.nutrition.day.changed.v1","{}",1)
        doReturn(listOf(claim)).`when`(publisher).claim()
        doThrow(IllegalStateException("synthetic release failure")).`when`(publisher).release(claim)
        try {
            publisher.publishAvailable()
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally { Thread.interrupted() }
    }
}
