package com.cookie.nutrition

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.nats.client.JetStreamSubscription
import io.nats.client.Message
import io.nats.client.impl.NatsJetStreamMetaData
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.env.MockEnvironment
import org.springframework.test.util.ReflectionTestUtils
import java.time.Duration

class DeletionRecoveryTest {
    @ParameterizedTest @ValueSource(strings=["TERM","NAK"])
    fun `broker recovery failure cannot skip healthy batch tail`(fault:String) {
        val handler=mock(AccountDeletion::class.java)
        val jdbc=mock(JdbcTemplate::class.java)
        val consumer=DeletionConsumer(mock(NatsJetStreamConnection::class.java),handler,jdbc,SimpleMeterRegistry(),MockEnvironment().withProperty("spring.profiles.active","test"))
        val subscription=mock(JetStreamSubscription::class.java)
        val bad=mock(Message::class.java);val good=mock(Message::class.java)
        val badBytes=byteArrayOf(1);val goodBytes=byteArrayOf(2)
        `when`(bad.subject).thenReturn(DeletionConsumer.SUBJECT);`when`(good.subject).thenReturn(DeletionConsumer.SUBJECT)
        `when`(bad.data).thenReturn(badBytes);`when`(good.data).thenReturn(goodBytes)
        `when`(bad.metaData()).thenReturn(mock(NatsJetStreamMetaData::class.java))
        `when`(subscription.isActive).thenReturn(true)
        `when`(subscription.fetch(20,Duration.ofMillis(200))).thenReturn(listOf(bad,good))
        ReflectionTestUtils.setField(consumer,"subscription",subscription)
        if(fault=="TERM") {
            doThrow(NutritionFailure(400,"INVALID_INPUT")).`when`(handler).handle(DeletionConsumer.SUBJECT,badBytes)
            doThrow(IllegalStateException("synthetic TERM failure")).`when`(bad).term()
        } else {
            doThrow(IllegalStateException("synthetic temporary failure")).`when`(handler).handle(DeletionConsumer.SUBJECT,badBytes)
            doThrow(IllegalStateException("synthetic NAK failure")).`when`(bad).nakWithDelay(Duration.ofSeconds(5))
        }
        consumer.poll()
        verify(handler).handle(DeletionConsumer.SUBJECT,goodBytes)
        verify(good).ackSync(Duration.ofSeconds(2))
        verify(bad,never()).ackSync(any(Duration::class.java))
    }
}
