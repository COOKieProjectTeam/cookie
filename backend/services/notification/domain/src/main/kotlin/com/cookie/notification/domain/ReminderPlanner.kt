package com.cookie.notification.domain

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class MealSnapshot(val id: String, val eatenAt: Instant)

data class ProgressSnapshot(
    val localDate: LocalDate,
    val timezone: String,
    val caloriesConsumed: BigDecimal?,
    val calorieTarget: BigDecimal?,
    val unlimitedCalories: Boolean?,
    val waterConsumedMl: Int?,
    val waterTargetMl: Int?,
)

data class OccurrenceKey(val kind: ReminderKind, val cycle: String)

data class PlannedReminder(
    val kind: ReminderKind,
    val cycle: String,
    val scheduledAt: Instant,
    val expiresAt: Instant,
)

data class ReminderPlanningState(
    val settings: NotificationSettings,
    val foodStartedAt: Instant?,
    val waterStartedAt: Instant?,
    val lastWaterReminderAt: Instant?,
    val meals: List<MealSnapshot> = emptyList(),
    val waterRecords: List<Instant> = emptyList(),
    val weightMeasurements: List<Instant> = emptyList(),
    val progress: List<ProgressSnapshot> = emptyList(),
    val emitted: Set<OccurrenceKey> = emptySet(),
    val lastWaterSubmissionAt: Instant? = null,
)

data class ReminderPlan(val due: List<PlannedReminder>, val nextWakeup: Instant?)

/** Pure reminder decisions. Clock, storage, broker and delivery providers remain outside this model. */
object ReminderPlanner {
    fun plan(state: ReminderPlanningState, now: Instant): ReminderPlan {
        val settings = state.settings
        val timezone = settings.timezone ?: return ReminderPlan(emptyList(), null)
        val started = settings.schedulingStartedAt ?: return ReminderPlan(emptyList(), null)
        val zone = ZoneId.of(timezone)
        val day = now.atZone(zone).toLocalDate()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
        val wakeups = mutableListOf(dayEnd)
        val due = mutableListOf<PlannedReminder>()

        // A future-dated record becomes relevant when its actual timestamp arrives.
        (state.meals.map { it.eatenAt } + state.waterRecords + state.weightMeasurements)
            .filter { it > now }
            .forEach(wakeups::add)

        fun candidate(kind: ReminderKind, cycle: String, scheduled: Instant, expiry: Instant) {
            if (OccurrenceKey(kind, cycle) in state.emitted || expiry <= now) return
            val effective = ReminderPolicy.afterQuiet(maxOf(scheduled, now), settings)
            val daily = kind != ReminderKind.FOOD && kind != ReminderKind.WATER
            if (daily && effective >= expiry) return
            if (effective > now) {
                wakeups += effective
                return
            }
            due += PlannedReminder(kind, cycle, scheduled, expiry)
        }

        if (settings.foodEnabled) {
            val meal = latestMeal(state, now)
            val baseline = state.foodStartedAt ?: started
            val scheduled = maxOf(baseline, meal?.eatenAt ?: baseline)
                .plusSeconds(settings.foodInactiveMinutes * 60L)
            candidate(ReminderKind.FOOD, foodCycle(state, meal), scheduled, now.plus(Duration.ofDays(1)))
        }

        val progress = progressForDay(state, now)
        if (settings.waterEnabled && !waterGoalReached(progress)) {
            val baseline = state.waterStartedAt ?: started
            val anchor = maxOf(baseline, lastWater(state, now) ?: baseline,
                state.lastWaterReminderAt ?: baseline, state.lastWaterSubmissionAt ?: baseline)
            val scheduled = anchor.plus(Duration.ofHours(2))
            candidate(ReminderKind.WATER, scheduled.toString(), scheduled, now.plus(Duration.ofHours(2)))
            if (due.any { it.kind == ReminderKind.WATER }) wakeups += now.plus(Duration.ofHours(2))
        }

        if (settings.weighEnabled) {
            val scheduled = ReminderPolicy.atLocalTime(day, settings.weighTime, zone)
            if (scheduled >= started && !weighedToday(state, now)) {
                candidate(ReminderKind.WEIGH, day.toString(), scheduled, dayEnd)
            }
            wakeups += ReminderPolicy.atLocalTime(day.plusDays(1), settings.weighTime, zone)
        }

        if (settings.goalWarningEnabled) {
            if (exceeded(progress)) candidate(ReminderKind.CALORIES_EXCEEDED, day.toString(), now, dayEnd)
            val underTime = ReminderPolicy.atLocalTime(day, LocalTime.of(20, 0), zone)
            if (underTarget(progress)) {
                candidate(ReminderKind.CALORIES_UNDER_TARGET, day.toString(), underTime, dayEnd)
            }
            wakeups += if (underTime > now) underTime
                else ReminderPolicy.atLocalTime(day.plusDays(1), LocalTime.of(20, 0), zone)
        }

        return ReminderPlan(due, wakeups.filter { it > now }.minOrNull())
    }

