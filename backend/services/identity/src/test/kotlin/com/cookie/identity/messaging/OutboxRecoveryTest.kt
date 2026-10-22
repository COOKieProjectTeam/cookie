package com.cookie.identity.messaging

import com.cookie.identity.persistence.JdbcOutboxRepository
import com.cookie.identity.persistence.OutboxRecord
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.nats.client.JetStream
import io.nats.client.api.PublishAck
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import tools.jackson.databind.json.JsonMapper
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class OutboxRecoveryTest {
    private fun record()=OutboxRecord(UUID.randomUUID(),UUID.randomUUID(),"account.activated",1,"account",
        UUID.randomUUID().toString(),"{}",Instant.now(),1,null,null,null)

    @ParameterizedTest
    @ValueSource(booleans=[true,false])
    fun `release failure leaves failed record leased and continues healthy record`(failSubmission:Boolean) {
        val bad=record();val good=record();val registry=SimpleMeterRegistry()
        val repository=mock(JdbcOutboxRepository::class.java) { invocation ->
            when(invocation.method.name) {
                "claim" -> listOf(bad,good)
                "release" -> throw IllegalStateException("release unavailable")
                "markPublished" -> if(invocation.getArgument<UUID>(0)==bad.eventId) throw IllegalStateException("completion unavailable") else true
                else -> RETURNS_DEFAULTS.answer(invocation)
            }
        }
        var submissions=0
        val stream=mock(JetStream::class.java) { invocation ->
            if(invocation.method.name=="publishAsync") {
                if(submissions++==0 && failSubmission) throw IllegalStateException("publish unavailable")
                CompletableFuture.completedFuture(mock(PublishAck::class.java))
            } else RETURNS_DEFAULTS.answer(invocation)
        }
        val nats=mock(NatsJetStreamConnection::class.java);`when`(nats.jetStream()).thenReturn(stream)
        val publisher=OutboxPublisher(repository,nats,JsonMapper.builder().build(),SecureRandom(),OutboxMetrics(repository,registry))
        publisher.publishAvailable()
        verify(repository).markPublished(good.eventId,good.claimId)
        assertThat(submissions).isEqualTo(2)
        assertThat(registry.get("cookie.identity.outbox.publish").tags("event.type","account.activated","outcome","recovery_failure").counter().count()).isEqualTo(1.0)
    }

    @Test
    fun `submission interruption stops batch and preserves flag even if release fails`() {
        val bad=record();val good=record()
        val repository=mock(JdbcOutboxRepository::class.java) { invocation ->
            when(invocation.method.name) {
                "claim" -> listOf(bad,good)
                "release" -> throw IllegalStateException("release unavailable")
                else -> RETURNS_DEFAULTS.answer(invocation)
            }
        }
        var submissions=0
        val stream=mock(JetStream::class.java) { invocation ->
            if(invocation.method.name=="publishAsync") {submissions++;throw InterruptedException("cancelled")}
            else RETURNS_DEFAULTS.answer(invocation)
        }
        val nats=mock(NatsJetStreamConnection::class.java);`when`(nats.jetStream()).thenReturn(stream)
        try {
            OutboxPublisher(repository,nats,JsonMapper.builder().build(),SecureRandom(),OutboxMetrics(repository,SimpleMeterRegistry())).publishAvailable()
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(submissions).isEqualTo(1)
            verify(repository,never()).markPublished(good.eventId,good.claimId)
        } finally { Thread.interrupted() }
    }
}
