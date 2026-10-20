package com.cookie.nutrition

import io.micrometer.core.instrument.MeterRegistry
import io.nats.client.PullSubscribeOptions
import io.nats.client.JetStreamSubscription
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.AckPolicy
import io.nats.client.impl.Headers
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

@Component
class NutritionOutbox(private val jdbc: JdbcTemplate, private val nats: NatsJetStreamConnection,private val metrics: MeterRegistry) {
    data class Claim(val id: UUID,val token: UUID,val subject: String,val body: String,val attempts: Int)
    fun claim(): List<Claim> = jdbc.query("""
        WITH selected AS (
          SELECT event_id FROM outbox_events WHERE published_at IS NULL AND available_at<=clock_timestamp()
          AND (claimed_until IS NULL OR claimed_until<=clock_timestamp())
          ORDER BY occurred_at,event_id FOR UPDATE SKIP LOCKED LIMIT 20
        ) UPDATE outbox_events o SET claim_id=?,claimed_until=clock_timestamp()+interval '30 seconds',attempts=attempts+1
        FROM selected s WHERE o.event_id=s.event_id RETURNING o.*
    """,{ rs,_ -> Claim(rs.getObject("event_id",UUID::class.java),rs.getObject("claim_id",UUID::class.java),rs.getString("subject"),rs.getString("envelope"),rs.getInt("attempts")) },UUID.randomUUID())
    fun complete(c: Claim): Boolean = jdbc.update("UPDATE outbox_events SET published_at=clock_timestamp(),claim_id=NULL,claimed_until=NULL WHERE event_id=? AND claim_id=? AND published_at IS NULL",c.id,c.token)==1
    fun release(c: Claim) {
        val ceiling=minOf(300000L,1000L shl minOf((c.attempts-1).coerceAtLeast(0),9))
        val delay=if(ceiling==1000L) 1000 else ThreadLocalRandom.current().nextLong(ceiling/2,ceiling+1)
        jdbc.update("UPDATE outbox_events SET claim_id=NULL,claimed_until=NULL,available_at=clock_timestamp()+(? * interval '1 millisecond') WHERE event_id=? AND claim_id=? AND published_at IS NULL",delay,c.id,c.token)
    }
    @Scheduled(fixedDelayString="\${cookie.nutrition.outbox-delay:500}")
    fun publishAvailable() {
        val claims=claim()
        // All publishes share one bounded acknowledgement deadline, below the lease.
        val pending=claims.mapNotNull { c ->
            try { c to nats.jetStream().publishAsync(c.subject,Headers().add("Nats-Msg-Id",c.id.toString()),c.body.toByteArray()) }
            catch (_: Exception) { release(c);metrics.counter("cookie.nutrition.outbox.failure").increment();null }
        }
        val deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos()
        pending.forEach { (c,future) ->
            try {
                future.get((deadline-System.nanoTime()).coerceAtLeast(0),java.util.concurrent.TimeUnit.NANOSECONDS)
                if(complete(c)) metrics.counter("cookie.nutrition.outbox.published").increment()
            } catch (e: InterruptedException) {
                release(c);Thread.currentThread().interrupt()
            } catch (_: Exception) { release(c);metrics.counter("cookie.nutrition.outbox.failure").increment() }
        }
    }
    @Scheduled(fixedDelay=60000)
    fun retain() {
        jdbc.update("DELETE FROM outbox_events WHERE event_id IN (SELECT event_id FROM outbox_events WHERE published_at<clock_timestamp()-interval '7 days' LIMIT 500)")
    }
}

@Component
class AccountDeletion(private val service: NutritionService,private val mapper: ObjectMapper) {
    fun handle(subject: String,bytes: ByteArray) {
        val n=try { mapper.readTree(bytes) } catch (_: Exception) { invalid() }
        val s=service.snapshots
        val eventId=s.uuid(n,"event_id")
        if(subject!="cookie.events.account.deletion.requested.v1" || s.text(n,"event_type")!="account.deletion.requested" || s.text(n,"producer")!="identity" || !n.path("event_version").isIntegralNumber || !n.path("event_version").canConvertToInt() || n.path("event_version").intValue()!=1) invalid()
        s.timestamp(n,"occurred_at");s.text(n,"aggregate_type");s.text(n,"aggregate_id")
        val payload=n.path("payload");val user=s.uuid(payload,"accountId");val requestId=s.uuid(payload,"deletionRequestId")
        val at=s.timestamp(payload,"requestedAt").toInstant()
        service.tx.executeWithoutResult {
            service.lock(user)
            if(service.jdbc.update("INSERT INTO inbox_events(event_id) VALUES (?) ON CONFLICT DO NOTHING",eventId)==0) return@executeWithoutResult
            service.jdbc.update("INSERT INTO account_tombstones(user_id,deletion_request_id,deleted_at) VALUES (?,?,?) ON CONFLICT DO NOTHING",user,requestId,java.sql.Timestamp.from(at))
            listOf("meals","water_entries","saved_dishes","day_versions","outbox_events").forEach { table -> service.jdbc.update("DELETE FROM $table WHERE user_id=?",user) }
        }
    }
}

@Component
class DeletionConsumer(private val nats: NatsJetStreamConnection,private val handler: AccountDeletion,
    private val jdbc: JdbcTemplate,private val metrics: MeterRegistry,environment: Environment) {
    private val local=environment.acceptsProfiles(Profiles.of("dev","test"))
    private var subscription: JetStreamSubscription?=null
    @Scheduled(fixedDelayString="\${cookie.nutrition.consumer-delay:500}")
    @Synchronized fun poll() {
        try {
            val sub=subscription?.takeIf { it.isActive } ?: run {
                val connection=nats.connected()
                if(local) connection.jetStreamManagement().addOrUpdateConsumer(NatsJetStreamConnection.STREAM_NAME,
                    ConsumerConfiguration.builder().durable("nutrition-account-deletion-v1").filterSubject(SUBJECT).ackPolicy(AckPolicy.Explicit).ackWait(Duration.ofSeconds(30)).maxAckPending(20).build())
                connection.jetStream().subscribe(SUBJECT,PullSubscribeOptions.bind(NatsJetStreamConnection.STREAM_NAME,"nutrition-account-deletion-v1")).also { subscription=it }
            }
            sub.fetch(20,Duration.ofMillis(200)).forEach { message ->
                try { handler.handle(message.subject,message.data);message.ackSync(Duration.ofSeconds(2)) }
                catch (e: NutritionFailure) {
                    if(e.status!=400) throw e
                    // Invalid payloads are not retained. Preserve bounded broker metadata before TERM.
                    jdbc.update("INSERT INTO rejected_events(stream_sequence,reason) VALUES (?,?) ON CONFLICT DO NOTHING",message.metaData().streamSequence(),e.code)
                    message.term();metrics.counter("cookie.nutrition.consumer.rejected").increment()
                } catch (_: Exception) { message.nakWithDelay(Duration.ofSeconds(5));metrics.counter("cookie.nutrition.consumer.retry").increment() }
            }
        } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        catch (_: Exception) { metrics.counter("cookie.nutrition.consumer.failure").increment() }
    }
    companion object { const val SUBJECT="cookie.events.account.deletion.requested.v1" }
}
