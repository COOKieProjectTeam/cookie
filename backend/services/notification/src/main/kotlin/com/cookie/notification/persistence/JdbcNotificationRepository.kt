package com.cookie.notification.persistence

import com.cookie.notification.application.ports.NotificationRepository
import com.cookie.notification.application.ports.ReminderDeadlineIndex
import com.cookie.notification.domain.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

@Repository
class JdbcNotificationRepository(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val deadlineIndex: ReminderDeadlineIndex? = null,
) : NotificationRepository {
    override fun settings(userId: UUID): NotificationSettings = transaction(userId) {
        ensureSettings(userId)
        readSettings(userId)!!
    }

    override fun updateSettings(userId: UUID, patch: SettingsPatch): NotificationSettings = transaction(userId) {
        ensureSettings(userId)
        val old = readSettings(userId)!!
        val now = now()
        val updated = old.apply(patch, now)
        jdbc.update("""UPDATE settings SET water_enabled=?,food_enabled=?,food_inactive_minutes=?,weigh_enabled=?,weigh_time=?,
            goal_warning_enabled=?,quiet_start=?,quiet_end=?,timezone=?,locale=?,locale_overridden=(locale_overridden OR ?),scheduling_started_at=?,
            food_started_at=CASE WHEN ? THEN ? ELSE food_started_at END,
            water_started_at=CASE WHEN ? THEN ? ELSE water_started_at END,updated_at=? WHERE user_id=?""",
            updated.waterEnabled,updated.foodEnabled,updated.foodInactiveMinutes,updated.weighEnabled,java.sql.Time.valueOf(updated.weighTime),
            updated.goalWarningEnabled,java.sql.Time.valueOf(updated.quietStart),java.sql.Time.valueOf(updated.quietEnd),updated.timezone,updated.locale,patch.locale!=null,
            updated.schedulingStartedAt?.let(Timestamp::from),
            (!old.foodEnabled && updated.foodEnabled) || (old.timezone == null && updated.timezone != null), Timestamp.from(now),
            (!old.waterEnabled && updated.waterEnabled) || (old.timezone == null && updated.timezone != null), Timestamp.from(now),Timestamp.from(now),userId)
        dirty(userId,now)
        updated
    }

    override fun registerDevice(userId: UUID, command: RegisterDevice): DeviceRegistration = transaction(userId) {
        ensureSettings(userId)
        // Serialize token movement across accounts; a token belongs to one active installation.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Any::class.java, "notification:token:${command.platform}:${command.token}")
        jdbc.update("UPDATE push_devices SET enabled=false,updated_at=clock_timestamp() WHERE platform=? AND push_token=? AND NOT(user_id=? AND device_id=?)", command.platform.name,command.token,userId,command.deviceId)
        val existing = jdbc.query("SELECT * FROM push_devices WHERE user_id=? AND device_id=?", ::mapDevice,userId,command.deviceId).singleOrNull()
        val id = existing?.id ?: UUID.randomUUID()
        jdbc.update("""INSERT INTO push_devices(id,user_id,platform,device_id,push_token,enabled,created_at,updated_at)
            VALUES (?,?,?,?,?,true,clock_timestamp(),clock_timestamp()) ON CONFLICT(user_id,device_id) DO UPDATE
            SET platform=EXCLUDED.platform,push_token=EXCLUDED.push_token,enabled=true,updated_at=clock_timestamp()""",
            id,userId,command.platform.name,command.deviceId,command.token)
        dirty(userId,now())
        DeviceRegistration(PushDevice(id,userId,command.platform,command.deviceId,command.token),existing == null)
    }

    override fun unregisterDevice(userId: UUID, deviceId: String): Unit = transaction(userId) {
        if (jdbc.update("DELETE FROM push_devices WHERE user_id=? AND device_id=?",userId,deviceId) == 0) throw NotificationDeviceNotFound()
    }

    fun lockUser(userId: UUID) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Any::class.java,"notification:user:$userId")
    }
    fun isDeleted(userId: UUID): Boolean = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM account_tombstones WHERE user_id=?)",Boolean::class.java,userId) == true
    fun now(): Instant = jdbc.queryForObject("SELECT clock_timestamp()",Timestamp::class.java)!!.toInstant()
    fun ensureSettings(userId: UUID) { jdbc.update("INSERT INTO settings(user_id) VALUES (?) ON CONFLICT DO NOTHING",userId) }
    fun dirty(userId: UUID, at: Instant) {
        check(TransactionSynchronizationManager.isSynchronizationActive()) { "Schedule change requires a transaction" }
        val committedHint=jdbc.query("INSERT INTO reminder_deadlines(user_id,due_at) VALUES (?,?) ON CONFLICT(user_id) DO UPDATE SET due_at=LEAST(reminder_deadlines.due_at,EXCLUDED.due_at),generation=reminder_deadlines.generation+1 RETURNING due_at,generation",
            {rs,_->rs.getTimestamp(1).toInstant() to rs.getLong(2)},userId,Timestamp.from(at)).single()
        TransactionSynchronizationManager.registerSynchronization(object:TransactionSynchronization {
            override fun afterCommit() {
                // PostgreSQL has committed. A best-effort index failure must never turn success into HTTP 500.
                runCatching { deadlineIndex?.put(userId,committedHint.first,committedHint.second) }
            }
        })
    }
    fun readSettings(userId: UUID): NotificationSettings? = jdbc.query("SELECT * FROM settings WHERE user_id=?",::mapSettings,userId).singleOrNull()
    fun devices(userId: UUID): List<PushDevice> = jdbc.query("SELECT * FROM push_devices WHERE user_id=? AND enabled",::mapDevice,userId)

    private fun <T : Any> transaction(userId: UUID, block: () -> T): T = transactions.execute {
        lockUser(userId)
        if (isDeleted(userId)) throw NotificationAccountDeleted()
        block()
    }

    private fun mapDevice(rs: ResultSet, row: Int) = PushDevice(rs.getObject("id",UUID::class.java),rs.getObject("user_id",UUID::class.java),DevicePlatform.valueOf(rs.getString("platform")),rs.getString("device_id"),rs.getString("push_token"),rs.getBoolean("enabled"))
    private fun mapSettings(rs: ResultSet,row: Int) = NotificationSettings(
        rs.getObject("user_id",UUID::class.java),rs.getBoolean("water_enabled"),rs.getBoolean("food_enabled"),rs.getInt("food_inactive_minutes"),
        rs.getBoolean("weigh_enabled"),rs.getObject("weigh_time",LocalTime::class.java),rs.getBoolean("goal_warning_enabled"),
        rs.getObject("quiet_start",LocalTime::class.java),rs.getObject("quiet_end",LocalTime::class.java),rs.getString("timezone"),rs.getString("locale"),rs.getTimestamp("scheduling_started_at")?.toInstant())
}
