package com.cookie.health.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Period

enum class AssessmentSex { MALE, FEMALE }
enum class AssessmentActivity { INACTIVE, LOW_ACTIVE, ACTIVE, VERY_ACTIVE }
enum class MacroProfile { BALANCED, HIGH_PROTEIN, LOW_CARB, KETO }
enum class AssessmentOutcome { CALCULATED, NO_CALORIE_GOAL, MAINTENANCE_PREVIEW_AVAILABLE }
enum class AssessmentReason { OUTSIDE_POLICY_SCOPE, BELOW_CALORIE_FLOOR }
enum class TargetKind { RANGE, UPPER_LIMIT, AT_LEAST }

data class NutritionTarget(val kind: TargetKind, val low: BigDecimal?, val center: BigDecimal?, val high: BigDecimal?)
data class Assessment(
    val outcome: AssessmentOutcome,
    val reason: AssessmentReason?,
    val calories: NutritionTarget? = null,
    val proteinG: NutritionTarget? = null,
    val fatG: NutritionTarget? = null,
    val carbsG: NutritionTarget? = null,
    val fiberG: NutritionTarget? = null,
    val waterMl: Int = 1500,
)

data class AssessmentInputs(
    val sex: AssessmentSex,
    val birthDate: LocalDate,
    val heightCm: BigDecimal,
    val currentWeightKg: BigDecimal,
    val targetWeightKg: BigDecimal?,
    val weeklyChangeKg: BigDecimal,
    val activity: AssessmentActivity,
    val macroProfile: MacroProfile,
    val standardAdultPolicyApplies: Boolean,
)

class InvalidAssessment(val field: String) : RuntimeException("INVALID_ASSESSMENT")

object AssessmentPolicy {
    const val VERSION = "eer2023-v1"

    private class Equation(a: String, b: String, c: String, d: String) {
        private val intercept = a.toBigDecimal()
        private val age = b.toBigDecimal()
        private val height = c.toBigDecimal()
        private val weight = d.toBigDecimal()

        fun calculate(years: Int, heightCm: BigDecimal, weightKg: BigDecimal) =
            intercept + age * years.toBigDecimal() + height * heightCm + weight * weightKg
    }

    private val male = listOf(
        Equation("753.07", "-10.83", "6.50", "14.10"),
        Equation("581.47", "-10.83", "8.30", "14.94"),
        Equation("1004.82", "-10.83", "6.52", "15.91"),
        Equation("-517.88", "-10.83", "15.61", "19.11"),
    )
    private val female = listOf(
        Equation("584.90", "-7.01", "5.72", "11.71"),
        Equation("575.77", "-7.01", "6.60", "12.14"),
        Equation("710.25", "-7.01", "6.54", "12.34"),
        Equation("511.83", "-7.01", "9.07", "12.56"),
    )

    fun estimate(sex: AssessmentSex, activity: AssessmentActivity, years: Int, heightCm: BigDecimal, weightKg: BigDecimal): BigDecimal {
        val equations = when (sex) {
            AssessmentSex.MALE -> male
            AssessmentSex.FEMALE -> female
        }
        return equations[activity.ordinal].calculate(years, heightCm, weightKg)
    }

    fun assess(input: AssessmentInputs, assessmentDate: LocalDate): Assessment {
        validate(input, assessmentDate)
        val age = Period.between(input.birthDate, assessmentDate).years
        if (age < 19 || !input.standardAdultPolicyApplies) {
            return Assessment(AssessmentOutcome.NO_CALORIE_GOAL, AssessmentReason.OUTSIDE_POLICY_SCOPE)
        }
        val estimate = estimate(input.sex, input.activity, age, input.heightCm, input.currentWeightKg)
        return recommend(estimate, input.weeklyChangeKg, input.macroProfile)
    }

