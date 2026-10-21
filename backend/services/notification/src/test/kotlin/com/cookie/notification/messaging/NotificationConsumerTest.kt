package com.cookie.notification.messaging

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.nats.client.Message
import io.nats.client.impl.NatsJetStreamMetaData
import org.junit.jupiter.api.Test
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
        verify(message).nakWithDelay(Duration.ofSeconds(256))
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
}
