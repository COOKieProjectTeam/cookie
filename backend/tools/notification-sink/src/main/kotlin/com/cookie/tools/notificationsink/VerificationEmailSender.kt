package com.cookie.tools.notificationsink

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.springframework.stereotype.Component
import java.util.Properties
import java.util.UUID

fun interface VerificationEmailSender {
    fun send(eventId: UUID, recipient: String, token: String)
}

@Component
class SmtpVerificationEmailSender(
    private val configuration: NotificationSinkProperties,
) : VerificationEmailSender {
    override fun send(eventId: UUID, recipient: String, token: String) {
        val properties = Properties().apply {
            put("mail.smtp.host", configuration.smtpHost)
            put("mail.smtp.port", configuration.smtpPort.toString())
            put("mail.smtp.connectiontimeout", SMTP_TIMEOUT_MILLIS)
            put("mail.smtp.timeout", SMTP_TIMEOUT_MILLIS)
            put("mail.smtp.writetimeout", SMTP_TIMEOUT_MILLIS)
        }
        val message = EventMimeMessage(Session.getInstance(properties), eventId).apply {
            setFrom(InternetAddress("noreply@cookie.local", "COOKie"))
            setRecipient(Message.RecipientType.TO, InternetAddress(recipient))
            subject = "Подтвердите email в COOKie"
            setText("Токен подтверждения: $token", Charsets.UTF_8.name())
        }
        Transport.send(message)
    }

    private companion object {
        const val SMTP_TIMEOUT_MILLIS = "10000"
    }
}

internal class EventMimeMessage(
    session: Session,
    private val eventId: UUID,
) : MimeMessage(session) {
    override fun updateMessageID() {
        setHeader("Message-ID", "<$eventId@notification-sink.cookie.local>")
    }
}
