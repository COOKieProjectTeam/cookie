package com.cookie.notification.delivery

import com.cookie.notification.security.VerificationEmail
import jakarta.mail.MessagingException
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SmtpDeliveryExecutorTest {
    private val now = Instant.parse("2026-09-17T12:00:00Z")
    private val job = DeliveryJob(UUID.randomUUID(), "EMAIL", null, null, UUID.randomUUID(), "EMAIL_VERIFICATION", "ru",
        "ciphertext", now.plusSeconds(300), null, 1, UUID.randomUUID())
    private val email = VerificationEmail("person@example.test", "secret", "en", now.plusSeconds(300))

    @Test fun `uncertain marker commit never calls SMTP and never schedules retry`() {
        val repository = TestDeliveryRepository().apply { markerFailure = true }
        var submissions = 0
        val executor = SmtpDeliveryExecutor(repository, VerificationSender { _, _ -> prepared { submissions++; ProviderResult.Accepted() } })
        assertThat(executor.execute(job, email)).isNull()
        assertThat(submissions).isZero()
        assertThat(repository.retries).isZero()
    }

    @Test fun `failed fencing prevents old worker from sending`() {
        val repository = TestDeliveryRepository().apply { markerResult = false }
        var submissions = 0
        val executor = SmtpDeliveryExecutor(repository, VerificationSender { _, _ -> prepared { submissions++; ProviderResult.Accepted() } })
        assertThat(executor.execute(job, email)).isNull()
        assertThat(submissions).isZero()
    }

    @Test fun `lost response after marker is unknown and close failure preserves accepted result`() {
        val repository = TestDeliveryRepository()
        val failed = SmtpDeliveryExecutor(repository, VerificationSender { _, _ -> prepared { throw MessagingException("socket closed") } })
        assertThat(failed.execute(job, email)).isEqualTo(ProviderResult.Unknown)
        val accepted = SmtpDeliveryExecutor(repository, VerificationSender { _, _ -> object : PreparedEmail {
            override fun submit() = ProviderResult.Accepted("message-id")
            override fun close() { error("QUIT failed") }
        } })
        assertThat(accepted.execute(job, email)).isEqualTo(ProviderResult.Accepted("message-id"))
    }

    @Test fun `only explicit negative SMTP replies are retryable after submission`() {
        assertThat(classifySmtpFailure(MessagingException("timeout", java.io.IOException())))
            .isEqualTo(ProviderResult.Unknown)
        assertThat(classifySmtpFailure(SMTPSendFailedException(".", 451, "safe", null, null, null, null)))
            .isInstanceOf(ProviderResult.Retry::class.java)
        assertThat(classifySmtpFailure(SMTPSendFailedException(".", 550, "safe", null, null, null, null)))
            .isInstanceOf(ProviderResult.Failed::class.java)
    }

    private fun prepared(block: () -> ProviderResult) = object : PreparedEmail {
        override fun submit(): ProviderResult = block()
        override fun close() = Unit
    }
}

internal class TestDeliveryRepository : DeliveryRepository {
    var markerFailure = false
    var markerResult = true
    var retries = 0
    override fun claim(limit: Int) = emptyList<DeliveryJob>()
    override fun beginSmtpSubmission(job: DeliveryJob): Boolean {
        if (markerFailure) error("DB commit acknowledgement lost")
        return markerResult
    }
    override fun heartbeat(job: DeliveryJob) = true
    override fun currentPushTarget(job: DeliveryJob): PushTarget? = null
    override fun withPushSubmission(job: DeliveryJob, submit: (PushTarget) -> Unit) = false
    override fun markPushSubmission(job: DeliveryJob) = Unit
    override fun invalidateDevice(target: PushTarget) = Unit
    override fun finish(job: DeliveryJob, status: String, code: String, providerId: String?, providerFailure: Boolean) = Unit
    override fun retry(job: DeliveryJob, code: String, delay: Duration, providerFailure: Boolean) { retries++ }
    override fun maintain() = Unit
}
