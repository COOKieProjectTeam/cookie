package com.cookie.user.transport

import com.cookie.user.application.UserService
import com.cookie.user.domain.*
import com.cookie.user.generated.api.ProfileApi
import com.cookie.user.generated.model.*
import com.cookie.user.security.IdentityTokens
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import com.cookie.user.generated.model.Profile as ProfileDto
import com.cookie.user.generated.model.NutritionGoal as GoalDto
import com.cookie.user.generated.model.HydrationGoal as WaterDto
import com.cookie.user.generated.model.GoalHistoryPage as HistoryDto

@Controller
class ProfileController(private val service:UserService,private val tokens:IdentityTokens,private val request:HttpServletRequest):ProfileApi {
    private fun account():UUID {
        val headers=request.getHeaders("Authorization").toList()
        if(headers.size!=1 || !headers.single().startsWith("Bearer ",true)) throw UserFailure("INVALID_ACCESS_TOKEN",401)
        return tokens.verify(headers.single().substring(7))
    }
    private fun <T> ok(value:T)=ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
    override fun getProfile()=ok(service.profile(account()).dto())
    override fun updateProfile(idempotencyKey:UUID,profilePatch:ProfilePatch):ResponseEntity<ProfileDto> {
        val p=profilePatch
        return ok(service.update(account(),idempotencyKey,ProfileChange(p.version,p.heightCm?.let(BigDecimal::valueOf),
            p.activityLevel?.value?.let(com.cookie.user.domain.ActivityLevel::valueOf),p.timezone,p.locale,
            p.dietPreferences?.map { com.cookie.user.domain.DietType.valueOf(it.value) }?.sortedBy { it.name }?.toSet(),
            p.allergies?.map { com.cookie.user.domain.Allergen.valueOf(it.value) }?.sortedBy { it.name }?.toSet())).dto())
    }
    override fun getCurrentGoal()=ok(service.currentGoal(account()).dto())
    override fun setCurrentGoal(idempotencyKey:UUID,nutritionGoalInput:NutritionGoalInput):ResponseEntity<GoalDto> {
        val p=nutritionGoalInput
        return ok(service.setGoal(account(),idempotencyKey,NutritionGoalCommand(Nutrients(p.calories,p.proteinG,p.fatG,p.carbsG,p.fiberG),
            p.targetWeightKg?.let(BigDecimal::valueOf),p.targetWeightChangeKgPerWeek?.let(BigDecimal::valueOf))).dto())
    }
    override fun getGoalHistory(cursor:String?,limit:Int):ResponseEntity<HistoryDto> {
        val page=service.goalHistory(account(),cursor,limit);return ok(HistoryDto(page.items.map { it.dto() },page.nextCursor))
    }
    override fun getHydrationGoal()=ok(service.hydration(account()).dto())
    override fun setHydrationGoal(idempotencyKey:UUID,hydrationGoalInput:HydrationGoalInput)=ok(service.setHydration(account(),idempotencyKey,hydrationGoalInput.targetMl).dto())
    override fun getDailyGoalOverride(date:LocalDate)=ok(service.override(account(),date).dto())
    override fun setDailyGoalOverride(date:LocalDate,idempotencyKey:UUID,dailyGoalOverrideInput:DailyGoalOverrideInput):ResponseEntity<DailyGoalOverride> {
        val p=dailyGoalOverrideInput
        return ok(service.setOverride(account(),idempotencyKey,date,OverrideCommand(OverrideType.valueOf(p.type.value),p.calories,p.proteinG,p.fatG,p.carbsG,p.fiberG,p.unlimitedCalories)).dto())
    }
    override fun deleteDailyGoalOverride(date:LocalDate,idempotencyKey:UUID):ResponseEntity<Unit> {
        service.deleteOverride(account(),idempotencyKey,date);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }
}
fun com.cookie.user.domain.Profile.dto()=ProfileDto(sex?.name?.let(com.cookie.user.generated.model.Sex::forValue),birthDate,heightCm?.toDouble(),
    activityLevel?.name?.let(com.cookie.user.generated.model.ActivityLevel::forValue),timezone,locale,
    dietPreferences.map { com.cookie.user.generated.model.DietType.forValue(it.name) }.toSet(),allergies.map { com.cookie.user.generated.model.Allergen.forValue(it.name) }.toSet(),onboardingCompleted,version,userId)
fun com.cookie.user.domain.NutritionGoal.dto()=GoalDto(value.nutrients.calories,value.nutrients.proteinG,value.nutrients.fatG,value.nutrients.carbsG,value.nutrients.fiberG,
    id,effectiveFrom,GoalDto.Source.forValue(source),revision,value.targetWeightKg?.toDouble(),value.targetWeightChangeKgPerWeek?.toDouble())
fun com.cookie.user.domain.HydrationGoal.dto()=WaterDto(id,effectiveFrom,revision,targetMl)
fun DailyOverride.dto()=DailyGoalOverride(id,localDate,DailyGoalOverride.Type.forValue(value.type.name),value.unlimitedCalories,value.calories,value.proteinG,value.fatG,value.carbsG,value.fiberG)
