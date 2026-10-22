package com.cookie.notification.messaging

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.nats.client.JetStreamSubscription
import io.nats.client.Message
import io.nats.client.impl.NatsJetStreamMetaData
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.springframework.test.util.ReflectionTestUtils
import java.time.Duration

class NotificationRecoveryTest {
    private val subject="cookie.events.account.deletion.requested.v1"
    private val badData=byteArrayOf(1)
    private val goodData=byteArrayOf(2)
    private val handler=mock(NotificationEventHandler::class.java)
    private val quarantine=mock(NotificationQuarantine::class.java)
    private val registry=SimpleMeterRegistry()
    private val consumer=NotificationConsumer(mock(NotificationNatsConnection::class.java),NotificationMessagingProperties(),handler,quarantine,registry)
    private fun message(data:ByteArray):Message = mock(Message::class.java).also { message ->
        `when`(message.subject).thenReturn(subject);`when`(message.data).thenReturn(data)
        val metadata=mock(NatsJetStreamMetaData::class.java)
        `when`(metadata.streamSequence()).thenReturn(23);`when`(metadata.deliveredCount()).thenReturn(1)
        `when`(message.metaData()).thenReturn(metadata)
    }
    private fun batch(vararg messages:Message) {
        val sub=mock(JetStreamSubscription::class.java)
        `when`(sub.isActive).thenReturn(true)
        `when`(sub.fetch(20,Duration.ofSeconds(1))).thenReturn(messages.toList())
        ReflectionTestUtils.setField(consumer,"subscription",sub)
    }

    @ParameterizedTest
    @ValueSource(strings=["quarantine","nak","quarantine ack"])
    fun `recovery failure leaves message for redelivery but handles healthy fetched tail`(fault:String) {
        val bad=message(badData);val good=message(goodData);batch(bad,good)
        if(fault=="nak") {
            doThrow(IllegalStateException("handler unavailable")).`when`(handler).handle(subject,badData)
            doThrow(IllegalStateException("NAK unavailable")).`when`(bad).nakWithDelay(Duration.ofSeconds(2))
        } else {
            doThrow(InvalidNotificationEvent("invalid_json")).`when`(handler).handle(subject,badData)
            if(fault=="quarantine") doThrow(IllegalStateException("quarantine unavailable")).`when`(quarantine).record(subject,badData,23,"invalid_json")
            else doThrow(IllegalStateException("ACK unavailable")).`when`(bad).ack()
        }
        consumer.poll()
        verify(handler).handle(subject,goodData);verify(good).ack()
        if(fault!="quarantine ack") verify(bad,never()).ack()
        assertThat(registry.get("notification.inbox.recovery.failures").counter().count()).isEqualTo(1.0)
    }

    @Test
    fun `interrupted handler stops traversal and preserves interruption without NAK`() {
        val bad=message(badData);val good=message(goodData);batch(bad,good)
        doAnswer { throw InterruptedException("cancelled") }.`when`(handler).handle(subject,badData)
        try {
            consumer.poll()
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            verify(handler,never()).handle(subject,goodData)
            verify(bad,never()).ack();verify(bad,never()).nakWithDelay(Duration.ofSeconds(2))
        } finally { Thread.interrupted() }
    }
}