    private fun validate(input: AssessmentInputs, date: LocalDate) {
        if (input.birthDate.year !in 1..9999 || input.birthDate > date) throw InvalidAssessment("birthDate")
        if (input.heightCm !in "30".toBigDecimal().."300".toBigDecimal() || input.heightCm.stripTrailingZeros().scale() > 2) {
            throw InvalidAssessment("heightCm")
        }
        if (!validWeight(input.currentWeightKg)) throw InvalidAssessment("currentWeightKg")
        if (input.targetWeightKg != null && !validWeight(input.targetWeightKg)) throw InvalidAssessment("targetWeightKg")
        if (input.weeklyChangeKg.abs().compareTo("0.25".toBigDecimal()) != 0 && input.weeklyChangeKg.signum() != 0) {
            throw InvalidAssessment("weeklyChangeKg")
        }
        val direction = input.targetWeightKg?.compareTo(input.currentWeightKg)
        if ((direction ?: 0) != input.weeklyChangeKg.signum()) throw InvalidAssessment("targetWeightKg")
    }

    fun validWeight(weightKg: BigDecimal): Boolean =
        weightKg.signum() > 0 && weightKg <= "999.99".toBigDecimal() && weightKg.stripTrailingZeros().scale() <= 2

    internal fun recommend(estimate: BigDecimal, weeklyChangeKg: BigDecimal, profile: MacroProfile): Assessment {
        val adjustment = weeklyChangeKg * 1100.toBigDecimal()
        val center = (estimate + adjustment).setScale(0, RoundingMode.HALF_UP)
        if (center < 1600.toBigDecimal()) {
            val maintenanceAvailable = weeklyChangeKg.signum() < 0 &&
                estimate.setScale(0, RoundingMode.HALF_UP) >= 1600.toBigDecimal()
            return Assessment(
                if (maintenanceAvailable) AssessmentOutcome.MAINTENANCE_PREVIEW_AVAILABLE else AssessmentOutcome.NO_CALORIE_GOAL,
                AssessmentReason.BELOW_CALORIE_FLOOR,
            )
        }
        val low = center - 100.toBigDecimal()
        val high = center + 100.toBigDecimal()
        val energy = NutritionTarget(TargetKind.RANGE, low, center, high)
        fun range(grams: (BigDecimal) -> BigDecimal): NutritionTarget {
            val (minimum, midpoint, maximum) = listOf(low, center, high).map { grams(it).setScale(2, RoundingMode.HALF_UP) }
            return NutritionTarget(TargetKind.RANGE, minimum, midpoint, maximum)
        }
        val proteinShare = when (profile) {
            MacroProfile.BALANCED, MacroProfile.KETO -> "0.20"
            MacroProfile.HIGH_PROTEIN, MacroProfile.LOW_CARB -> "0.25"
        }.toBigDecimal()
        val carbohydrateCap = when (profile) {
            MacroProfile.LOW_CARB -> minOf(center * "0.05".toBigDecimal(), 120.toBigDecimal())
            MacroProfile.KETO -> 30.toBigDecimal()
            else -> null
        }
        val protein = range { it * proteinShare / 4.toBigDecimal() }
        val fat = range {
            val caloriesFromFat = if (carbohydrateCap == null) it * "0.30".toBigDecimal()
                else it * (BigDecimal.ONE - proteinShare) - carbohydrateCap * 4.toBigDecimal()
            caloriesFromFat.divide(9.toBigDecimal(), MathContext.DECIMAL128)
        }
        val carbs = if (carbohydrateCap != null) NutritionTarget(TargetKind.UPPER_LIMIT, null, null, carbohydrateCap.setScale(2))
            else range { it * (BigDecimal.ONE - proteinShare - "0.30".toBigDecimal()) / 4.toBigDecimal() }
        val fiber = (center * "0.014".toBigDecimal()).setScale(0, RoundingMode.HALF_UP)
        return Assessment(
            AssessmentOutcome.CALCULATED, null, energy, protein, fat, carbs,
            NutritionTarget(TargetKind.AT_LEAST, fiber, null, null),
        )
    }
}
