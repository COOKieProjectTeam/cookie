package com.cookie.notification.delivery

import com.cookie.notification.application.ports.DeliveryQueue
import com.cookie.notification.domain.ReminderPolicy
import com.cookie.notification.persistence.JdbcNotificationRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

@Repository
class JdbcDeliveryRepository(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val notificationRepository: JdbcNotificationRepository? = null,
) :
    DeliveryQueue, DeliveryRepository {
    private val transactions = TransactionTemplate(transactionManager).apply { timeout = 5 }
    private val submissionTransaction = TransactionTemplate(transactionManager).apply {
        timeout = 5
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    private val pushSubmissionTransaction = TransactionTemplate(transactionManager).apply {
        timeout = 45
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    override fun enqueueEmail(eventId: UUID, registrationAttemptId: UUID, jwe: String,
                              expiresAt: Instant, occurredAt: Instant) {
        jdbc.update("""INSERT INTO delivery_jobs(id,dedup_key,channel,registration_attempt_id,kind,encrypted_payload,expires_at,created_at)
            VALUES (?,?,'EMAIL',?,'EMAIL_VERIFICATION',?,?,?) ON CONFLICT(dedup_key) DO NOTHING""",
            eventId, "email:$eventId", registrationAttemptId, jwe, Timestamp.from(expiresAt), Timestamp.from(occurredAt))
    }

    override fun enqueuePush(occurrenceId: UUID, userId: UUID, deviceId: UUID, kind: String, locale: String, expiresAt: Instant) {
        jdbc.update("""INSERT INTO delivery_jobs(id,dedup_key,channel,user_id,device_id,kind,locale,expires_at,occurrence_id)
            VALUES (?,?,'PUSH',?,?,?,?,?,?) ON CONFLICT(dedup_key) DO NOTHING""",
            UUID.randomUUID(), "push:$occurrenceId:$deviceId", userId, deviceId, kind, locale, Timestamp.from(expiresAt), occurrenceId)
    }

    override fun cancelUser(userId: UUID) { jdbc.update("DELETE FROM delivery_jobs WHERE user_id=?", userId) }
    override fun cancelPendingPush(userId: UUID) {
        jdbc.update("""UPDATE delivery_jobs SET status='CANCELLED',finished_at=clock_timestamp(),
            lease_token=NULL,lease_until=NULL,encrypted_payload=NULL,last_code='USER_CANCELLED'
            WHERE user_id=? AND status IN ('PENDING','CLAIMED')""", userId)
    }

    override fun claim(limit: Int): List<DeliveryJob> = transactions.execute {
        jdbc.query("""WITH candidate AS (
            SELECT id FROM delivery_jobs WHERE status='PENDING' AND next_attempt_at<=clock_timestamp()
            AND expires_at>clock_timestamp() ORDER BY next_attempt_at,created_at LIMIT ? FOR UPDATE SKIP LOCKED
          ) UPDATE delivery_jobs d SET status='CLAIMED',lease_token=gen_random_uuid(),
            lease_until=clock_timestamp()+interval '120 seconds',attempt_count=attempt_count+1
          FROM candidate c WHERE d.id=c.id RETURNING d.*""", { row, _ -> map(row) }, limit).also { jobs ->
            jobs.forEach { jdbc.update("INSERT INTO delivery_attempts(id,delivery_id) VALUES (?,?)", it.leaseToken, it.id) }
        }
    }

    override fun beginSmtpSubmission(job: DeliveryJob): Boolean = submissionTransaction.execute {
        jdbc.update("""UPDATE delivery_jobs
        SET status='SMTP_SUBMITTING' WHERE id=? AND lease_token=? AND status='CLAIMED'
        AND lease_until>clock_timestamp() AND expires_at>clock_timestamp() AND channel='EMAIL'""",
        job.id, job.leaseToken) == 1
    }

    override fun heartbeat(job: DeliveryJob): Boolean = jdbc.update("""UPDATE delivery_jobs
        SET lease_until=clock_timestamp()+interval '120 seconds' WHERE id=? AND lease_token=?
        AND status IN ('CLAIMED','SMTP_SUBMITTING') AND lease_until>clock_timestamp()""", job.id, job.leaseToken) == 1

    /**
     * A push target may be transferred or deleted after an ordinary read. Hold the same user -> token
     * advisory locks as mutations through validation, bounded provider I/O and result persistence.
     * REQUIRED eligibility/result transactions join this dedicated transaction, never an inbox
     * transaction. At most eight workers hold connections; the default pool leaves four for API,
     * consumer and heartbeat work. A rollback retains existing at-least-once push retry semantics.
     */
    override fun withPushSubmission(job: DeliveryJob, submit: (PushTarget) -> Unit): Boolean =
        pushSubmissionTransaction.execute {
            require(job.channel == "PUSH")
            jdbc.execute("SET LOCAL lock_timeout = '2s'")
            jdbc.execute("SET LOCAL statement_timeout = '5s'")
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Any::class.java,
                "notification:user:${requireNotNull(job.userId)}")
            val initial = currentPushTarget(job) ?: return@execute false
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Any::class.java,
                "notification:token:${initial.platform}:${initial.token}")
            // A transfer can finish while this transaction waits for the token lock.
            val current = currentPushTarget(job) ?: return@execute false
            check(current.platform == initial.platform && current.token == initial.token)
            submit(current)
            true
        } == true

    override fun markPushSubmission(job: DeliveryJob) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Push submission requires its ownership fence" }
        val submittedAt = jdbc.query("""UPDATE reminder_occurrences o SET last_submitted_at=clock_timestamp()
            WHERE o.id=? AND o.user_id=? AND EXISTS(
                SELECT 1 FROM delivery_jobs j WHERE j.id=? AND j.lease_token=? AND j.status='CLAIMED'
                AND j.lease_until>clock_timestamp() AND j.expires_at>clock_timestamp())
            RETURNING last_submitted_at""", { row, _ -> row.getTimestamp(1).toInstant() },
            job.occurrenceId, job.userId, job.id, job.leaseToken).singleOrNull()
        check(submittedAt != null) { "Push delivery is no longer leased" }
        if (job.kind == "WATER") {
            jdbc.update("""UPDATE settings SET last_water_submission_at=?,last_water_submission_occurrence_id=?
                WHERE user_id=?""", Timestamp.from(submittedAt), job.occurrenceId, job.userId)
        }
        dirtyReminder(requireNotNull(job.userId), submittedAt)
    }

    override fun currentPushTarget(job: DeliveryJob): PushTarget? = jdbc.query("""SELECT d.id,d.platform,d.push_token,d.updated_at,
        s.water_enabled,s.food_enabled,s.weigh_enabled,s.goal_warning_enabled,s.quiet_start,s.quiet_end,s.timezone,s.locale,
        s.last_water_submission_at,s.last_water_submission_occurrence_id,
        clock_timestamp() AS current_time FROM push_devices d JOIN settings s ON s.user_id=d.user_id
        JOIN delivery_jobs j ON j.device_id=d.id WHERE j.id=? AND j.lease_token=? AND j.status='CLAIMED'
        AND d.user_id=j.user_id
        AND j.lease_until>clock_timestamp() AND j.expires_at>clock_timestamp() AND d.enabled
        AND NOT EXISTS(SELECT 1 FROM account_tombstones t WHERE t.user_id=d.user_id)""", { row, _ ->
        val enabled = when (job.kind) {
            "FOOD" -> row.getBoolean("food_enabled")
            "WATER" -> row.getBoolean("water_enabled")
            "WEIGH" -> row.getBoolean("weigh_enabled")
            "CALORIES_EXCEEDED", "CALORIES_UNDER_TARGET" -> row.getBoolean("goal_warning_enabled")
            else -> false
        }
        val zone = row.getString("timezone")?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        val quietStart = row.getObject("quiet_start", LocalTime::class.java)
        val quietEnd = row.getObject("quiet_end", LocalTime::class.java)
        val localNow = zone?.let { row.getTimestamp("current_time").toInstant().atZone(it) }
        val nextWaterSubmissionAt = if (job.kind == "WATER" &&
            row.getObject("last_water_submission_occurrence_id", UUID::class.java) != job.occurrenceId
        ) row.getTimestamp("last_water_submission_at")?.toInstant()?.plus(Duration.ofHours(2))
            ?.takeIf { it.isAfter(row.getTimestamp("current_time").toInstant()) } else null
        val quietUntil = if (localNow != null && inQuietHours(localNow.toLocalTime(), quietStart, quietEnd)) {
            val date = if (quietStart > quietEnd && localNow.toLocalTime() >= quietStart)
                localNow.toLocalDate().plusDays(1) else localNow.toLocalDate()
            ReminderPolicy.localTimeAfter(date, requireNotNull(quietEnd), zone, localNow.toInstant())
        } else null
        if (!enabled || zone == null) null
        else PushTarget(row.getObject("id", UUID::class.java), row.getString("platform"), row.getString("push_token"),
            row.getTimestamp("updated_at").toInstant(), row.getString("locale"), quietUntil, nextWaterSubmissionAt)
    }, job.id, job.leaseToken).firstOrNull()

    override fun invalidateDevice(target: PushTarget) {
        jdbc.update("UPDATE push_devices SET enabled=false,updated_at=clock_timestamp() WHERE id=? AND updated_at=? AND push_token=?",
            target.deviceId, Timestamp.from(target.revision), target.token)
    }

    override fun finish(job: DeliveryJob, status: String, code: String, providerId: String?) {
        require(status in setOf("ACCEPTED","FAILED","UNKNOWN","CANCELLED","EXPIRED"))
        require(code.matches(Regex("[A-Z0-9_]{1,64}")))
        transactions.executeWithoutResult {
            val changed = jdbc.update("""UPDATE delivery_jobs SET status=?,last_code=?,provider_message_id=?,
                finished_at=clock_timestamp(),encrypted_payload=NULL,lease_token=NULL,lease_until=NULL
                WHERE id=? AND lease_token=? AND status IN ('CLAIMED','SMTP_SUBMITTING') AND lease_until>clock_timestamp()""",
                status, code, providerId?.take(512), job.id, job.leaseToken)
            if (changed == 1) jdbc.update("""UPDATE delivery_attempts SET outcome=?,safe_code=?,finished_at=clock_timestamp()
                WHERE id=? AND finished_at IS NULL""", status, code, job.leaseToken)
            if (changed == 1 && status == "CANCELLED" && code == "NO_LONGER_ELIGIBLE" && job.userId != null) {
                dirtyReminder(job.userId, jdbc.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)!!.toInstant())
            }
        }
    }

    override fun retry(job: DeliveryJob, code: String, delay: Duration) {
        require(code.matches(Regex("[A-Z0-9_]{1,64}")) && !delay.isNegative)
        transactions.executeWithoutResult {
            val changed = jdbc.update("""UPDATE delivery_jobs SET status='PENDING',last_code=?,
                next_attempt_at=clock_timestamp()+(? * interval '1 millisecond'),lease_token=NULL,lease_until=NULL
                WHERE id=? AND lease_token=? AND status IN ('CLAIMED','SMTP_SUBMITTING') AND lease_until>clock_timestamp()""",
                code, delay.toMillis(), job.id, job.leaseToken)
            if (changed == 1) jdbc.update("""UPDATE delivery_attempts SET outcome='RETRY',safe_code=?,finished_at=clock_timestamp()
                WHERE id=? AND finished_at IS NULL""", code, job.leaseToken)
        }
    }

    override fun maintain() {
        transactions.executeWithoutResult {
            jdbc.update("""WITH expired AS MATERIALIZED (
                SELECT id,lease_token,status FROM delivery_jobs WHERE status IN ('CLAIMED','SMTP_SUBMITTING')
                  AND lease_until<=clock_timestamp() ORDER BY lease_until LIMIT 500 FOR UPDATE SKIP LOCKED
              ), reclaimed AS (
                UPDATE delivery_jobs j SET status=CASE WHEN e.status='SMTP_SUBMITTING' THEN 'UNKNOWN' ELSE 'PENDING' END,
                  last_code='LEASE_EXPIRED',next_attempt_at=clock_timestamp(),
                  finished_at=CASE WHEN e.status='SMTP_SUBMITTING' THEN clock_timestamp() ELSE NULL END,
                  encrypted_payload=CASE WHEN e.status='SMTP_SUBMITTING' THEN NULL ELSE j.encrypted_payload END,
                  lease_token=NULL,lease_until=NULL FROM expired e WHERE j.id=e.id
                RETURNING e.lease_token AS old_token,e.status AS old_status
              ) UPDATE delivery_attempts a SET outcome=CASE WHEN r.old_status='SMTP_SUBMITTING' THEN 'UNKNOWN' ELSE 'RETRY' END,
                  safe_code='LEASE_EXPIRED',finished_at=clock_timestamp() FROM reclaimed r
                WHERE a.id=r.old_token AND a.finished_at IS NULL""")
            jdbc.update("""UPDATE delivery_jobs SET status='EXPIRED',last_code='EXPIRED',finished_at=clock_timestamp(),encrypted_payload=NULL
                WHERE id IN (SELECT id FROM delivery_jobs WHERE status='PENDING'
                  AND expires_at<=clock_timestamp() ORDER BY expires_at LIMIT 500)""")
            jdbc.update("""DELETE FROM delivery_jobs WHERE id IN (SELECT id FROM delivery_jobs
                WHERE finished_at<clock_timestamp()-interval '30 days' ORDER BY finished_at LIMIT 500)""")
        }
    }

    private fun map(row: ResultSet) = DeliveryJob(row.getObject("id", UUID::class.java), row.getString("channel"),
        row.getObject("user_id", UUID::class.java), row.getObject("device_id", UUID::class.java),
        row.getObject("registration_attempt_id", UUID::class.java), row.getString("kind"), row.getString("locale"),
        row.getString("encrypted_payload"), row.getTimestamp("expires_at").toInstant(), row.getObject("occurrence_id", UUID::class.java), row.getInt("attempt_count"),
        row.getObject("lease_token", UUID::class.java))

    private fun dirtyReminder(userId: UUID, at: Instant) {
        if (notificationRepository != null) notificationRepository.dirty(userId, at)
        else jdbc.update("""INSERT INTO reminder_deadlines(user_id,due_at) VALUES (?,?)
            ON CONFLICT(user_id) DO UPDATE SET due_at=LEAST(reminder_deadlines.due_at,EXCLUDED.due_at),
            generation=reminder_deadlines.generation+1""", userId, Timestamp.from(at))
    }
}

internal fun inQuietHours(time: LocalTime, start: LocalTime?, end: LocalTime?): Boolean = when {
    start == null || end == null || start == end -> false
    start < end -> time >= start && time < end
    else -> time >= start || time < end
}
