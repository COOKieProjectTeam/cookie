package com.cookie.user.domain

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class UserFailure(val code: String, val status: Int) : RuntimeException(code)
fun invalid(): Nothing = throw UserFailure("INVALID_REQUEST", 400)
fun ensure(value: Boolean) { if (!value) invalid() }

enum class Sex { MALE, FEMALE }
enum class ActivityLevel { LOW, MODERATE, HIGH }
enum class DietType { BALANCED, HIGH_PROTEIN, VEGETARIAN, VEGAN, PESCATARIAN, LOW_CARB, KETO, MEDITERRANEAN, GLUTEN_FREE, LACTOSE_FREE }
enum class Allergen { GLUTEN, CRUSTACEANS, EGGS, FISH, PEANUTS, SOY, MILK, TREE_NUTS, CELERY, MUSTARD, SESAME, SULPHITES, LUPIN, MOLLUSCS }

data class Profile(
    val userId: UUID, val sex: Sex?, val birthDate: LocalDate?, val heightCm: BigDecimal?,
    val activityLevel: ActivityLevel?, val timezone: String, val locale: String,
    val dietPreferences: Set<DietType>, val allergies: Set<Allergen>,
    val onboardingCompleted: Boolean, val version: Long,
)
data class ProfileChange(
    val version: Long, val heightCm: BigDecimal? = null, val activityLevel: ActivityLevel? = null,
    val timezone: String? = null, val locale: String? = null,
    val dietPreferences: Set<DietType>? = null, val allergies: Set<Allergen>? = null,
) {
    fun validate() {
        ensure(version > 0)
        heightCm?.let { ensure(it >= BigDecimal("30") && it <= BigDecimal("300") && it.stripTrailingZeros().scale() <= 2) }
        timezone?.let { ensure(it in ZoneId.getAvailableZoneIds()) }
        locale?.let { ensure(it in setOf("ru", "en")) }
        ensure(heightCm != null || activityLevel != null || timezone != null || locale != null || dietPreferences != null || allergies != null)
    }
}
data class Nutrients(val calories: BigDecimal, val proteinG: BigDecimal, val fatG: BigDecimal,
                     val carbsG: BigDecimal, val fiberG: BigDecimal) {
    fun validate() { listOf(calories, proteinG, fatG, carbsG, fiberG).forEach {
        ensure(it.signum() >= 0 && it <= BigDecimal("999999.99") && it.stripTrailingZeros().scale() <= 2)
    } }
}
data class NutritionGoalCommand(val nutrients: Nutrients, val targetWeightKg: BigDecimal? = null,
                                val targetWeightChangeKgPerWeek: BigDecimal? = null) {
    fun validate() {
        nutrients.validate()
        targetWeightKg?.let { ensure(it >= BigDecimal("1") && it <= BigDecimal("999.99") && it.stripTrailingZeros().scale() <= 2) }
        targetWeightChangeKgPerWeek?.let { ensure(it.abs() <= BigDecimal("99.99") && it.stripTrailingZeros().scale() <= 2) }
    }
}
data class NutritionGoal(val id: UUID, val effectiveFrom: LocalDate, val revision: Long,
                         val value: NutritionGoalCommand, val source: String = "MANUAL")
data class HydrationGoal(val id: UUID, val effectiveFrom: LocalDate, val revision: Long, val targetMl: Int)
data class GoalPage(val items: List<NutritionGoal>, val nextCursor: String?)
enum class OverrideType { CUSTOM, CHEAT_DAY }
data class OverrideCommand(val type: OverrideType, val calories: BigDecimal? = null,
                           val proteinG: BigDecimal? = null, val fatG: BigDecimal? = null,
                           val carbsG: BigDecimal? = null, val fiberG: BigDecimal? = null,
                           val unlimitedCalories: Boolean = false) {
    fun validate() {
        val values = listOf(calories, proteinG, fatG, carbsG, fiberG)
        values.filterNotNull().forEach { ensure(it.signum() >= 0 && it <= BigDecimal("999999.99") && it.stripTrailingZeros().scale() <= 2) }
        when(type) {
            OverrideType.CUSTOM -> ensure(calories != null && !unlimitedCalories)
            OverrideType.CHEAT_DAY -> ensure(unlimitedCalories && values.all { it == null })
        }
    }
}
data class DailyOverride(val id: UUID, val localDate: LocalDate, val value: OverrideCommand)
