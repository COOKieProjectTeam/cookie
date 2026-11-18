package com.cookie.progress.domain

import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AchievementsTest {
    private fun range(low: String, high: String) =
        GoalBand(GoalKind.RANGE, low.toBigDecimal(), high = high.toBigDecimal())

    private val goal = NutritionGoal(
        calories = range("1800", "2200"),
        protein = range("80", "110"),
        fat = range("55", "75"),
        carbs = GoalBand(GoalKind.UPPER_LIMIT, high = "100".toBigDecimal()),
        fiber = GoalBand(GoalKind.AT_LEAST, low = "30".toBigDecimal()),
    )
    private val food = FoodFacts(
        mealCount = 1,
        calories = "1800.000".toBigDecimal(),
        protein = "110.00".toBigDecimal(),
        fat = "55.00".toBigDecimal(),
        carbs = "100.000".toBigDecimal(),
    )
    private val missedDinner = food.copy(calories = "2200.0001".toBigDecimal())
    private val empty = FoodFacts(0, null, null, null, null)

    @Test
    fun onlyClosingChangesTheConfirmedStreakAndRecord() {
        val yesterday = Streak(7, 7)
        val changes = listOf(empty, food, missedDinner, food)
        assertEquals(listOf(7L, 8L, 7L, 8L), changes.map { yesterday.provisional(assessDay(it, goal, false)) })
        assertEquals(7L, yesterday.best)
        assertEquals(Streak(8, 8), yesterday.close(assessDay(food, goal, false)))
        assertEquals(Streak(0, 7), yesterday.close(assessDay(missedDinner, goal, false)))
        assertEquals(Streak(0, 7), yesterday.close(assessDay(empty, goal, false)))
    }

    @Test
    fun allFourBoundsAreInclusiveAndComparedBeforeRounding() {
        val hit = Achievement(DayOutcome.MACROS_MET, 1, true)
        assertEquals(hit, assessDay(food, goal, false))
        assertEquals(hit, assessDay(food.copy(carbs = BigDecimal.ZERO, calories = "2200".toBigDecimal()), goal, false))
        val outside = listOf(
            missedDinner,
            food.copy(protein = "79.9999".toBigDecimal()),
            food.copy(fat = "54.9999".toBigDecimal()),
            food.copy(carbs = "100.0001".toBigDecimal()),
        )
        outside.forEach {
            assertEquals(Achievement(DayOutcome.OUT_OF_TARGET, 0, false), assessDay(it, goal, false))
        }
    }

    @Test
    fun everyMacroMustBeKnownAndAtLeastOneMealMustExist() {
        val insufficient = Achievement(DayOutcome.INSUFFICIENT_DATA, 0, null)
        val missing = listOf(
            food.copy(mealCount = 0),
            food.copy(calories = null),
            food.copy(protein = null),
            food.copy(fat = null),
            food.copy(carbs = null),
        )
        missing.forEach { assertEquals(insufficient, assessDay(it, goal, false)) }
        assertEquals(DayOutcome.OUT_OF_TARGET, assessDay(food.copy(calories = BigDecimal.ZERO), goal, false).outcome)
    }

    @Test
    fun cheatDayProtectsAMissButCountsOnceWhenMacrosAreMet() {
        val streak = Streak(7, 10)
        val protectedDay = assessDay(empty, goal, true)
        assertEquals(Achievement(DayOutcome.CHEAT_DAY_CREDIT, 1, null), protectedDay)
        assertEquals(Achievement(DayOutcome.CHEAT_DAY_CREDIT, 1, false), assessDay(missedDinner, goal, true))
        assertEquals(8L, streak.provisional(protectedDay))
        assertEquals(7L, streak.provisional(assessDay(empty, goal, false)))
        val alreadyMet = assessDay(food, goal, true)
        assertEquals(Achievement(DayOutcome.MACROS_MET, 1, true), alreadyMet)
        assertEquals(Streak(8, 10), streak.close(alreadyMet))
    }

    @Test
    fun absentGoalAndFirstApplicableDayRemainDistinct() {
        val noGoal = assessDay(food, null, true)
        assertEquals(Achievement(DayOutcome.NOT_APPLICABLE, 0, null), noGoal)
        assertNull(Streak(7, 9).provisional(noGoal))
        assertEquals(Streak(null, 9), Streak(7, 9).close(noGoal))
        val first = Streak()
        assertEquals(0L, first.provisional(assessDay(empty, goal, false)))
        assertEquals(1L, first.provisional(assessDay(food, goal, false)))
        assertEquals(Streak(1, 1), first.close(assessDay(food, goal, false)))
    }
}
