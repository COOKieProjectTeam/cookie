package com.cookie.notification.delivery

import com.cookie.notification.config.SmtpProperties
import com.cookie.notification.security.VerificationEmail
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.eclipse.angus.mail.smtp.SMTPTransport
import java.util.Properties
import java.util.UUID
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import jakarta.annotation.PreDestroy

class SmtpPreparationException(val result: ProviderResult) : RuntimeException("SMTP preparation failed")

interface PreparedEmail : AutoCloseable { fun submit(): ProviderResult }
fun interface VerificationSender { fun prepare(job: DeliveryJob, email: VerificationEmail): PreparedEmail }

/** Connect/authenticate before the durable SMTP_SUBMITTING marker; never retry inside this adapter. */
class SmtpVerificationSender(private val properties: SmtpProperties, private val templates: NotificationTemplates) : VerificationSender {
    private val timeouts = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "notification-smtp-deadline").apply { isDaemon = true }
    }
    override fun prepare(job: DeliveryJob, email: VerificationEmail): PreparedEmail {
        val socketFactory = AbortableSocketFactory()
        val configuration = Properties().apply {
            put("mail.smtp.host", properties.host)
            put("mail.smtp.port", properties.port.toString())
            put("mail.smtp.auth", properties.username.isNotBlank().toString())
            put("mail.smtp.starttls.enable", properties.starttlsRequired.toString())
            put("mail.smtp.starttls.required", properties.starttlsRequired.toString())
            put("mail.smtp.ssl.checkserveridentity", "true")
            put("mail.smtp.connectiontimeout", "5000")
            put("mail.smtp.timeout", "30000")
            put("mail.smtp.writetimeout", "30000")
            put("mail.smtp.sendpartial", "false")
            put("mail.smtp.reportsuccess", "false")
            put("mail.smtp.chunksize", "0")
            put("mail.smtp.quitwait", "false")
            put("mail.smtp.socketFactory", socketFactory)
            put("mail.smtp.socketFactory.fallback", "false")
        }
        val session = Session.getInstance(configuration).apply { debug = false }
        val content = templates.verification(email)
        val message = StableMessage(session, job.id, properties.messageIdDomain).apply {
            setFrom(InternetAddress(properties.from, true))
            setRecipient(Message.RecipientType.TO, InternetAddress(email.recipient, true))
            setSubject(content.subject, Charsets.UTF_8.name())
            val multipart = MimeMultipart("alternative")
            multipart.addBodyPart(MimeBodyPart().apply { setText(content.text, "UTF-8") })
            multipart.addBodyPart(MimeBodyPart().apply { setContent(content.html, "text/html; charset=UTF-8") })
            setContent(multipart)
            saveChanges()
        }
        val transport = session.getTransport("smtp") as SMTPTransport
        val preparationDeadline = timeouts.schedule({ socketFactory.abort() }, 60, TimeUnit.SECONDS)
        try {
            transport.connect(properties.host, properties.port, properties.username.takeIf(String::isNotBlank),
                properties.password.takeIf(String::isNotBlank))
        } catch (_: jakarta.mail.AuthenticationFailedException) {
            runCatching { transport.close() }
            throw SmtpPreparationException(ProviderResult.Unavailable("SMTP_AUTH"))
        } catch (_: Exception) {
            runCatching { transport.close() }
            throw SmtpPreparationException(ProviderResult.Retry("SMTP_CONNECT"))
        } finally {
            preparationDeadline.cancel(false)
        }
        return object : PreparedEmail {
            override fun submit(): ProviderResult {
                // Closing the underlying socket is independent of SMTPTransport's synchronized send/close lock.
                val deadline = timeouts.schedule({ socketFactory.abort() }, 60, TimeUnit.SECONDS)
                return try {
                    transport.sendMessage(message, message.allRecipients)
                    ProviderResult.Accepted(message.messageID)
                } catch (exception: Exception) { classifySmtpFailure(exception) }
                finally { deadline.cancel(false) }
            }
            override fun close() { runCatching { transport.close() } }
        }
    }
    @PreDestroy fun close() { timeouts.shutdownNow() }
}

private class AbortableSocketFactory : SocketFactory() {
    private val active = AtomicReference<Socket?>()
    fun abort() { runCatching { active.get()?.close() } }
    override fun createSocket(): Socket = Socket().also(active::set)
    override fun createSocket(host: String, port: Int): Socket = Socket(host, port).also(active::set)
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        Socket(host, port, localHost, localPort).also(active::set)
    override fun createSocket(host: InetAddress, port: Int): Socket = Socket(host, port).also(active::set)
    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
        Socket(host, port, localHost, localPort).also(active::set)
}

/** A generic MessagingException / validUnsent list cannot prove the server did not accept DATA. */
internal fun classifySmtpFailure(exception: Exception): ProviderResult {
    var current: Exception? = exception
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Exception, Boolean>())
    while (current != null && seen.add(current)) {
        val code = when (current) {
            is SMTPSendFailedException -> current.returnCode
            is SMTPAddressFailedException -> current.returnCode
            else -> null
        }
        if (code != null) {
            if (code in 400..499) return ProviderResult.Retry("SMTP_REJECTED_TEMPORARY")
            if (code in 500..599) return ProviderResult.Failed("SMTP_REJECTED_PERMANENT")
        }
        current = (current as? MessagingException)?.nextException
    }
    return ProviderResult.Unknown
}

private class StableMessage(session: Session, private val id: UUID, private val domain: String) : MimeMessage(session) {
    override fun updateMessageID() { setHeader("Message-ID", "<$id@$domain>") }
}
