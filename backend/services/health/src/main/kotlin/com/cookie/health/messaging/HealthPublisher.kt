package com.cookie.health.messaging

import com.cookie.platform.messaging.EventEnvelope
import com.cookie.platform.messaging.subject
import com.cookie.health.config.HealthProperties
import com.cookie.health.persistence.HealthDatabase
import tools.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Gauge
import io.nats.client.JetStreamOptions
import io.nats.client.impl.Headers
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

@Component
class HealthPublisher(private val broker:HealthBroker,private val store:HealthDatabase,private val properties:HealthProperties,registry:MeterRegistry, private val mapper: ObjectMapper) {
    private val failures=registry.counter("cookie.health.outbox.failures")
    private val published=registry.counter("cookie.health.outbox.published")
    init {
        Gauge.builder("cookie.health.outbox.pending", store) {
            it.jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long::class.java)!!.toDouble()
        }.register(registry)
        Gauge.builder("cookie.health.outbox.oldest.seconds", store) {
            it.jdbc.queryForObject(
                "SELECT COALESCE(EXTRACT(EPOCH FROM now()-min(occurred_at)),0) FROM outbox_events WHERE published_at IS NULL",
                Double::class.java,
            )!!
        }.register(registry)
    }
    private data class Claimed(val id:UUID,val claim:UUID,val body:EventEnvelope,val attempts:Int)
    @Scheduled(fixedDelay=500)
    fun poll() {
        if(!properties.messagingEnabled) return
        val deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos()
        repeat(20) {
            if(System.nanoTime()>deadline) return
            val claim=try { claim() } catch (_:Exception) { failures.increment();return } ?: return
            try {
                val stream=broker.connection().jetStream(JetStreamOptions.builder().requestTimeout(Duration.ofSeconds(10)).build())
                stream.publish(claim.body.subject(),Headers().add("Nats-Msg-Id",claim.id.toString()),mapper.writeValueAsBytes(claim.body))
                store.jdbc.update("""UPDATE outbox_events SET published_at=now(),claim_id=NULL,claimed_until=NULL,last_error=NULL
                    WHERE event_id=? AND claim_id=? AND published_at IS NULL""",claim.id,claim.claim)
                published.increment()
            } catch (_:Exception) {
                failures.increment()
                // Failed bookkeeping leaves a lease that expires; it cannot starve unrelated rows.
                runCatching {
                    val cap=(1000L shl (claim.attempts-1).coerceIn(0,9)).coerceAtMost(300000)
                    val delay=if(cap==1000L) cap else ThreadLocalRandom.current().nextLong(cap/2,cap+1)
                    store.jdbc.update("""UPDATE outbox_events SET available_at=now()+(? * interval '1 millisecond'),
                        claim_id=NULL,claimed_until=NULL,last_error='publish_failed' WHERE event_id=? AND claim_id=? AND published_at IS NULL""",delay,claim.id,claim.claim)
                }
            }
        }
    }
    private fun claim():Claimed? {
        val token=UUID.randomUUID()
        return store.jdbc.query("""WITH pending AS (
            SELECT event_id FROM outbox_events WHERE published_at IS NULL AND available_at<=now()
            AND (claimed_until IS NULL OR claimed_until<=now()) ORDER BY available_at,event_id FOR UPDATE SKIP LOCKED LIMIT 1)
            UPDATE outbox_events o SET claim_id=?,claimed_until=now()+interval '30 seconds',attempt_count=attempt_count+1
            FROM pending p WHERE o.event_id=p.event_id RETURNING o.*""",{ r,_ ->
                val id=r.getObject("event_id",UUID::class.java)
                Claimed(id,token,EventEnvelope(id,r.getString("event_type"),r.getInt("event_version"),r.getTimestamp("occurred_at").toInstant(),"health",r.getString("aggregate_type"),
                    r.getString("aggregate_id"),mapper.readTree(r.getString("payload")),r.getLong("aggregate_version")),r.getInt("attempt_count"))
            },token).singleOrNull()
    }
}
