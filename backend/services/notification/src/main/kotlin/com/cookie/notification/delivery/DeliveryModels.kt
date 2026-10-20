package com.cookie.notification.delivery

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Contains only ciphertext for email. Never log delivery data or provider targets. */
class DeliveryJob(
    val id: UUID, val channel: String, val userId: UUID?, val deviceId: UUID?, val registrationAttemptId: UUID?,
    val kind: String, val locale: String, val encryptedPayload: String?, val expiresAt: Instant, val occurrenceId: UUID?,
    val attemptCount: Int, val leaseToken: UUID,
) { override fun toString(): String = "DeliveryJob(id=$id,channel=$channel,kind=$kind)" }

class PushTarget(val deviceId: UUID, val platform: String, val token: String, val revision: Instant,
                 val locale: String = "ru", val quietUntil: Instant? = null,
                 val nextWaterSubmissionAt: Instant? = null) {
    override fun toString(): String = "PushTarget(deviceId=$deviceId,platform=$platform,token=[redacted])"
}

sealed interface ProviderResult {
    data class Accepted(val messageId: String? = null) : ProviderResult
    data class Retry(val code: String, val minimumDelay: Duration = Duration.ofSeconds(10)) : ProviderResult
    data class Failed(val code: String, val invalidDevice: Boolean = false) : ProviderResult
    data class Unavailable(val code: String) : ProviderResult
    data object Unknown : ProviderResult
}

fun interface PushSender {
    fun send(job: DeliveryJob, target: PushTarget, beforeSubmission: () -> Unit): ProviderResult
    fun send(job: DeliveryJob, target: PushTarget): ProviderResult = send(job, target) {}
}

interface DeliveryRepository {
    fun claim(limit: Int): List<DeliveryJob>
    fun beginSmtpSubmission(job: DeliveryJob): Boolean
    fun heartbeat(job: DeliveryJob): Boolean
    fun currentPushTarget(job: DeliveryJob): PushTarget?
    /** Serializes target validation and submission with account/token mutations. */
    fun withPushSubmission(job: DeliveryJob, submit: (PushTarget) -> Unit): Boolean
    fun markPushSubmission(job: DeliveryJob)
    fun invalidateDevice(target: PushTarget)
    fun finish(job: DeliveryJob, status: String, code: String, providerId: String? = null)
    fun retry(job: DeliveryJob, code: String, delay: Duration)
    fun maintain()
}
