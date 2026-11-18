package com.cookie.health.domain

import java.time.LocalDate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

class AssessmentPolicyTest {
    @Test
    fun eer2023AdultGolden8() {
        val expected = listOf("2520.17", "2713.37", "2902.02", "3148.62", "2166.70", "2337.27", "2475.55", "2722.63")
        val actual = AssessmentSex.entries.flatMap { sex ->
            AssessmentActivity.entries.map {
                AssessmentPolicy.estimate(sex, it, 30, 170.toBigDecimal(), 70.toBigDecimal())
            }
        }
        assertEquals(expected.map(String::toBigDecimal), actual)
    }

    @Test
    fun exactFloorAndExplicitMaintenance() {
        fun recommend(eer: String, rate: String) =
            AssessmentPolicy.recommend(eer.toBigDecimal(), rate.toBigDecimal(), MacroProfile.BALANCED)
        assertEquals(1500.toBigDecimal(), recommend("1875", "-0.25").calories?.low)
        assertEquals(AssessmentOutcome.MAINTENANCE_PREVIEW_AVAILABLE, recommend("1800", "-0.25").outcome)
        assertEquals(1700.toBigDecimal(), recommend("1800", "0").calories?.low)
        assertEquals(AssessmentOutcome.NO_CALORIE_GOAL, recommend("1599.49", "0").outcome)
        assertEquals(1500.toBigDecimal(), recommend("1599.50", "0").calories?.low)
        assertEquals(1500.toBigDecimal(), recommend("1325", "+0.25").calories?.low)
    }

    @Test
    fun macroCapsAndFiber() {
        val low = AssessmentPolicy.recommend(2000.toBigDecimal(), 0.toBigDecimal(), MacroProfile.LOW_CARB)
        assertEquals("125.00".toBigDecimal(), low.proteinG?.center)
        assertEquals("122.22".toBigDecimal(), low.fatG?.center)
        assertEquals(NutritionTarget(TargetKind.UPPER_LIMIT, null, null, "100.00".toBigDecimal()), low.carbsG)
        val keto = AssessmentPolicy.recommend(2000.toBigDecimal(), 0.toBigDecimal(), MacroProfile.KETO)
        assertEquals("100.00".toBigDecimal(), keto.proteinG?.center)
        assertEquals("164.44".toBigDecimal(), keto.fatG?.center)
        assertEquals("30.00".toBigDecimal(), keto.carbsG?.high)
        assertNull(keto.carbsG?.low)
        val balanced = AssessmentPolicy.recommend(2300.toBigDecimal(), 0.toBigDecimal(), MacroProfile.BALANCED)
        assertEquals(32.toBigDecimal(), balanced.fiberG?.low)
        assertEquals(1500, balanced.waterMl)
    }

    @Test
    fun completedYearsDetermineEligibility() {
        val input = AssessmentInputs(
            AssessmentSex.MALE, LocalDate.of(2007, 11, 18), 170.toBigDecimal(), 70.toBigDecimal(), null,
            0.toBigDecimal(), AssessmentActivity.INACTIVE, MacroProfile.BALANCED, true,
        )
        assertEquals(AssessmentOutcome.NO_CALORIE_GOAL, AssessmentPolicy.assess(input, LocalDate.of(2026, 11, 17)).outcome)
        assertEquals(AssessmentOutcome.CALCULATED, AssessmentPolicy.assess(input, LocalDate.of(2026, 11, 18)).outcome)
    }
}
