package com.cookie.user.persistence

import com.cookie.user.domain.UserFailure
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Component
class AcceptedGoalHistory(private val store: JdbcUserStore) {
    private val db get() = store.jdbc
    private val mapper get() = store.mapper
    private val legacy = RowMapper<JsonNode> { row, _ ->
        val targets = mapOf("calories" to "calorie_target", "proteinG" to "protein_g", "fatG" to "fat_g",
            "carbsG" to "carbs_g", "fiberG" to "fiber_g")
        val fields = mapOf(
            "id" to row.getObject("id", UUID::class.java), "revision" to row.getLong("revision"),
            "effectiveFrom" to row.getObject("effective_from", LocalDate::class.java),
            "outcome" to "LEGACY_POINT", "source" to row.getString("source"),
            "targetWeightKg" to row.getBigDecimal("target_weight_kg"), "weeklyChangeKg" to row.getBigDecimal("target_weight_change_per_week"),
        )
        json(fields + targets.mapValues { (_, column) -> mapOf("kind" to "LEGACY_POINT", "center" to row.getBigDecimal(column)) })
    }

    fun current(user: UUID): JsonNode = store.read(user) {
        val date = LocalDate.now(store.clock.withZone(ZoneId.of(db.queryForObject("SELECT timezone FROM profiles WHERE user_id=?", String::class.java, user)!!)))
        val latest = db.queryForObject("SELECT goal_revision FROM profiles WHERE user_id=?", Long::class.java, user)!!
        val current = db.query(
            "SELECT goal::text FROM accepted_goal_versions WHERE user_id=? AND effective_from<=? ORDER BY revision DESC LIMIT 1",
            { row, _ -> mapper.readTree(row.getString(1)) }, user, date,
        ).singleOrNull()
        current?.let { (it.deepCopy() as tools.jackson.databind.node.ObjectNode).put("latestRevision", latest) } ?: run {
            val archived = db.query(
                "SELECT * FROM nutrition_goal_versions WHERE user_id=? AND effective_from<=? ORDER BY effective_from DESC,revision DESC LIMIT 1",
                legacy, user, date,
            ).singleOrNull()
            json(mapOf(
                "id" to archived?.get("id"), "effectiveFrom" to archived?.get("effectiveFrom"),
                "latestRevision" to latest,
                "revision" to (archived?.path("revision")?.asLong() ?: 0L), "outcome" to "NO_CALORIE_GOAL",
                "reason" to if (archived != null) "LEGACY_REQUIRES_ASSESSMENT" else "NOT_ACCEPTED",
            ))
        }
    }

    fun page(user: UUID, cursor: String?, limit: Int): JsonNode = store.read(user) {
        if (limit !in 1..100) throw UserFailure("INVALID_REQUEST", 400)
        val before = cursor?.let { it.toLongOrNull()?.takeIf { number -> number > 0 } ?: throw UserFailure("INVALID_REQUEST", 400) }
            ?: Long.MAX_VALUE
        val accepted = db.query(
            "SELECT goal::text FROM accepted_goal_versions WHERE user_id=? AND revision<? ORDER BY revision DESC LIMIT ?",
            { row, _ -> mapper.readTree(row.getString(1)) }, user, before, limit + 1,
        )
        val remaining = limit + 1 - accepted.size
        val history = if (remaining > 0) db.query(
            "SELECT * FROM nutrition_goal_versions WHERE user_id=? AND revision<? ORDER BY revision DESC LIMIT ?",
            legacy, user, before, remaining,
        ) else emptyList()
        val combined = accepted + history
        json(mapOf("items" to combined.take(limit), "nextCursor" to
            if (combined.size > limit) combined[limit - 1].path("revision").asLong().toString() else null))
    }

    private fun json(value: Any): JsonNode = mapper.valueToTree(value)
}
