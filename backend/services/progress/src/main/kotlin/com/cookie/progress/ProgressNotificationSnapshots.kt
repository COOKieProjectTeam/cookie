package com.cookie.progress

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

internal class ProgressNotificationSnapshots(private val store: ProgressStore, private val mapper: ObjectMapper) {
    /** The calendar owns the transaction and holds the exclusive account lock. */
    fun emit(user: UUID, day: CalendarDay) {
        val date = day.date.toString()
        val zone = day.zone.id
        val food = store.latest(user, "nutrition.day.changed", date)?.payload
        val water = store.latest(user, "nutrition.water.changed", date)?.payload
        val goal = day.goalSequence?.let { seq ->
            store.jdbc.query("SELECT * FROM source_facts WHERE user_id=? AND owner='user' AND source_seq=?",
                store.factRow, user, seq).singleOrNull()?.let(::GoalRevision)
        }
        val hydration = store.history(user, "user.hydration_goal.changed").map { it.first.payload }
            .filter { it.date("effectiveFrom") <= day.date }.maxByOrNull { it.positive("revision") }
        val override = store.latest(user, "user.goal_override.changed", date)?.payload
        val calories = goal?.nutrition?.calories
        val pending = listOf("nutrition", "user").any { store.cursor(user, it) < store.highEvidence(user, it) }
        val caloriesKnown = !pending && food != null && food.count("mealCount") > 0 && food.belongsTo(zone)
        val waterKnown = !pending && water != null && water.belongsTo(zone)
        val payload = mapper.valueToTree<JsonNode>(mapOf(
            "userId" to user, "localDate" to date, "timezone" to zone,
            "calories" to mapOf(
                "consumed" to if (caloriesKnown) food.path("nutritionTotals").quantity("calories") else null,
                "goalState" to if (calories == null) "NO_CALORIE_GOAL" else "RANGE",
                "goalId" to goal?.payload?.string("id"), "goalRevision" to goal?.version,
                "low" to calories?.low, "high" to calories?.high,
                "cheatDay" to (override?.path("override")?.stringValue() == "CHEAT_DAY"),
            ),
            "water" to mapOf(
                "consumedMl" to if (waterKnown) water.count("totalMl") else null,
                "targetMl" to hydration?.count("targetMl"),
            ),
        ))
        val version = store.jdbc.query(
            """INSERT INTO notification_versions(user_id,local_date,zone_id,version,payload) VALUES (?,?,?,1,?::jsonb)
               ON CONFLICT(user_id,local_date,zone_id) DO UPDATE SET version=notification_versions.version+1,payload=EXCLUDED.payload
               WHERE notification_versions.payload IS DISTINCT FROM EXCLUDED.payload RETURNING version""",
            { row, _ -> row.getLong(1) }, user, day.date, zone, payload.toString(),
        ).singleOrNull() ?: return
        store.enqueue(user, "progress.day.updated", 2, "progress_day", "$user:$date:$zone", payload, version)
    }

    private fun JsonNode.belongsTo(zone: String): Boolean {
        val origin = path("zoneProvenance")
        return origin.path("state").stringValue() == "EMPTY" ||
            (origin.path("state").stringValue() == "SINGLE" && origin.path("timezone").stringValue() == zone)
    }
}
