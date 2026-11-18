package com.cookie.progress.domain

import java.math.BigDecimal

enum class DayOutcome { MACROS_MET, CHEAT_DAY_CREDIT, OUT_OF_TARGET, INSUFFICIENT_DATA, NOT_APPLICABLE }

data class FoodFacts(
    val mealCount: Long,
    val calories: BigDecimal?,
    val protein: BigDecimal?,
    val fat: BigDecimal?,
    val carbs: BigDecimal?,
    val fiber: BigDecimal? = null,
) {
    init {
        require(mealCount >= 0)
    }
}

enum class GoalKind { RANGE, UPPER_LIMIT, AT_LEAST }

data class GoalBand(
    val kind: GoalKind,
    val low: BigDecimal? = null,
    val center: BigDecimal? = null,
    val high: BigDecimal? = null,
) {
    init {
        when (kind) {
            GoalKind.RANGE -> require(low != null && high != null && low <= high)
            GoalKind.UPPER_LIMIT -> require(high != null)
            GoalKind.AT_LEAST -> require(low != null)
        }
    }

    internal fun contains(value: BigDecimal): Boolean = when (kind) {
        GoalKind.RANGE -> value >= requireNotNull(low) && value <= requireNotNull(high)
        GoalKind.UPPER_LIMIT -> value <= requireNotNull(high)
        GoalKind.AT_LEAST -> value >= requireNotNull(low)
    }
}

data class NutritionGoal(
    val calories: GoalBand,
    val protein: GoalBand,
    val fat: GoalBand,
    val carbs: GoalBand,
    val fiber: GoalBand? = null,
)

data class Achievement(val outcome: DayOutcome, val credit: Int, val macrosMet: Boolean?) {
    init {
        val earnsCredit = outcome == DayOutcome.MACROS_MET || outcome == DayOutcome.CHEAT_DAY_CREDIT
        require(credit == if (earnsCredit) 1 else 0)
    }
}

fun assessDay(food: FoodFacts, goal: NutritionGoal?, cheatDay: Boolean): Achievement {
    if (goal == null) return Achievement(DayOutcome.NOT_APPLICABLE, 0, null)

    val macrosMet = if (food.mealCount > 0 && food.calories != null && food.protein != null &&
        food.fat != null && food.carbs != null
    ) {
        goal.calories.contains(food.calories) && goal.protein.contains(food.protein) &&
            goal.fat.contains(food.fat) && goal.carbs.contains(food.carbs)
    } else {
        null
    }
    return when {
        macrosMet == true -> Achievement(DayOutcome.MACROS_MET, 1, true)
        cheatDay -> Achievement(DayOutcome.CHEAT_DAY_CREDIT, 1, macrosMet)
        macrosMet == false -> Achievement(DayOutcome.OUT_OF_TARGET, 0, false)
        else -> Achievement(DayOutcome.INSUFFICIENT_DATA, 0, null)
    }
}

data class Streak(val confirmed: Long? = null, val best: Long = 0) {
    fun provisional(assessment: Achievement): Long? = when (assessment.outcome) {
        DayOutcome.NOT_APPLICABLE -> null
        else -> (confirmed ?: 0L) + assessment.credit
    }

    fun close(assessment: Achievement): Streak {
        val length = when (assessment.outcome) {
            DayOutcome.NOT_APPLICABLE -> null
            DayOutcome.INSUFFICIENT_DATA, DayOutcome.OUT_OF_TARGET -> 0L
            DayOutcome.CHEAT_DAY_CREDIT, DayOutcome.MACROS_MET -> (confirmed ?: 0L) + 1
        }
        return Streak(length, maxOf(best, length ?: 0L))
    }
}
