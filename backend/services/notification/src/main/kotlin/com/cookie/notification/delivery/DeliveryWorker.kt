package com.cookie.notification.delivery

import com.cookie.notification.application.ports.PushEligibility
import com.cookie.notification.config.DeliveryProperties
import com.cookie.notification.security.DecryptionKeyUnavailableException
import com.cookie.notification.security.InvalidVerificationPayloadException
import com.cookie.notification.security.VerificationEmail
import com.cookie.notification.security.VerificationPayloadDecryptor
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** The marker is durably committed before any SMTP submission. Its ambiguous outcome is never retried. */
class SmtpDeliveryExecutor(private val repository: DeliveryRepository, private val sender: VerificationSender) {
    fun execute(job: DeliveryJob, email: VerificationEmail): ProviderResult? {
        val prepared = try { sender.prepare(job, email) }
        catch (exception: SmtpPreparationException) { return exception.result }
        catch (_: Exception) { return ProviderResult.Failed("SMTP_PREPARATION") }
        try {
            val permitted = try { repository.beginSmtpSubmission(job) } catch (_: Exception) {
                // A lost COMMIT response may have persisted SMTP_SUBMITTING. Do not submit or reset the job.
                return null
            }
            if (!permitted) return null
            return try { prepared.submit() } catch (_: Exception) { ProviderResult.Unknown }
        } finally {
            // A failed QUIT/close cannot change a confirmed DATA result.
            runCatching { prepared.close() }
        }
    }
}

