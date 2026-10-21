package com.cookie.notification.scheduler

import com.cookie.notification.domain.*
import com.cookie.notification.persistence.JdbcNotificationRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Component
class JdbcReminderStateLoader(private val jdbc:JdbcTemplate,private val repository:JdbcNotificationRepository,private val mapper:ObjectMapper) {
    fun load(userId:UUID,includeOccurrences:Boolean=true):ReminderPlanningState? {
        val settings=repository.readSettings(userId)?:return null
        val baselines=jdbc.query("SELECT food_started_at,water_started_at,last_water_reminder_at FROM settings WHERE user_id=?",{rs,_->
            Triple(rs.getTimestamp(1)?.toInstant(),rs.getTimestamp(2)?.toInstant(),rs.getTimestamp(3)?.toInstant())},userId).single()
        val waterSubmission=jdbc.query("SELECT last_water_submission_at FROM settings WHERE user_id=?",
            {rs,_->rs.getTimestamp(1)?.toInstant()},userId).single()
        val projections=jdbc.query("SELECT kind,entity_id,payload FROM event_projections WHERE user_id=? AND NOT deleted",{rs,_->
            Triple(rs.getString(1),rs.getString(2),mapper.readTree(rs.getString(3)))},userId)
        // A completely unsent occurrence cancelled by a corrected diary can be rearmed.
        // Daily cycles still expire with their local date; only FOOD can resume an expired job.
        // Actual submission evidence survives delivery-job metadata retention.
        val emitted=if(includeOccurrences) jdbc.query("""SELECT kind,cycle_key FROM reminder_occurrences o WHERE user_id=?
            AND NOT (kind IN ('FOOD','WEIGH','CALORIES_EXCEEDED','CALORIES_UNDER_TARGET')
                AND last_submitted_at IS NULL AND NOT EXISTS (
                    SELECT 1 FROM delivery_jobs j WHERE j.occurrence_id=o.id
                    AND NOT (j.status='CANCELLED' OR (o.kind='FOOD' AND j.status='EXPIRED'))))""",{rs,_->
            OccurrenceKey(ReminderKind.valueOf(rs.getString(1)),rs.getString(2))},userId).toSet() else emptySet()
        return ReminderPlanningState(settings,baselines.first,baselines.second,baselines.third,
            meals=projections.filter { it.first=="MEAL" }.map { MealSnapshot(it.second,requireNotNull(it.third.instantOrNull("eatenAt"))) },
            waterRecords=projections.filter { it.first=="WATER" }.mapNotNull { it.third.instantOrNull("lastConsumedAt") },
            weightMeasurements=projections.filter { it.first=="WEIGHT" }.mapNotNull { it.third.instantOrNull("measuredAt") },
            progress=projections.filter { it.first=="PROGRESS" }.map { row ->
                val node=row.third;val calories=node.path("calories");val water=node.path("water")
                ProgressSnapshot(LocalDate.parse(node.path("localDate").stringValue()),node.path("timezone").stringValue(),
                    calories.path("consumed").takeIf { it.isNumber }?.decimalValue(),calories.path("target").takeIf { it.isNumber }?.decimalValue(),
                    calories.path("unlimitedCalories").takeIf { it.isBoolean }?.booleanValue(),water.path("consumedMl").takeIf { it.isIntegralNumber }?.intValue(),
                    water.path("targetMl").takeIf { it.isIntegralNumber }?.intValue())
            },emitted=emitted,lastWaterSubmissionAt=waterSubmission)
    }
    private fun JsonNode.instantOrNull(field:String):Instant?=path(field).takeIf { it.isString }?.stringValue()?.let(Instant::parse)
}
