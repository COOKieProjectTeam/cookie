package com.cookie.platform.messaging

import io.nats.client.Connection
import io.nats.client.JetStreamSubscription
import io.nats.client.Message
import io.nats.client.PullSubscribeOptions
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

/** Equal jitter avoids synchronized redelivery while retaining bounded exponential backoff. */
class ExponentialRetryDelay(private val random: () -> Double = { ThreadLocalRandom.current().nextDouble() }) {
    fun delay(deliveredCount: Long): Duration {
        val ceilingMillis = (1L shl deliveredCount.coerceIn(0, 9).toInt()).coerceAtMost(300) * 1000
        val floorMillis = ceilingMillis / 2
        return Duration.ofMillis(floorMillis + ((ceilingMillis - floorMillis) * random().coerceIn(0.0, 1.0)).toLong())
    }
}

/** ACK follows the service's committed handler/quarantine; failures are isolated per message. */
class JetStreamPullConsumer(
    private val connection: () -> Connection,
    private val stream: String,
    private val durable: String,
    private val handle: (Message) -> Unit,
    private val invalidReason: (Exception) -> String?,
    private val quarantine: (Message, String) -> Unit,
    private val processed: () -> Unit,
    private val quarantined: () -> Unit,
    private val failed: () -> Unit,
    private val retryDelay: ExponentialRetryDelay = ExponentialRetryDelay(),
) {
    private var subscription: JetStreamSubscription? = null
    private var owner: Connection? = null

    fun poll() {
        try {
            val connected = connection()
            val active = subscription?.takeIf { it.isActive && owner === connected } ?: run {
                reset()
                connected.jetStream().subscribe(null, PullSubscribeOptions.bind(stream, durable)).also {
                    subscription = it
                    owner = connected
                }
            }
            active.fetch(20, Duration.ofSeconds(1)).forEach(::consume)
        } catch (_: Exception) {
            runCatching(failed)
            reset()
        }
    }

    fun consume(message: Message) {
        try {
            try {
                handle(message)
            } catch (failure: Exception) {
                val reason = invalidReason(failure) ?: throw failure
                quarantine(message, reason)
                message.ack()
                quarantined()
                return
            }
            message.ack()
            processed()
        } catch (_: Exception) {
            runCatching(failed)
            // Even a NAK or metadata failure cannot abort an independent message in the batch.
            runCatching { message.nakWithDelay(retryDelay.delay(message.metaData().deliveredCount())) }
        }
    }

    private fun reset() {
        runCatching { subscription?.unsubscribe() }
        subscription = null
        owner = null
    }
}
