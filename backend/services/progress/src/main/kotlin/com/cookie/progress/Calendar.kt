package com.cookie.progress

import com.cookie.progress.domain.Achievement
import com.cookie.progress.domain.DayOutcome
import com.cookie.progress.domain.FoodFacts
import com.cookie.progress.domain.GoalBand
import com.cookie.progress.domain.GoalKind
import com.cookie.progress.domain.NutritionGoal
import com.cookie.progress.domain.Streak
import com.cookie.progress.domain.assessDay
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal data class CalendarDay(
    val date: LocalDate, val zone: ZoneId, val state: String, val openedAt: Instant?, val closesAt: Instant?,
    val profileSequence: Long, val goalSequence: Long?,
)

internal data class GoalRevision(val source: SourceFact) {
    val payload = source.payload.path("nutritionGoalVersion")
    val effectiveFrom = payload.date("effectiveFrom")
    val version = payload.positive("revision")
    val calculated = payload.string("outcome") == "CALCULATED"
    val nutrition = if (calculated) NutritionGoal(
        payload.band("calories"), payload.band("proteinG"), payload.band("fatG"), payload.band("carbsG"),
        payload.path("fiberG").takeIf(JsonNode::isObject)?.let { payload.band("fiberG") },
    ) else null
}

private fun JsonNode.band(name: String): GoalBand = path(name).let { band ->
    fun value(field: String) = band.path(field).takeIf(JsonNode::isNumber)?.decimalValue()
    GoalBand(GoalKind.valueOf(band.string("kind")), value("low"), value("center"), value("high"))
}

