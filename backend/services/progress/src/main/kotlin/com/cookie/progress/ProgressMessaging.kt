package com.cookie.progress

import com.cookie.platform.messaging.DevelopmentJetStreamTopology
import com.cookie.platform.messaging.JetStreamPullConsumer
import com.cookie.platform.messaging.NatsConnectionLifecycle
import com.cookie.platform.messaging.NatsConnectionSettings
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.nats.client.JetStreamOptions
import io.nats.client.impl.Headers
import jakarta.annotation.PreDestroy
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

@Component
internal class ProgressMessaging(
    properties: ProgressProperties, environment: Environment, private val store: ProgressStore,
    inbox: ProgressInbox, private val calendar: ProgressCalendar, private val metrics: MeterRegistry, private val gate: ProgressEvidenceGate,
) {
    private val local = environment.acceptsProfiles(Profiles.of("dev", "test"))
    private val connection = NatsConnectionLifecycle(
        options = {
            NatsConnectionSettings(properties.natsUrl, local, properties.natsCredentialsPath,
                properties.natsTruststorePath, properties.natsTruststorePassword).options("cookie-progress", "_INBOX.cookie.progress")
        },
        initialize = { broker ->
            if (local && properties.provisionLocalBroker) {
                DevelopmentJetStreamTopology.ensure(broker, STREAM, DURABLE, ProgressInbox.subjects.sorted())
            }
        },
    )
    private val consumer = JetStreamPullConsumer(
        connection = connection::connection,
        stream = STREAM,
        durable = DURABLE,
        handle = { inbox.receive(it.subject, it.data) },
        invalidReason = { (it as? InvalidProgressEvent)?.reason },
        quarantine = { message, reason ->
            val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(message.data))
            store.jdbc.update("INSERT INTO message_quarantine(stream_sequence,reason,event_hash) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                message.metaData().streamSequence(), reason, hash)
        },
        processed = { metrics.counter("cookie.progress.inbox.processed").increment() },
        quarantined = { metrics.counter("cookie.progress.inbox.quarantined").increment() },
        failed = { metrics.counter("cookie.progress.inbox.failures").increment() },
    )
    private data class Claim(val id: UUID, val token: UUID, val subject: String, val body: String, val attempts: Int)

    init {
        if (!local) NatsConnectionSettings(properties.natsUrl, false, properties.natsCredentialsPath,
            properties.natsTruststorePath, properties.natsTruststorePassword).validate()
        gauge("closing.count", "SELECT count(*)::double precision FROM calendar_days WHERE state='CLOSING'")
        gauge("closing.oldest.seconds", """SELECT COALESCE(extract(epoch FROM clock_timestamp()-min(closes_at)),0)::double precision
            FROM calendar_days WHERE state='CLOSING'""")
        gauge("due.count", "SELECT count(*)::double precision FROM progress_accounts WHERE next_due_at<=clock_timestamp()")
        gauge("outbox.pending", "SELECT count(*)::double precision FROM outbox_events WHERE published_at IS NULL")
        gauge("outbox.oldest.seconds", """SELECT COALESCE(extract(epoch FROM clock_timestamp()-min(created_at)),0)::double precision
            FROM outbox_events WHERE published_at IS NULL""")
    }

    @Scheduled(fixedDelay = 100)
    fun consume() = consumer.poll()

    @Scheduled(fixedDelay = 2_000)
    fun inspectRetention() {
        try {
            val management = connection.connection().jetStreamManagement(JetStreamOptions.builder().requestTimeout(Duration.ofSeconds(2)).build())
            val stream = management.getStreamInfo(STREAM)
            val floor = management.getConsumerInfo(STREAM, DURABLE).ackFloor.streamSequence
            gate.inspect(stream.createTime.toInstant(), stream.streamState.firstSequence, floor)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            metrics.counter("cookie.progress.intake.monitor.failures").increment()
        }
    }

    @Scheduled(fixedDelay = 500)
    fun calendar() {
        if (!gate.ready()) return
        val sample = io.micrometer.core.instrument.Timer.start(metrics)
        try { calendar.work() } finally { sample.stop(metrics.timer("cookie.progress.calendar.batch")) }
    }

    @Scheduled(fixedDelay = 200)
    fun publish() {
        if (!gate.ready()) return
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        repeat(20) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted) return
            val claim = claim() ?: return
            try {
                connection.connection().jetStream(JetStreamOptions.builder().requestTimeout(Duration.ofSeconds(3)).build())
                    .publish(claim.subject, Headers().add("Nats-Msg-Id", claim.id.toString()), claim.body.toByteArray())
                store.jdbc.update("""UPDATE outbox_events SET published_at=clock_timestamp(),claim_id=NULL,claimed_until=NULL
                     WHERE event_id=? AND claim_id=? AND published_at IS NULL""", claim.id, claim.token)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (_: Exception) {
                metrics.counter("cookie.progress.outbox.failures").increment()
                runCatching { release(claim) }
            }
        }
    }

    private fun claim(): Claim? {
        val token = UUID.randomUUID()
        return store.jdbc.query(
            """WITH pending AS (
                 SELECT event_id FROM outbox_events WHERE published_at IS NULL AND available_at<=clock_timestamp()
                   AND (claimed_until IS NULL OR claimed_until<=clock_timestamp())
                 ORDER BY available_at,created_at,event_id FOR UPDATE SKIP LOCKED LIMIT 1
               ) UPDATE outbox_events o SET claim_id=?,claimed_until=clock_timestamp()+interval '30 seconds',attempts=attempts+1
                 FROM pending p WHERE o.event_id=p.event_id RETURNING o.*""",
            { row, _ -> Claim(row.getObject("event_id", UUID::class.java), token, row.getString("subject"),
                row.getString("envelope"), row.getInt("attempts")) }, token,
        ).singleOrNull()
    }

    private fun release(claim: Claim) {
        val ceiling = (1000L shl (claim.attempts - 1).coerceIn(0, 9)).coerceAtMost(300000)
        val retry = ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1)
        store.jdbc.update("""UPDATE outbox_events SET available_at=clock_timestamp()+(? * interval '1 millisecond'),
            claim_id=NULL,claimed_until=NULL WHERE event_id=? AND claim_id=? AND published_at IS NULL""", retry, claim.id, claim.token)
    }

    private fun gauge(name: String, query: String) {
        Gauge.builder("cookie.progress.$name", store) { progress ->
            runCatching { progress.jdbc.queryForObject(query, Double::class.java) ?: Double.NaN }.getOrDefault(Double.NaN)
        }.register(metrics)
    }

    @PreDestroy fun shutdown() = connection.close()

    companion object {
        const val STREAM = "COOKIE_EVENTS"
        const val DURABLE = "progress-v1"
    }
}