    /** Re-evaluates a durable occurrence just before provider submission, without consuming its cycle. */
    fun isEligible(
        state: ReminderPlanningState,
        kind: ReminderKind,
        cycle: String,
        scheduledAt: Instant,
        now: Instant,
    ): Boolean {
        val settings = state.settings
        val zone = settings.timezone?.let(ZoneId::of) ?: return false
        val started = settings.schedulingStartedAt ?: return false
        val day = now.atZone(zone).toLocalDate()
        val progress = progressForDay(state, now)

        return when (kind) {
            ReminderKind.FOOD -> {
                val meal = latestMeal(state, now)
                val baseline = state.foodStartedAt ?: started
                settings.foodEnabled && cycle == foodCycle(state, meal) &&
                    maxOf(baseline, meal?.eatenAt ?: baseline).plusSeconds(settings.foodInactiveMinutes * 60L) <= now
            }
            ReminderKind.WATER -> {
                val baseline = state.waterStartedAt ?: started
                settings.waterEnabled && scheduledAt >= baseline &&
                    maxOf(baseline, lastWater(state, now) ?: baseline).plusSeconds(7200) <= now &&
                    !waterGoalReached(progress)
            }
            ReminderKind.WEIGH ->
                settings.weighEnabled && cycle == day.toString() &&
                    ReminderPolicy.atLocalTime(day, settings.weighTime, zone) <= now && !weighedToday(state, now)
            ReminderKind.CALORIES_EXCEEDED ->
                settings.goalWarningEnabled && cycle == day.toString() && exceeded(progress)
            ReminderKind.CALORIES_UNDER_TARGET ->
                settings.goalWarningEnabled && cycle == day.toString() &&
                    now.atZone(zone).toLocalTime() >= LocalTime.of(20, 0) && underTarget(progress)
        }
    }

    private fun latestMeal(state: ReminderPlanningState, now: Instant): MealSnapshot? = state.meals
        .filter { it.eatenAt <= now }
        .maxWithOrNull(compareBy<MealSnapshot> { it.eatenAt }.thenBy { it.id })

    private fun foodCycle(state: ReminderPlanningState, meal: MealSnapshot?): String =
        "${state.foodStartedAt ?: state.settings.schedulingStartedAt}:${meal?.id ?: "initial"}"

    private fun lastWater(state: ReminderPlanningState, now: Instant): Instant? =
        state.waterRecords.filter { it <= now }.maxOrNull()

    private fun progressForDay(state: ReminderPlanningState, now: Instant): ProgressSnapshot? {
        val zone = state.settings.timezone ?: return null
        val day = now.atZone(ZoneId.of(zone)).toLocalDate()
        return state.progress.firstOrNull { it.timezone == zone && it.localDate == day }
    }

    private fun weighedToday(state: ReminderPlanningState, now: Instant): Boolean {
        val zone = ZoneId.of(requireNotNull(state.settings.timezone))
        val day = now.atZone(zone).toLocalDate()
        return state.weightMeasurements.any { it <= now && it.atZone(zone).toLocalDate() == day }
    }

    private fun waterGoalReached(progress: ProgressSnapshot?): Boolean =
        ReminderPolicy.waterGoalReached(progress?.waterConsumedMl, progress?.waterTargetMl)

    private fun exceeded(progress: ProgressSnapshot?): Boolean =
        ReminderPolicy.caloriesExceeded(progress?.caloriesConsumed, progress?.calorieTarget, progress?.unlimitedCalories)

    private fun underTarget(progress: ProgressSnapshot?): Boolean =
        ReminderPolicy.caloriesUnderTarget(progress?.caloriesConsumed, progress?.calorieTarget, progress?.unlimitedCalories)
}
