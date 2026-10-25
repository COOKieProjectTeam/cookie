package com.cookie.notification.messaging

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.nats.client.Message
import io.nats.client.impl.NatsJetStreamMetaData
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.dao.DataAccessResourceFailureException
import java.time.Duration

class NotificationConsumerTest {
    private val eventSubject="cookie.events.account.activated.v1"
    private val eventBytes=byteArrayOf(1)
    private val handler=mock(NotificationEventHandler::class.java)
    private val quarantine=mock(NotificationQuarantine::class.java)
    private val consumer=NotificationConsumer(mock(NotificationNatsConnection::class.java),NotificationMessagingProperties(),handler,quarantine,SimpleMeterRegistry())
    private val message=mock(Message::class.java)
    private val metadata=mock(NatsJetStreamMetaData::class.java)
    private fun prepare() {
        `when`(message.subject).thenReturn(eventSubject)
        `when`(message.data).thenReturn(eventBytes)
        `when`(message.metaData()).thenReturn(metadata)
        `when`(metadata.deliveredCount()).thenReturn(1000)
        `when`(metadata.streamSequence()).thenReturn(23)
    }
    @Test fun `long database outage never turns event into poison or acknowledges it`() {
        prepare()
        doThrow(DataAccessResourceFailureException("offline")).`when`(handler).handle(eventSubject,eventBytes)
        consumer.consume(message)
        val delay = ArgumentCaptor.forClass(Duration::class.java)
        verify(message).nakWithDelay(delay.capture())
        assertThat(delay.value).isBetween(Duration.ofSeconds(150), Duration.ofSeconds(300))
        verify(message,never()).ack()
        verifyNoInteractions(quarantine)
    }
    @Test fun `semantic poison is acknowledged only after quarantine succeeds`() {
        prepare()
        doThrow(InvalidNotificationEvent("invalid_json")).`when`(handler).handle(eventSubject,eventBytes)
        consumer.consume(message)
        val order=inOrder(quarantine,message)
        order.verify(quarantine).record(eventSubject,eventBytes,23,"invalid_json")
        order.verify(message).ack()
    }
    @Test fun `quarantine outage retries poison without acknowledging and the next event succeeds`() {
        prepare()
        doThrow(InvalidNotificationEvent("invalid_json")).`when`(handler).handle(eventSubject,eventBytes)
        doThrow(DataAccessResourceFailureException("offline")).`when`(quarantine).record(eventSubject,eventBytes,23,"invalid_json")
        consumer.consume(message)
        verify(message, never()).ack()
        verify(message).nakWithDelay(any(Duration::class.java))
        val healthy = mock(Message::class.java)
        `when`(healthy.subject).thenReturn("healthy")
        `when`(healthy.data).thenReturn(byteArrayOf(2))
        consumer.consume(healthy)
        verify(healthy).ack()
    }
}
