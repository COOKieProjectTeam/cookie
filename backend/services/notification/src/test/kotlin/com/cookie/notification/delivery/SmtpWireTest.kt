package com.cookie.notification.delivery

import com.cookie.notification.config.SmtpProperties
import com.cookie.notification.security.VerificationEmail
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SmtpWireTest {
    @Test fun `accepted DATA uses stable Message-ID and verification link`() {
        FakeSmtp(dropFinalResponse = false).use { server ->
            val sender = sender(server.port)
            try {
                val job = job()
                val prepared = sender.prepare(job, VerificationEmail("person@example.test", "verification-secret", "en", job.expiresAt))
                val result = prepared.use { it.submit() }
                assertThat(result).isEqualTo(ProviderResult.Accepted("<${job.id}@messages.example.test>"))
                val mime = server.data.get(5, TimeUnit.SECONDS)
                assertThat(mime).contains("Message-ID: <${job.id}@messages.example.test>")
                assertThat(mime).contains("#token=verification-secret")
            } finally { sender.close() }
        }
    }

    @Test fun `server consuming DATA then losing its response is unknown not retryable`() {
        FakeSmtp(dropFinalResponse = true).use { server ->
            val sender = sender(server.port)
            try {
                val job = job()
                val result = sender.prepare(job, VerificationEmail("person@example.test", "verification-secret", "en", job.expiresAt))
                    .use { it.submit() }
                assertThat(result).isEqualTo(ProviderResult.Unknown)
                assertThat(server.data.get(5, TimeUnit.SECONDS)).contains("verification-secret")
            } finally { sender.close() }
        }
    }

    private fun sender(port: Int) = SmtpVerificationSender(SmtpProperties(host = "127.0.0.1", port = port,
        from = "noreply@example.test", starttlsRequired = false, messageIdDomain = "messages.example.test"),
        NotificationTemplates(URI.create("https://links.example.test/verify")))
    private fun job() = DeliveryJob(UUID.randomUUID(), "EMAIL", null, null, UUID.randomUUID(), "EMAIL_VERIFICATION", "en",
        null, Instant.now().plusSeconds(600), null, 1, UUID.randomUUID())
}

private class FakeSmtp(private val dropFinalResponse: Boolean) : AutoCloseable {
    private val socket = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int = socket.localPort
    val data = CompletableFuture<String>()
    private val worker = Thread {
        try {
            socket.accept().use { client ->
                client.soTimeout = 5000
                val input = client.getInputStream().bufferedReader()
                val output = client.getOutputStream().bufferedWriter()
                fun respond(line: String) { output.write("$line\r\n"); output.flush() }
                respond("220 localhost test SMTP")
                while (true) {
                    val command = input.readLine() ?: break
                    when {
                        command.startsWith("EHLO") || command.startsWith("HELO") -> respond("250 localhost")
                        command.startsWith("MAIL FROM") || command.startsWith("RCPT TO") -> respond("250 OK")
                        command == "DATA" -> {
                            respond("354 Send message")
                            val body = StringBuilder()
                            while (true) {
                                val line = input.readLine() ?: error("Incomplete SMTP DATA")
                                if (line == ".") break
                                body.appendLine(line)
                            }
                            data.complete(body.toString())
                            if (dropFinalResponse) break
                            respond("250 Message accepted")
                        }
                        command == "QUIT" -> { respond("221 Bye"); break }
                        else -> respond("250 OK")
                    }
                }
            }
        } catch (exception: Exception) { data.completeExceptionally(exception) }
    }.apply { isDaemon = true; start() }
    override fun close() { socket.close(); worker.join(1000) }
}
