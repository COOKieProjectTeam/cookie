package com.cookie.health.transport

import com.cookie.health.domain.AssessmentActivity
import com.cookie.health.domain.AssessmentInputs
import com.cookie.health.domain.AssessmentPolicy
import com.cookie.health.domain.AssessmentSex
import com.cookie.health.domain.MacroProfile
import com.cookie.health.domain.NutritionTarget
import com.cookie.health.generated.model.HealthAssessmentInput
import com.cookie.health.generated.model.HealthAssessmentPreview
import com.cookie.health.generated.model.HealthNutritionTarget
import java.time.LocalDate

internal fun calculateAssessment(input: HealthAssessmentInput, date: LocalDate): HealthAssessmentPreview {
    val assessment = AssessmentPolicy.assess(
        AssessmentInputs(
            AssessmentSex.valueOf(input.sex.value), input.birthDate, input.heightCm, input.currentWeightKg,
            input.targetWeightKg, input.weeklyChangeKg, AssessmentActivity.valueOf(input.activity.value),
            MacroProfile.valueOf(input.macroProfile.value), input.standardAdultPolicyApplies,
        ), date,
    )
    return HealthAssessmentPreview(
        outcome = HealthAssessmentPreview.Outcome.forValue(assessment.outcome.name),
        policyVersion = AssessmentPolicy.VERSION,
        assessmentDate = date,
        waterMl = assessment.waterMl,
        reason = assessment.reason?.let { HealthAssessmentPreview.Reason.forValue(it.name) },
        calories = assessment.calories?.dto(),
        proteinG = assessment.proteinG?.dto(),
        fatG = assessment.fatG?.dto(),
        carbsG = assessment.carbsG?.dto(),
        fiberG = assessment.fiberG?.dto(),
    )
}

private fun NutritionTarget.dto() = HealthNutritionTarget(
        kind = HealthNutritionTarget.Kind.forValue(kind.name),
        low = low, center = center, high = high,
    )
