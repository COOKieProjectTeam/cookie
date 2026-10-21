package com.cookie.notification.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class ReminderPlannerTest {
    private val noon=Instant.parse("2026-09-17T12:00:00Z")
    private val start=Instant.parse("2026-09-17T00:00:00Z")
    private fun state()=ReminderPlanningState(NotificationSettings(UUID.randomUUID(),waterEnabled=false,weighEnabled=false,goalWarningEnabled=false,
        timezone="UTC",quietStart=LocalTime.MIDNIGHT,quietEnd=LocalTime.MIDNIGHT,schedulingStartedAt=start),start,start,null)
    private fun progress(consumed:Int?,target:Int?=2000,unlimited:Boolean?=false,date:LocalDate=LocalDate.of(2026,9,17),zone:String="UTC",water:Int?=null,waterGoal:Int?=null)=
        ProgressSnapshot(date,zone,consumed?.toBigDecimal(),target?.toBigDecimal(),unlimited,water,waterGoal)
    private fun food(state:ReminderPlanningState,now:Instant=noon)=ReminderPlanner.plan(state,now).due.filter { it.kind==ReminderKind.FOOD }

    @Test fun `food once until next meal and editing same meal does not resend`() {
        val meal=MealSnapshot("a",noon.minusSeconds(14400));val initial=state().copy(meals=listOf(meal))
        val emitted=food(initial).single()
        val sent=initial.copy(emitted=setOf(OccurrenceKey(emitted.kind,emitted.cycle)))
        assertThat(food(sent,noon.plusSeconds(7200))).isEmpty()
        assertThat(food(sent.copy(meals=listOf(meal.copy(eatenAt=noon.minusSeconds(18000)))))).isEmpty()
        assertThat(food(sent.copy(meals=listOf(meal,MealSnapshot("b",noon.plusSeconds(1)))),noon.plusSeconds(14401))).hasSize(1)
    }
    @Test fun `meal correction reschedules pending reminder and deletion finds previous meal`() {
        val a=MealSnapshot("a",noon.minusSeconds(14400));val b=MealSnapshot("b",noon.minusSeconds(3600))
        assertThat(food(state().copy(meals=listOf(a,b)))).isEmpty()
        assertThat(food(state().copy(meals=listOf(a)))).hasSize(1)
        val old=food(state().copy(meals=listOf(a))).single()
        val edited=state().copy(meals=listOf(a.copy(eatenAt=noon.minusSeconds(3600))))
        assertThat(ReminderPlanner.isEligible(edited,old.kind,old.cycle,old.scheduledAt,noon)).isFalse()
        assertThat(ReminderPlanner.plan(edited,noon).nextWakeup).isEqualTo(noon.plusSeconds(10800))
    }
    @Test fun `water starts two hours after latest record and coalesces overdue ticks`() {
        val initial=state().let { it.copy(settings=it.settings.copy(foodEnabled=false,waterEnabled=true)) }
        val overdue=ReminderPlanner.plan(initial,noon)
        assertThat(overdue.due.map { it.kind }).containsExactly(ReminderKind.WATER)
        val next=initial.copy(lastWaterReminderAt=noon)
        assertThat(ReminderPlanner.plan(next,noon).due).isEmpty()
        assertThat(ReminderPlanner.plan(next,noon).nextWakeup).isEqualTo(noon.plusSeconds(7200))
        assertThat(ReminderPlanner.plan(next.copy(waterRecords=listOf(noon.plusSeconds(3600))),noon.plusSeconds(7200)).due).isEmpty()
    }
    @Test fun `delayed actual water submission moves the next reminder beyond its reservation time`() {
        val initial=state().let { it.copy(settings=it.settings.copy(foodEnabled=false,waterEnabled=true),
            lastWaterReminderAt=noon.minusSeconds(7200),lastWaterSubmissionAt=noon.minusSeconds(60)) }
        assertThat(ReminderPlanner.plan(initial,noon).due).isEmpty()
        assertThat(ReminderPlanner.plan(initial,noon).nextWakeup).isEqualTo(noon.plusSeconds(7140))
        assertThat(ReminderPlanner.plan(initial,noon.plusSeconds(7140)).due.map { it.kind }).containsExactly(ReminderKind.WATER)
    }
    @Test fun `water-only initial and quiet-hour deadlines are preserved before creating delivery expiry`() {
        val initial=state().let { it.copy(settings=it.settings.copy(foodEnabled=false,waterEnabled=true,schedulingStartedAt=noon),waterStartedAt=noon) }
        assertThat(ReminderPlanner.plan(initial,noon).nextWakeup).isEqualTo(noon.plusSeconds(7200))
        val overnight=initial.copy(settings=initial.settings.copy(quietStart=LocalTime.of(23,0),quietEnd=LocalTime.of(8,0)),waterRecords=listOf(Instant.parse("2026-09-17T22:30:00Z")))
        val midnight=Instant.parse("2026-09-18T00:00:00Z")
        assertThat(ReminderPlanner.plan(overnight,midnight).nextWakeup).isEqualTo(Instant.parse("2026-09-18T08:00:00Z"))
        assertThat(ReminderPlanner.plan(overnight,Instant.parse("2026-09-18T08:00:00Z")).due.single().kind).isEqualTo(ReminderKind.WATER)
    }
    @Test fun `known reached water goal suppresses but absent stale or mismatched goals do not`() {
        val initial=state().let { it.copy(settings=it.settings.copy(foodEnabled=false,waterEnabled=true)) }
        assertThat(ReminderPlanner.plan(initial.copy(progress=listOf(progress(null,water=2000,waterGoal=2000))),noon).due).isEmpty()
        assertThat(ReminderPlanner.plan(initial.copy(progress=listOf(progress(null,water=0,waterGoal=null))),noon).due).hasSize(1)
        assertThat(ReminderPlanner.plan(initial.copy(progress=listOf(progress(null,zone="Europe/London",water=2000,waterGoal=2000))),noon).due).hasSize(1)
        assertThat(ReminderPlanner.plan(initial.copy(progress=listOf(progress(null,date=LocalDate.of(2026,9,16),water=2000,waterGoal=2000))),noon).due).hasSize(1)
    }
    @Test fun `weigh uses measurement day in selected timezone and does not catch up before first activation`() {
        val initial=state().let { it.copy(settings=it.settings.copy(foodEnabled=false,weighEnabled=true,timezone="Europe/London")) }
        val eight=Instant.parse("2026-09-17T07:00:00Z")
        assertThat(ReminderPlanner.plan(initial,eight).due.map { it.kind }).containsExactly(ReminderKind.WEIGH)
        assertThat(ReminderPlanner.plan(initial.copy(weightMeasurements=listOf(Instant.parse("2026-09-16T23:30:00Z"))),eight).due).isEmpty()
        assertThat(ReminderPlanner.plan(initial.copy(weightMeasurements=listOf(Instant.parse("2026-09-16T22:30:00Z"))),eight).due).hasSize(1)
        assertThat(ReminderPlanner.plan(initial.copy(settings=initial.settings.copy(schedulingStartedAt=noon)),noon).due).isEmpty()
    }
    @Test fun `goal rules are once per type per current day with strict thresholds and cheat exemption`() {
        val evening=Instant.parse("2026-09-17T20:00:00Z")
        val initial=state().let { it.copy(settings=it.settings.copy(foodEnabled=false,goalWarningEnabled=true)) }
        assertThat(ReminderPlanner.plan(initial.copy(progress=listOf(progress(1600))),evening).due).isEmpty()
        val under=ReminderPlanner.plan(initial.copy(progress=listOf(progress(1599))),evening).due.single()
        assertThat(under.kind).isEqualTo(ReminderKind.CALORIES_UNDER_TARGET)
        assertThat(ReminderPlanner.plan(initial.copy(progress=listOf(progress(2100))),evening).due.single().kind).isEqualTo(ReminderKind.CALORIES_EXCEEDED)
        assertThat(ReminderPlanner.plan(initial.copy(progress=listOf(progress(2100,unlimited=true))),evening).due).isEmpty()
        assertThat(ReminderPlanner.plan(initial.copy(progress=listOf(progress(null))),evening).due).isEmpty()
        assertThat(ReminderPlanner.plan(initial.copy(progress=listOf(progress(1599)),emitted=setOf(OccurrenceKey(under.kind,under.cycle))),evening).due).isEmpty()
        assertThat(ReminderPlanner.plan(initial.copy(progress=listOf(progress(1599))),evening.plusSeconds(86400)).due).isEmpty()
    }
    @Test fun `no timezone means no schedules and first timezone grants timers a fresh baseline`() {
        val initial=state().let { it.copy(settings=it.settings.copy(timezone=null,schedulingStartedAt=null,waterEnabled=true,weighEnabled=true)) }
        assertThat(ReminderPlanner.plan(initial,noon)).isEqualTo(ReminderPlan(emptyList(),null))
        val activated=initial.copy(settings=initial.settings.apply(SettingsPatch(timezone="UTC"),noon),foodStartedAt=noon,waterStartedAt=noon)
        assertThat(ReminderPlanner.plan(activated,noon).due).isEmpty()
        assertThat(ReminderPlanner.plan(activated,noon).nextWakeup).isEqualTo(noon.plusSeconds(7200))
    }
    @Test fun `quiet hours defer persistent reminders but never move daily warnings to tomorrow`() {
        val evening=Instant.parse("2026-09-17T23:30:00Z")
        val initial=state().let { it.copy(settings=it.settings.copy(waterEnabled=true,goalWarningEnabled=true,quietStart=LocalTime.of(23,0),quietEnd=LocalTime.of(8,0)),progress=listOf(progress(1000))) }
        val plan=ReminderPlanner.plan(initial,evening)
        assertThat(plan.due).isEmpty()
        assertThat(plan.nextWakeup).isEqualTo(Instant.parse("2026-09-18T00:00:00Z"))
        val morning=ReminderPlanner.plan(initial,Instant.parse("2026-09-18T08:00:00Z"))
        assertThat(morning.due.map { it.kind }).containsExactlyInAnyOrder(ReminderKind.FOOD,ReminderKind.WATER)
    }
    @Test fun `outage catchup emits at most one occurrence per type and never old daily dates`() {
        val later=noon.plusSeconds(86400*7L)
        val initial=state().let { it.copy(settings=it.settings.copy(waterEnabled=true,weighEnabled=true,goalWarningEnabled=true),progress=listOf(progress(100))) }
        val plan=ReminderPlanner.plan(initial,later)
        assertThat(plan.due.map { it.kind }).doesNotHaveDuplicates()
        assertThat(plan.due).allMatch { it.kind!=ReminderKind.CALORIES_UNDER_TARGET }
        assertThat(plan.due.first { it.kind==ReminderKind.WEIGH }.cycle).isEqualTo("2026-09-24")
    }
}
