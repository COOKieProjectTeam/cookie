package com.cookie.progress

import com.cookie.progress.domain.DayOutcome
import com.cookie.progress.domain.FoodFacts
import com.cookie.progress.domain.GoalBand
import com.cookie.progress.domain.assessDay
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

internal class ProgressFailure(val status: Int, val code: String) : RuntimeException(code)

internal class ProgressReads(
    private val store: ProgressStore, private val calendar: ProgressCalendar, private val clock: Clock, private val mapper: ObjectMapper,
    private val requireReadable: () -> Unit = {},
) {
    private val db get() = store.jdbc
    private data class Weight(val id: UUID, val payload: JsonNode) {
        val date = payload.date("measurementLocalDate")
        val kg = checkNotNull(payload.quantity("valueKg"))
        val measuredAt = payload.instant("measuredAt")
        fun dto() = mapOf("measurementId" to id, "date" to date, "measuredAt" to measuredAt,
            "valueKg" to kg.toPlainString(), "source" to payload.string("source"))
    }
    private data class Facts(val food: FoodFacts, val water: Int, val hasWater: Boolean, val steps: Int?, val activeKcal: BigDecimal?)
    private data class View(val dto: Map<String, Any?>, val facts: Facts, val closed: ConfirmedDay?, val travel: Boolean)

    fun day(user: UUID, date: LocalDate): Map<String, Any?> = account(user) {
        validateRange(date, date)
        views(user, date, date).single().dto
    }

    fun today(user: UUID): Map<String, Any?> = account(user) {
        val date = currentDate(user)
        views(user, date, date).single().dto
    }

    fun range(user: UUID, from: LocalDate, to: LocalDate): Map<String, Any?> = account(user) {
        validateRange(from, to)
        val days = views(user, from, to)
        mapOf("days" to days.map(View::dto), "summary" to summary(days))
    }

    fun weights(user: UUID, from: LocalDate, to: LocalDate): Map<String, Any?> = account(user) {
        validateRange(from, to)
        val weights = allWeights(user)
        val points = dailyWeights(weights).filter { it.date in from..to }
        val connectionCount = store.latest(user, CONNECTIONS, user.toString())?.payload?.count("activeConnectionCount")
        val state = when (connectionCount) {
            null -> "UNKNOWN"
            0L -> "NOT_CONNECTED"
            else -> "CONNECTED"
        }
        val open = calendar.active(user)
        val activeGoal = if (open != null && open.state == "OPEN" && clock.instant() < checkNotNull(open.closesAt))
            calendar.chooseGoal(user, open, store.cursor(user, "user"), minOf(open.closesAt, clock.instant().plusNanos(1)))
        else calendar.goal(user, calendar.streak(user).second?.let { calendar.day(user, it)?.goalSequence })
        mapOf(
            "points" to points.map(Weight::dto), "latestKnown" to weights.maxWithOrNull(weightOrder)?.dto(),
            "targetWeightKg" to activeGoal?.payload?.path("targetWeightKg")?.exact()?.toPlainString(),
            "periodChangeKg" to if (points.size < 2) null else points.last().kg.subtract(points.first().kg).setScale(2).toPlainString(),
            "connectionState" to state, "activeConnectionCount" to connectionCount,
        )
    }

    private fun <T : Any> account(user: UUID, read: () -> T): T = requireNotNull(store.transactions.execute {
        store.lock(user, shared = true)
        if (store.deleted(user)) throw ProgressFailure(403, "ACCOUNT_DELETED")
        requireReadable()
        read()
    })

    private fun currentDate(user: UUID): LocalDate {
        val now = clock.instant()
        val open = calendar.active(user)
        if (open?.state == "OPEN" && now < checkNotNull(open.closesAt)) return open.date
        val profile = store.latest(user, PROFILE, user.toString())
        return now.atZone(profile?.payload?.zone("timezone") ?: ZoneOffset.UTC).toLocalDate()
    }

    private fun views(user: UUID, from: LocalDate, to: LocalDate): List<View> {
        val calendars = calendar.days(user, from, to).associateBy(CalendarDay::date)
        val confirmations = calendar.confirmations(user, from, to)
        val facts = latestDayFacts(user, from, to).associate { (it.kind to it.key) to it.payload }
        val weights = dailyWeights(allWeights(user)).associateBy(Weight::date)
        val revisions = store.history(user, GOAL).map { GoalRevision(it.first) }.associateBy { it.source.sequence }
        val hydration = store.history(user, HYDRATION).map { it.first }
        val appliedDates = db.query("SELECT goal_seq,min(local_date) FROM calendar_days WHERE user_id=? AND goal_seq IS NOT NULL GROUP BY goal_seq",
            { row, _ -> row.getLong(1) to row.getObject(2, LocalDate::class.java) }, user).toMap()
        val firstDate = db.query("SELECT local_date FROM calendar_days WHERE user_id=? ORDER BY local_date LIMIT 1",
            { row, _ -> row.getObject(1, LocalDate::class.java) }, user).singleOrNull()
        val profileKnown = store.latest(user, PROFILE, user.toString()) != null
        val gap = listOf("user", "nutrition").any { store.cursor(user, it) < store.highEvidence(user, it) }
        val (streak, lastDate) = calendar.streak(user)
        val current = currentDate(user)
        val active = calendar.active(user)
        val closing = active?.state == "CLOSING" || active?.closesAt?.let { clock.instant() >= it } == true
        val liveDate = active?.date
        val liveGoal = if (active != null && !closing) {
            calendar.chooseGoal(user, active, store.cursor(user, "user"), minOf(checkNotNull(active.closesAt), clock.instant().plusNanos(1)))
        } else null
        val liveFood = liveDate?.let { store.latest(user, FOOD, it.toString()) }.food()
        val liveAssessment = liveDate?.let { assessDay(liveFood, liveGoal?.nutrition, calendar.cheatDay(user, it)) }
        val provisional = if (gap || closing) null else if (liveAssessment != null) streak.provisional(liveAssessment) else streak.confirmed
        return from.datesUntil(to.plusDays(1)).map { date ->
            val day = calendars[date]
            val closed = confirmations[date]
            val open = day?.state == "OPEN" && !closing
            val goal = if (open) liveGoal else revisions[day?.goalSequence]
            val goalWater = hydration.asSequence().filter {
                checkNotNull(it.sequence) <= (closed?.userCut ?: Long.MAX_VALUE) && it.payload.date("effectiveFrom") <= date
            }.maxByOrNull { it.payload.positive("revision") }?.payload?.count("targetMl")
            val dayKey = date.toString()
            val food = food(facts[FOOD to dayKey])
            val water = facts[WATER to dayKey]
            val activity = facts[ACTIVITY to dayKey]?.takeUnless { it.bool("deleted") }
            val cheat = facts[OVERRIDE to dayKey]?.path("override")?.asString("") == "CHEAT_DAY"
            val row = Facts(food, water?.count("totalMl")?.toInt() ?: 0,
                water?.path("lastConsumedAt")?.isString == true, activity?.path("steps")?.takeIf(JsonNode::isNumber)?.intValue(),
                activity?.quantity("activeKcal"))
            val state = when {
                day?.state == "OPEN" && closing -> "CLOSING"
                day != null -> day.state
                !profileKnown -> "AWAITING_SOURCE"
                firstDate != null && date < firstDate -> "BEFORE_TRACKING"
                else -> "UPCOMING"
            }
            val stale = !profileKnown || gap || state == "CLOSING" || (closing && date == current && day == null)
            val achievement = when {
                closed != null -> mapOf("stage" to "FINAL", "outcome" to closed.result.outcome.name,
                    "credit" to closed.result.credit, "macrosMet" to closed.result.macrosMet,
                    "streakBefore" to closed.before, "streakAfter" to closed.after,
                    "goalRevisionAtClose" to goal?.version, "cheatDayAtClose" to closed.cheatDay,
                    "caloriesKcalAtClose" to closed.facts.path("nutritionTotals").path("calories").exact()?.toPlainString())
                day?.state == "OPEN" && !stale && liveAssessment != null -> mapOf(
                    "stage" to "PROVISIONAL", "outcome" to liveAssessment.outcome.name, "credit" to liveAssessment.credit,
                    "macrosMet" to liveAssessment.macrosMet, "streakBefore" to if (liveGoal?.calculated == true) streak.confirmed ?: 0 else null,
                    "streakAfter" to provisional,
                )
                else -> null
            }
            View(
                mapOf(
                    "date" to date, "calendar" to mapOf("state" to state, "timezone" to day?.zone?.id,
                        "openedAt" to day?.openedAt, "closesAt" to day?.closesAt),
                    "projectionState" to if (stale) "AWAITING_SOURCE" else "READY", "cheatDay" to cheat,
                    "goal" to goalDto(goal, if (day != null) appliedDates[goal?.source?.sequence] ?: day.date.takeIf { open && goal != null } else null, goalWater,
                        profileKnown && day != null && day.state != "TRAVEL_SKIPPED"),
                    "facts" to mapOf("mealCount" to food.mealCount, "caloriesKcal" to food.calories?.toPlainString(),
                        "proteinG" to food.protein?.toPlainString(), "fatG" to food.fat?.toPlainString(),
                        "carbsG" to food.carbs?.toPlainString(), "fiberG" to food.fiber?.toPlainString(),
                        "waterMl" to row.water, "hasWaterEntries" to row.hasWater, "steps" to row.steps,
                        "activeKcal" to row.activeKcal?.toPlainString(), "weight" to weights[date]?.dto()),
                    "achievement" to achievement, "streak" to mapOf(
                        "state" to if (closing || !profileKnown || gap) "AWAITING_SOURCE"
                            else if (provisional == null) "NOT_APPLICABLE" else "APPLICABLE",
                        "current" to provisional, "provisionalCredit" to if (closing || gap) 0 else liveAssessment?.credit ?: 0,
                        "confirmedBest" to streak.best, "lastConfirmed" to lastDate,
                    ),
                ), row, closed, state == "TRAVEL_SKIPPED",
            )
        }.toList()
    }

    private fun latestDayFacts(user: UUID, from: LocalDate, to: LocalDate): List<SourceFact> = db.query(
        """SELECT DISTINCT ON (f.kind,f.entity_key) f.* FROM source_facts f LEFT JOIN source_cursors c USING(user_id,owner)
           WHERE f.user_id=? AND f.kind IN ('nutrition.day.changed','nutrition.water.changed','health.activity.changed','user.goal_override.changed')
              AND f.entity_key BETWEEN ? AND ? AND (f.source_seq IS NULL OR f.source_seq <= c.applied_through)
           ORDER BY f.kind,f.entity_key,f.version DESC""", store.factRow, user, from.toString(), to.toString(),
    )

    private fun allWeights(user: UUID): List<Weight> = db.query(
        """SELECT * FROM (
             SELECT DISTINCT ON (entity_key) entity_key,payload FROM source_facts
             WHERE user_id=? AND kind='health.weight.changed' ORDER BY entity_key,version DESC
           ) current WHERE payload->>'deleted'='false' AND payload->>'measurementLocalDate' IS NOT NULL""",
        { row, _ -> Weight(UUID.fromString(row.getString("entity_key")), mapper.readTree(row.getString("payload"))) }, user,
    )

    private fun dailyWeights(weights: List<Weight>): List<Weight> = weights.groupBy(Weight::date).toSortedMap().values
        .map { it.maxWith(weightOrder) }

    private fun summary(views: List<View>): Map<String, Any?> {
        val closed = views.filter { it.closed != null }
        val withFood = closed.filter { it.facts.food.mealCount > 0 }.map { it.facts.food }
        val common = withFood.filter { it.protein != null && it.fat != null && it.carbs != null }
        val closedWater = closed.filter { it.facts.hasWater }.map { it.facts.water.toBigDecimal() }
        val outcomes = DayOutcome.entries.associate { outcome -> outcome.name to closed.count { it.closed?.result?.outcome == outcome } }
        return mapOf(
            "closedDays" to closed.size, "closedFoodDays" to withFood.size,
            "travelSkippedDays" to views.count(View::travel), "outcomes" to outcomes,
            "markedCheatDays" to closed.count { it.closed?.cheatDay == true },
            "creditedDays" to closed.count { it.closed?.result?.credit == 1 },
            "nutrients" to mapOf("caloriesKcal" to metric(withFood.mapNotNull(FoodFacts::calories)),
                "proteinG" to metric(withFood.mapNotNull(FoodFacts::protein)), "fatG" to metric(withFood.mapNotNull(FoodFacts::fat)),
                "carbsG" to metric(withFood.mapNotNull(FoodFacts::carbs)), "fiberG" to metric(withFood.mapNotNull(FoodFacts::fiber))),
            "macroComposition" to mapOf("commonDays" to common.size, "proteinG" to sum(common.mapNotNull(FoodFacts::protein)),
                "fatG" to sum(common.mapNotNull(FoodFacts::fat)), "carbsG" to sum(common.mapNotNull(FoodFacts::carbs))),
            "water" to mapOf("loggedTotalMl" to views.sumOf { it.facts.water.toLong() },
                "closedSumMl" to closedWater.sumOf(BigDecimal::longValueExact), "coveredClosedDays" to closedWater.size,
                "closedAverageMl" to average(closedWater)),
            "steps" to metric(closed.mapNotNull { it.facts.steps?.toBigDecimal() }),
            "activeKcal" to metric(closed.mapNotNull { it.facts.activeKcal }),
        )
    }

    private fun goalDto(goal: GoalRevision?, appliedFrom: LocalDate?, water: Long?, known: Boolean): Map<String, Any?> {
        fun GoalBand?.dto() = this?.let { mapOf("kind" to it.kind.name, "low" to it.low?.toPlainString(),
            "center" to it.center?.toPlainString(), "high" to it.high?.toPlainString()) }
        return mapOf(
            "state" to if (!known) "UNKNOWN" else if (goal?.calculated == true) "CALCULATED" else "NO_CALORIE_GOAL",
            "id" to goal?.payload?.string("id"), "revision" to goal?.version, "effectiveFrom" to goal?.effectiveFrom,
            "appliedFrom" to appliedFrom, "caloriesKcal" to goal?.nutrition?.calories.dto(), "proteinG" to goal?.nutrition?.protein.dto(),
            "fatG" to goal?.nutrition?.fat.dto(), "carbsG" to goal?.nutrition?.carbs.dto(), "fiberG" to goal?.nutrition?.fiber.dto(),
            "waterTargetMl" to water,
        )
    }

    private fun food(payload: JsonNode?): FoodFacts = payload?.let {
        val totals = it.path("nutritionTotals")
        FoodFacts(it.count("mealCount"), totals.quantity("calories"), totals.quantity("proteinG"),
            totals.quantity("fatG"), totals.quantity("carbsG"), totals.quantity("fiberG"))
    } ?: (null as SourceFact?).food()

    private fun metric(values: List<BigDecimal>) =
        mapOf("sum" to sum(values), "average" to average(values), "coveredDays" to values.size)
    private fun sum(values: List<BigDecimal>) = values.fold(BigDecimal.ZERO, BigDecimal::add).let {
        it.setScale(maxOf(3, it.scale())).toPlainString()
    }
    private fun average(values: List<BigDecimal>): String? = if (values.isEmpty()) null else
        values.fold(BigDecimal.ZERO, BigDecimal::add).divide(values.size.toBigDecimal(), 3, RoundingMode.HALF_UP).toPlainString()

    private fun validateRange(from: LocalDate, to: LocalDate) {
        if (from.year !in 1..9999 || to.year !in 1..9999 || ChronoUnit.DAYS.between(from, to) !in 0..365) {
            throw ProgressFailure(400, "INVALID_DATE_RANGE")
        }
    }

    private fun JsonNode.exact(): BigDecimal? = if (isNumber) decimalValue() else null

    companion object {
        private const val PROFILE = "user.profile.updated"
        private const val GOAL = "user.goal.changed"
        private const val HYDRATION = "user.hydration_goal.changed"
        private const val OVERRIDE = "user.goal_override.changed"
        private const val FOOD = "nutrition.day.changed"
        private const val WATER = "nutrition.water.changed"
        private const val ACTIVITY = "health.activity.changed"
        private const val CONNECTIONS = "health.connection_summary.changed"
        private val weightOrder = compareBy<Weight> { it.measuredAt }.thenBy { it.id.toString() }
    }
}
