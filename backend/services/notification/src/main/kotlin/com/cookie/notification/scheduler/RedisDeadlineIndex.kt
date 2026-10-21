package com.cookie.notification.scheduler

import com.cookie.notification.application.ports.ReminderDeadlineIndex
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

data class DeadlineHint(val userId:UUID,val generation:Long)

/** Bounded keyset recovery traverses all schedules, rather than repeatedly indexing the earliest page. */
@Component
@Profile("!notification-replay")
class RedisDeadlineIndex(private val redis:StringRedisTemplate,private val jdbc:JdbcTemplate,registry:MeterRegistry) : ReminderDeadlineIndex {
    private val failures=registry.counter("notification.redis.failures")
    private val rebuildFailures=registry.counter("notification.redis.rebuild.failures")
    @Volatile private var unavailable=false
    private var rebuildCursor:UUID?=null
    override fun put(userId:UUID,at:Instant,generation:Long) { write(userId,at,generation) }
    private fun write(userId:UUID,at:Instant,generation:Long):Boolean = try {
        redis.opsForZSet().add(KEY,"$userId:$generation",at.toEpochMilli().toDouble());true
    } catch (_:Exception) { unavailable=true;failures.increment();false }
    fun due(now:Instant,limit:Int):List<DeadlineHint> = try {
        val members=redis.opsForZSet().rangeByScore(KEY,0.0,now.toEpochMilli().toDouble(),0,limit.toLong()).orEmpty()
        if(members.isNotEmpty()) redis.opsForZSet().remove(KEY,*members.toTypedArray())
        if(unavailable) {
            unavailable=false
            synchronized(this) { rebuildCursor=null }
            rebuild()
        }
        members.mapNotNull { member->runCatching { val parts=member.split(':');DeadlineHint(UUID.fromString(parts[0]),parts[1].toLong()) }.getOrNull() }
    } catch (_:Exception) { unavailable=true;failures.increment();emptyList() }

    @Synchronized
    @Scheduled(fixedDelayString="\${cookie.notification.redis-rebuild-millis:60000}")
    fun rebuild() {
        try {
            repeat(4) {
                val page=if(rebuildCursor==null) jdbc.query("SELECT user_id,due_at,generation FROM reminder_deadlines ORDER BY user_id LIMIT 500",::mapDeadline)
                    else jdbc.query("SELECT user_id,due_at,generation FROM reminder_deadlines WHERE user_id>? ORDER BY user_id LIMIT 500",::mapDeadline,rebuildCursor)
                if(page.isEmpty()) { rebuildCursor=null;return }
                for((id,at,generation) in page) {
                    if(!write(id,at,generation)) return
                    rebuildCursor=id
                }
                if(page.size<500) { rebuildCursor=null;return }
            }
        } catch (_:Exception) { rebuildFailures.increment() }
    }
    private fun mapDeadline(rs:java.sql.ResultSet,@Suppress("UNUSED_PARAMETER") row:Int)=Triple(rs.getObject("user_id",UUID::class.java),rs.getTimestamp("due_at").toInstant(),rs.getLong("generation"))
    companion object { const val KEY="cookie:notification:reminder_deadlines" }
}
