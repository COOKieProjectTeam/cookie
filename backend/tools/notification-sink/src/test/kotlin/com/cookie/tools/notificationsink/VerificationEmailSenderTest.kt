package com.cookie.tools.notificationsink

import jakarta.mail.Session
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Properties
import java.util.UUID

class VerificationEmailSenderTest {
    @Test
    fun `save changes keeps the event id as the message id`() {
        val eventId = UUID.randomUUID()
        val message = EventMimeMessage(Session.getInstance(Properties()), eventId)

        message.setText("verification")
        message.saveChanges()
        message.saveChanges()

        assertThat(message.getHeader("Message-ID", null))
            .isEqualTo("<$eventId@notification-sink.cookie.local>")
    }
}