internal fun SourceFact?.food(): FoodFacts {
    if (this == null) {
        return FoodFacts(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
    }
    val totals = payload.path("nutritionTotals")
    return FoodFacts(payload.count("mealCount"), totals.quantity("calories"), totals.quantity("proteinG"),
        totals.quantity("fatG"), totals.quantity("carbsG"), totals.quantity("fiberG"))
}

internal data class ConfirmedDay(
    val result: Achievement, val before: Long?, val after: Long?, val userCut: Long,
    val goalSequence: Long?, val cheatDay: Boolean, val facts: JsonNode,
)

internal class ProgressCalendar(
    private val store: ProgressStore,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val publish: (UUID, CalendarDay) -> Unit = { _, _ -> },
) {
    private val db get() = store.jdbc
    private data class Profile(val zone: ZoneId, val seq: Long, val at: Instant)

    fun work() {
        val now = clock.instant()
        val due = db.query(
            "SELECT user_id FROM progress_accounts WHERE next_due_at<=? ORDER BY next_due_at,user_id LIMIT 64",
            { row, _ -> row.getObject(1, UUID::class.java) }, now.sqlTime(),
        )
        due.forEach { user ->
            try {
                run(user, now)
            } catch (_: org.springframework.dao.TransientDataAccessException) {
                // The transaction made no changes. A later poll retries this account.
            }
        }
    }

    fun run(user: UUID, now: Instant = clock.instant()) {
        store.transactions.executeWithoutResult {
            store.lock(user)
            if (store.deleted(user)) return@executeWithoutResult
            val exists = db.queryForObject("SELECT EXISTS(SELECT 1 FROM progress_accounts WHERE user_id=?)", Boolean::class.java, user)
            if (exists != true) return@executeWithoutResult
            for (owner in OWNERS) {
                val actual = store.cursor(user, owner)
                requestReplay(user, owner, actual, store.highEvidence(user, owner), now)
            }
            var day = active(user)
            if (day == null) {
                day = openNext(user, now)
                if (day == null) return@executeWithoutResult
            }
            val boundary = checkNotNull(day.closesAt)
            if (now < boundary) {
                val currentGoal = chooseGoal(user, day, store.cursor(user, "user"), boundary)
                if (currentGoal?.source?.sequence != day.goalSequence) {
                    db.update("UPDATE calendar_days SET goal_seq=? WHERE user_id=? AND local_date=?",
                        currentGoal?.source?.sequence, user, day.date)
                }
                publish(user, day.copy(goalSequence = currentGoal?.source?.sequence))
                due(user, boundary, now)
                return@executeWithoutResult
            }
            db.update("UPDATE calendar_days SET state='CLOSING' WHERE user_id=? AND local_date=?", user, day.date)
            val nutrition = certifiedCut(user, "nutrition", boundary, now)
            val profile = certifiedCut(user, "user", boundary, now)
            if (nutrition == null || profile == null) {
                due(user, now.plusSeconds(2), now)
                return@executeWithoutResult
            }
            close(user, day, nutrition, profile)
            due(user, now, now)
        }
    }

    fun day(user: UUID, date: LocalDate): CalendarDay? = db.query(
        "SELECT * FROM calendar_days WHERE user_id=? AND local_date=?", dayRow, user, date,
    ).singleOrNull()

    fun days(user: UUID, from: LocalDate, to: LocalDate): List<CalendarDay> = db.query(
        "SELECT * FROM calendar_days WHERE user_id=? AND local_date BETWEEN ? AND ? ORDER BY local_date", dayRow, user, from, to,
    )

    fun active(user: UUID): CalendarDay? = db.query(
        "SELECT * FROM calendar_days WHERE user_id=? AND state IN ('OPEN','CLOSING')", dayRow, user,
    ).singleOrNull()

    fun confirmed(user: UUID, date: LocalDate): ConfirmedDay? = confirmations(user, date, date)[date]

    fun confirmations(user: UUID, from: LocalDate, to: LocalDate): Map<LocalDate, ConfirmedDay> = db.query(
        "SELECT * FROM day_results WHERE user_id=? AND local_date BETWEEN ? AND ?", { row, _ ->
            val date = row.getObject("local_date", LocalDate::class.java)
            date to ConfirmedDay(Achievement(DayOutcome.valueOf(row.getString("outcome")), row.getInt("credit"),
                row.getObject("macros_met", Boolean::class.javaObjectType)), row.getObject("streak_before", Long::class.javaObjectType),
                row.getObject("streak_after", Long::class.javaObjectType), row.getLong("user_before_seq"),
                row.getObject("goal_seq", Long::class.javaObjectType), row.getBoolean("cheat_day"), mapper.readTree(row.getString("facts")))
        }, user, from, to,
    ).toMap()

    fun streak(user: UUID): Pair<Streak, LocalDate?> = db.query(
        "SELECT confirmed_streak,confirmed_best,last_confirmed_date FROM progress_accounts WHERE user_id=?",
        { row, _ -> Streak(row.getObject(1, Long::class.javaObjectType), row.getLong(2)) to row.getObject(3, LocalDate::class.java) },
        user,
    ).singleOrNull() ?: (Streak() to null)

    fun goal(user: UUID, sequence: Long?): GoalRevision? = sequence?.let {
        db.query("SELECT * FROM source_facts WHERE user_id=? AND owner='user' AND kind='user.goal.changed' AND source_seq=?",
            store.factRow, user, it).singleOrNull()?.let(::GoalRevision)
    }

    private fun close(user: UUID, day: CalendarDay, nutrition: SourceCut, profile: SourceCut) {
        val goal = chooseGoal(user, day, profile.before, nutrition.boundary)
        val fact = store.latest(user, "nutrition.day.changed", day.date.toString(), nutrition.before)
        val cheatDay = cheatDay(user, day.date, profile.before)
        val assessment = assessDay(fact.food(), goal?.nutrition, cheatDay)
        val previous = streak(user).first
        val settled = previous.close(assessment)
        db.update(
            """INSERT INTO day_results VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb)""",
            user, day.date, assessment.outcome.name, goal?.source?.sequence, cheatDay, assessment.macrosMet,
            assessment.credit, if (goal?.calculated == true) previous.confirmed ?: 0L else null,
            settled.confirmed, nutrition.before, profile.before, fact?.payload?.toString() ?: "{}",
        )
        db.update("UPDATE calendar_days SET state='CLOSED',goal_seq=? WHERE user_id=? AND local_date=?", goal?.source?.sequence, user, day.date)
        db.update("""UPDATE progress_accounts SET confirmed_streak=?,confirmed_best=?,last_confirmed_date=?,
               first_goal_applied=first_goal_applied OR ? WHERE user_id=?""",
            settled.confirmed, settled.best, day.date, goal?.calculated == true, user,
        )
        publish(user, day.copy(state = "CLOSED", goalSequence = goal?.source?.sequence))
    }

    fun chooseGoal(user: UUID, day: CalendarDay, through: Long, before: Instant): GoalRevision? {
        val opened = checkNotNull(day.openedAt)
        val revisions = store.history(user, "user.goal.changed", through).map { GoalRevision(it.first) to it.second }
            .filter { it.second < before }
        val atOpen = revisions.filter { it.first.effectiveFrom <= day.date && it.second <= opened }
            .maxByOrNull { it.first.version }?.first
        val hadGoal = db.queryForObject("SELECT first_goal_applied FROM progress_accounts WHERE user_id=?", Boolean::class.java, user)
        if (hadGoal == true || atOpen?.calculated == true) return atOpen
        val profiles = profiles(user, through)
        return revisions.firstOrNull { (goal, commit) ->
            goal.calculated && commit >= opened && (
                goal.effectiveFrom <= day.date ||
                    profiles.lastOrNull { it.seq <= checkNotNull(goal.source.sequence) }?.let {
                        goal.effectiveFrom <= commit.atZone(it.zone).toLocalDate()
                    } == true
                )
        }?.first ?: atOpen
    }

    fun cheatDay(user: UUID, date: LocalDate, through: Long? = null): Boolean =
        store.latest(user, "user.goal_override.changed", date.toString(), through)?.payload?.path("override")?.asString("") == "CHEAT_DAY"

    private fun openNext(user: UUID, now: Instant): CalendarDay? {
        val previous = db.query(
            "SELECT * FROM calendar_days WHERE user_id=? AND state='CLOSED' ORDER BY local_date DESC LIMIT 1",
            dayRow, user,
        ).singleOrNull()
        if (previous == null) {
            val profiles = profiles(user)
            val initial = profiles.firstOrNull()
            val groupEnd = initial?.let {
                db.queryForObject("SELECT last_seq FROM source_groups WHERE user_id=? AND owner='user' AND ? BETWEEN first_seq AND last_seq",
                    Long::class.java, user, it.seq)
            }
            val first = groupEnd?.let { end -> profiles.last { it.seq <= end } }
            if (first == null || first.at > now) {
                due(user, first?.at, now)
                return null
            }
            return open(user, first.at.atZone(first.zone).toLocalDate(), first, first.at)
        }
        var inspectedAt = checkNotNull(previous.closesAt)
        while (inspectedAt <= now) {
            val proof = certifiedCut(user, "user", inspectedAt, now)
            if (proof == null) {
                due(user, now.plusSeconds(2), now)
                return null
            }
            val profiles = profiles(user, proof.through)
            val atEntry = profiles.lastOrNull { it.at <= checkNotNull(previous.closesAt) } ?: return null
            val opening = firstNewDate(previous, atEntry, profiles, inspectedAt)
            if (opening != null) {
                val (profile, start) = opening
                val date = start.atZone(profile.zone).toLocalDate()
                for (skipped in previous.date.plusDays(1).datesUntil(date)) {
                    db.update("""INSERT INTO calendar_days(user_id,local_date,zone_id,state,profile_seq)
                        VALUES (?,?,?,'TRAVEL_SKIPPED',?) ON CONFLICT DO NOTHING""", user, skipped, profile.zone.id, profile.seq)
                }
                return open(user, date, profile, start)
            }
            val profileNow = profiles.last()
            val nextMidnight = inspectedAt.atZone(profileNow.zone).toLocalDate().plusDays(1).atStartOfDay(profileNow.zone).toInstant()
            val nextKnownZone = profiles(user).firstOrNull { it.at > inspectedAt && it.zone != profileNow.zone }?.at
            inspectedAt = if (nextKnownZone == null) nextMidnight else minOf(nextMidnight, nextKnownZone)
        }
        due(user, inspectedAt, now)
        return null
    }

    private fun firstNewDate(
        previous: CalendarDay, startProfile: Profile, profiles: List<Profile>, through: Instant,
    ): Pair<Profile, Instant>? {
        var at = checkNotNull(previous.closesAt)
        var profile = startProfile
        val changes = profiles.filter { it.at > at && it.at <= through }
        for (next in changes.map<Profile, Profile?> { it } + null) {
            val intervalEnd = next?.at ?: through
            val firstNewMidnight = previous.date.plusDays(1).atStartOfDay(profile.zone).toInstant()
            val candidate = maxOf(at, firstNewMidnight)
            if (if (next == null) candidate <= intervalEnd else candidate < intervalEnd) return profile to candidate
            if (next != null) {
                profile = next
                at = next.at
            }
        }
        return null
    }

    private fun profiles(user: UUID, through: Long = store.cursor(user, "user")) =
        store.history(user, "user.profile.updated", through).map { (fact, at) ->
            Profile(fact.payload.zone("timezone"), checkNotNull(fact.sequence), at)
        }

    private fun open(user: UUID, date: LocalDate, profile: Profile, start: Instant): CalendarDay {
        val end = date.plusDays(1).atStartOfDay(profile.zone).toInstant()
        db.update("""INSERT INTO calendar_days(user_id,local_date,zone_id,state,opened_at,closes_at,profile_seq)
                   VALUES (?,?,?,'OPEN',?,?,?)""", user, date, profile.zone.id, start.sqlTime(), end.sqlTime(), profile.seq)
        return CalendarDay(date, profile.zone, "OPEN", start, end, profile.seq, null)
    }

    private fun certifiedCut(user: UUID, owner: String, boundary: Instant, now: Instant): SourceCut? {
        val cut = store.cut(user, owner, boundary)
        if (cut == null) {
            if (store.pendingRequest(user, "$owner:cut:$boundary", now)) store.enqueue(user, "progress.${owner}_cut.requested", 1,
                "progress_source", user.toString(), mapper.valueToTree(mapOf("userId" to user, "boundary" to boundary.toString())))
            return null
        }
        val cursor = store.cursor(user, owner)
        if (cursor < cut.through) {
            requestReplay(user, owner, cursor, cut.through, now)
            return null
        }
        val verified = db.queryForObject(
            """SELECT COALESCE(MAX(last_seq) FILTER (WHERE committed_at<?),0),COALESCE(MAX(last_seq),0)
               FROM source_groups WHERE user_id=? AND owner=? AND committed_at<=? AND last_seq<=?""",
            { row, _ -> row.getLong(1) == cut.before && row.getLong(2) == cut.through },
            boundary.sqlTime(), user, owner, boundary.sqlTime(), cursor,
        )
        return cut.takeIf { verified }
    }

    private fun requestReplay(user: UUID, owner: String, from: Long, expected: Long, now: Instant) {
        if (from >= expected || !store.pendingRequest(user, "$owner:replay:$from", now)) return
        store.enqueue(user, "progress.${owner}_replay.requested", 1, "progress_source", user.toString(),
            mapper.valueToTree(mapOf("userId" to user, "firstSeq" to from + 1, "lastSeq" to minOf(expected, from + 64))))
    }

    private fun due(user: UUID, at: Instant?, now: Instant) {
        val gap = OWNERS.any { store.cursor(user, it) < store.highEvidence(user, it) }
        val next = if (gap) minOf(at ?: Instant.MAX, now.plusSeconds(2)) else at
        db.update("UPDATE progress_accounts SET next_due_at=? WHERE user_id=?", next?.sqlTime(), user)
    }

    private val dayRow = org.springframework.jdbc.core.RowMapper { row, _ ->
        CalendarDay(row.getObject("local_date", LocalDate::class.java), ZoneId.of(row.getString("zone_id")),
            row.getString("state"), row.instant("opened_at"), row.instant("closes_at"), row.getLong("profile_seq"),
            row.getObject("goal_seq", Long::class.javaObjectType))
    }

    companion object { private val OWNERS = listOf("user", "nutrition") }
}
