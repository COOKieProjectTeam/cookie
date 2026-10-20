package com.cookie.notification.scheduler

import com.cookie.notification.application.ports.DeliveryQueue
import com.cookie.notification.domain.ReminderKind
import com.cookie.notification.domain.ReminderPlanner
import com.cookie.notification.persistence.JdbcNotificationRepository
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
@Profile("!notification-replay")
class ReminderScheduler(
    private val jdbc:JdbcTemplate,
    private val repository:JdbcNotificationRepository,
    private val transactions:TransactionTemplate,
    private val queue:DeliveryQueue,
    private val index:RedisDeadlineIndex,
    mapper:ObjectMapper,
    registry:MeterRegistry,
    @param:Value("\${cookie.notification.scheduler-enabled:true}") private val enabled:Boolean,
) {
    private val loader=JdbcReminderStateLoader(jdbc,repository,mapper)
    private val failures=registry.counter("notification.scheduler.failures")
    private val occurrences=registry.counter("notification.scheduler.occurrences")
    private var lastSweep=Instant.EPOCH
    @Scheduled(fixedDelayString="\${cookie.notification.poll-millis:1000}")
    fun tick() {
        if(!enabled)return
        try {
            val now=repository.now()
            val hints=index.due(now,100)
            val fallback=if(Duration.between(lastSweep,now)>=Duration.ofSeconds(30)) {
                lastSweep=now
                jdbc.query("SELECT user_id,generation FROM reminder_deadlines WHERE due_at<=? ORDER BY due_at LIMIT 100",{rs,_->DeadlineHint(rs.getObject("user_id",UUID::class.java),rs.getLong("generation"))},Timestamp.from(now))
            } else emptyList()
            (hints+fallback).distinctBy { it.userId }.take(100).forEach { hint -> try { process(hint.userId,hint.generation) } catch (_:Exception) { failures.increment() } }
        } catch (_:Exception) { failures.increment() }
    }
    fun process(userId:UUID,expectedGeneration:Long?=null) {
        val next=transactions.execute {
            if(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))",Boolean::class.java,"notification:user:$userId")!=true) return@execute null
            val state=loader.load(userId)
            if(state?.settings?.timezone==null || repository.isDeleted(userId)) {
                jdbc.update("DELETE FROM reminder_deadlines WHERE user_id=?",userId);return@execute null
            }
            val now=repository.now()
            val current=jdbc.query("SELECT due_at,generation FROM reminder_deadlines WHERE user_id=?",{rs,_->rs.getTimestamp(1).toInstant() to rs.getLong(2)},userId).singleOrNull()
            if(current==null || current.first>now || (expectedGeneration!=null && expectedGeneration!=current.second)) return@execute current
            val plan=ReminderPlanner.plan(state,now)
            val devices=repository.devices(userId)
            var nextWakeup=plan.nextWakeup?:now.plusSeconds(60)
            if(devices.isEmpty() && plan.due.isNotEmpty()) nextWakeup=minOf(nextWakeup,now.plusSeconds(60))
            if(devices.isNotEmpty()) plan.due.forEach { candidate ->
                val id=jdbc.query("""INSERT INTO reminder_occurrences(id,user_id,kind,cycle_key,scheduled_at)
                    VALUES (?,?,?,?,?) ON CONFLICT(user_id,kind,cycle_key) DO UPDATE SET scheduled_at=EXCLUDED.scheduled_at
                    WHERE reminder_occurrences.kind IN ('FOOD','WEIGH','CALORIES_EXCEEDED','CALORIES_UNDER_TARGET')
                    AND reminder_occurrences.last_submitted_at IS NULL
                    AND NOT EXISTS (SELECT 1 FROM delivery_jobs j WHERE j.occurrence_id=reminder_occurrences.id
                        AND NOT (j.status='CANCELLED' OR (reminder_occurrences.kind='FOOD' AND j.status='EXPIRED'))) RETURNING id""",
                    {rs,_->rs.getObject(1,UUID::class.java)},UUID.randomUUID(),userId,candidate.kind.name,candidate.cycle,
                    Timestamp.from(candidate.scheduledAt)).singleOrNull()
                if(id!=null) {
                    devices.forEach { device ->
                        // Keep occurrence/provider dedup identity when corrected diary data makes it due again.
                        if(candidate.kind!=ReminderKind.WATER) jdbc.update("""UPDATE delivery_jobs
                            SET status='PENDING',expires_at=?,next_attempt_at=clock_timestamp(),finished_at=NULL,
                                last_code=NULL,locale=? WHERE occurrence_id=? AND device_id=?
                                AND (status='CANCELLED' OR (?='FOOD' AND status='EXPIRED'))""",
                            Timestamp.from(candidate.expiresAt),state.settings.locale,id,device.id,candidate.kind.name)
                        queue.enqueuePush(id,userId,device.id,candidate.kind.name,state.settings.locale,candidate.expiresAt)
                    }
                    if(candidate.kind==ReminderKind.WATER) jdbc.update("UPDATE settings SET last_water_reminder_at=? WHERE user_id=?",Timestamp.from(now),userId)
                    occurrences.increment()
                }
            }
            jdbc.update("UPDATE reminder_deadlines SET due_at=?,generation=generation+1 WHERE user_id=?",Timestamp.from(nextWakeup),userId)
            nextWakeup to current.second+1
        }
        if(next!=null)index.put(userId,next.first,next.second)
    }
}