@Component
class DeliveryWorker(
    private val repository: DeliveryRepository,
    private val decryptor: VerificationPayloadDecryptor,
    sender: VerificationSender,
    private val fcm: FcmPushSender,
    private val eligibility: PushEligibility,
    private val clock: Clock,
    private val properties: DeliveryProperties,
    private val metrics: DeliveryMetrics,
) {
    private val smtp = SmtpDeliveryExecutor(repository, sender)
    private val workers = Executors.newFixedThreadPool(8)
    private val heartbeat = Executors.newScheduledThreadPool(1)
    private val capacity = Semaphore(8)
    private val unavailableUntil = ConcurrentHashMap<String, Instant>()

    @Scheduled(fixedDelay = 500)
    fun poll() {
        if (!properties.enabled) return
        val available = capacity.availablePermits()
        if (available == 0) return
        for (job in repository.claim(available)) {
            capacity.acquire()
            workers.submit {
                val renewal = heartbeat.scheduleAtFixedRate({ runCatching { repository.heartbeat(job) } }, 15, 15, TimeUnit.SECONDS)
                try { process(job) }
                catch (_: Exception) {
                    // In particular, never turn an unknown SMTP completion/DB failure into a retry here.
                    logger.warn("Delivery worker interrupted deliveryId={} channel={}", job.id, job.channel)
                    metrics.workerFailure(job.channel)
                } finally { renewal.cancel(false); capacity.release() }
            }
        }
    }

    @Scheduled(fixedDelay = 30_000)
    fun maintain() { if (properties.enabled) repository.maintain() }

    internal fun process(job: DeliveryJob) {
        if (job.channel == "EMAIL" && !job.expiresAt.isAfter(clock.instant())) {
            repository.finish(job, "EXPIRED", "EXPIRED")
            return
        }
        if (job.channel == "EMAIL") {
            if (deferUnavailable(job, "SMTP")) return
            val email = try {
                decryptor.decrypt(requireNotNull(job.encryptedPayload), requireNotNull(job.registrationAttemptId), job.expiresAt)
            } catch (_: DecryptionKeyUnavailableException) {
                // A missing kid belongs to this payload, not to the SMTP provider.
                repository.retry(job, "DECRYPTION_KEY_UNAVAILABLE", Duration.ofSeconds(60))
                return
            } catch (_: InvalidVerificationPayloadException) {
                repository.finish(job, "FAILED", "INVALID_VERIFICATION_PAYLOAD")
                return
            }
            smtp.execute(job, email)?.let { applyResult(job, it, "SMTP") }
        } else {
            val handled = repository.withPushSubmission(job) { target ->
                // Settings may have changed after the claim. Read expiry under the same
                // account lock as timezone changes, before provider TTL and retry decisions.
                val job = job.withExpiry(target.deliveryExpiresAt ?: job.expiresAt)
                if (target.nextWaterSubmissionAt != null) {
                    repository.retry(job, "WATER_INTERVAL",
                        Duration.between(clock.instant(), target.nextWaterSubmissionAt).coerceAtLeast(Duration.ofSeconds(1)))
                    return@withPushSubmission
                }
                if (!eligibility.isEligible(requireNotNull(job.userId), job.kind,
                        requireNotNull(job.occurrenceId), clock.instant())) {
                    repository.finish(job, "CANCELLED", "NO_LONGER_ELIGIBLE")
                    return@withPushSubmission
                }
                if (target.quietUntil != null) {
                    repository.retry(job, "QUIET_HOURS", Duration.between(clock.instant(), target.quietUntil).coerceAtLeast(Duration.ofSeconds(1)))
                    return@withPushSubmission
                }
                if (deferUnavailable(job, "FCM") || deferUnavailable(job, "FCM:${target.platform}")) return@withPushSubmission
                val beforeSubmission = { repository.markPushSubmission(job) }
                val result = when (target.platform) {
                    "IOS", "ANDROID" -> fcm.send(job, target, beforeSubmission)
                    else -> ProviderResult.Failed("UNSUPPORTED_PLATFORM")
                }
                if (result is ProviderResult.Failed && result.invalidDevice) repository.invalidateDevice(target)
                // Apple credentials belong to the iOS transport behind FCM; Android can still send.
                val unavailableChannel = if (result is ProviderResult.Unavailable && result.code == "FCM_THIRD_PARTY_AUTH")
                    "FCM:${target.platform}" else "FCM"
                applyResult(job, result, "FCM", unavailableChannel)
            }
            if (!handled) repository.finish(job, "CANCELLED", "NO_LONGER_ELIGIBLE")
        }
    }

    private fun deferUnavailable(job: DeliveryJob, channel: String): Boolean {
        val until = unavailableUntil[channel] ?: return false
        val now = clock.instant()
        if (!until.isAfter(now)) return false
        repository.retry(job, "CHANNEL_PAUSED", Duration.between(now, until))
        return true
    }

    private fun applyResult(job: DeliveryJob, result: ProviderResult, channel: String, unavailableChannel: String = channel) {
        when (result) {
            is ProviderResult.Accepted -> repository.finish(job, "ACCEPTED", "PROVIDER_ACCEPTED", result.messageId)
            is ProviderResult.Failed -> repository.finish(job, "FAILED", result.code)
            ProviderResult.Unknown -> repository.finish(job, "UNKNOWN", "SMTP_AMBIGUOUS")
            is ProviderResult.Unavailable -> {
                unavailableUntil[unavailableChannel] = clock.instant().plusSeconds(60)
                repository.retry(job, result.code, Duration.ofSeconds(60))
            }
            is ProviderResult.Retry -> {
                val failures = job.providerFailureCount + 1
                val delay = retryDelay(failures, result.minimumDelay)
                when {
                    failures >= 10 -> repository.finish(job, "FAILED", "RETRIES_EXHAUSTED", providerFailure = true)
                    !clock.instant().plus(delay).isBefore(job.expiresAt) ->
                        repository.finish(job, "EXPIRED", "RETRY_AFTER_EXPIRY", providerFailure = true)
                    else -> repository.retry(job, result.code, delay, providerFailure = true)
                }
            }
        }
        logger.info("Delivery attempt completed deliveryId={} channel={} outcome={}", job.id, job.channel,
            result.javaClass.simpleName)
        metrics.outcome(channel, result.javaClass.simpleName)
    }

    @PreDestroy
    fun close() {
        workers.shutdownNow()
        heartbeat.shutdownNow()
    }
    private companion object { val logger = LoggerFactory.getLogger(DeliveryWorker::class.java) }
}

internal fun retryDelay(attempt: Int, minimum: Duration): Duration {
    val exponential = (10_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(300_000)
    val jitter = Random.nextLong(exponential / 2, exponential + 1)
    return Duration.ofMillis(maxOf(10_000, jitter, minimum.toMillis()))
}
