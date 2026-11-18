package com.cookie.progress

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal data class SourceFact(
    val owner: String,
    val kind: String,
    val key: String,
    val version: Long,
    val sequence: Long?,
    val payload: JsonNode,
)

internal data class SourceGroup(val owner: String, val first: Long, val last: Long, val at: Instant, val endLsn: String)
internal data class SourceCut(val owner: String, val boundary: Instant, val before: Long, val through: Long, val endLsn: String)

internal class InvalidProgressEvent(val reason: String) : RuntimeException(reason)

internal class ProgressInbox(private val store: ProgressStore, private val mapper: ObjectMapper) {
    fun receive(subject: String, bytes: ByteArray) {
        val input = parse(subject, bytes)
        store.transactions.executeWithoutResult {
            store.lock(input.user)
            if (!store.acceptInbox(input.id) || store.deleted(input.user)) return@executeWithoutResult
            if (input.change is Deletion) {
                store.erase(input.user, input.change.request, input.change.at)
                return@executeWithoutResult
            }
            store.ensureAccount(input.user)
            when (val change = input.change) {
                is Fact -> {
                    if (input.id == change.original || store.acceptInbox(change.original)) store.recordFact(input.user, change.fact, change.original)
                    change.fact.sequence?.let { store.advance(input.user, change.fact.owner) }
                }
                is Group -> {
                    store.recordGroup(input.user, change.group)
                    store.advance(input.user, change.group.owner)
                }
                is Cut -> store.recordCut(input.user, change.cut)
                is Deletion -> error("Deletion was already handled")
            }
            store.wake(input.user)
        }
    }

    private sealed interface Change
    private data class Deletion(val request: UUID, val at: Instant) : Change
    private data class Fact(val original: UUID, val fact: SourceFact) : Change
    private data class Group(val group: SourceGroup) : Change
    private data class Cut(val cut: SourceCut) : Change
    private data class Input(val id: UUID, val user: UUID, val change: Change)

    private fun parse(subject: String, bytes: ByteArray): Input = try {
        require(bytes.size <= 65536)
        val event = mapper.readTree(bytes)
        val type = event.string("event_type")
        val version = event.count("event_version")
        require(subject in subjects && subject == "cookie.events.$type.v$version")
        val id = event.uuid("event_id")
        val owner = event.string("producer")
        event.instant("occurred_at")
        val payload = event.path("payload").also { require(it.isObject) }
        if (type == "account.deletion.requested") {
            val user = payload.uuid("accountId")
            require(owner == "identity" && event.string("aggregate_type") == "account" && event.uuid("aggregate_id") == user)
            Input(id, user, Deletion(payload.uuid("deletionRequestId"), payload.instant("requestedAt")))
        } else {
            require(owner == type.substringBefore('.'))
            val user = payload.uuid("userId")
            val change = when (type.substringAfter('.')) {
                "progress_commit.recorded" -> {
                    val first = payload.positive("firstSeq")
                    val last = payload.positive("lastSeq")
                    require(last >= first && last - first < 64)
                    Group(SourceGroup(owner, first, last, payload.instant("committedAt"), payload.lsn()))
                }
                "progress_cut.confirmed" -> {
                    val before = payload.count("beforeSeq")
                    val through = payload.count("throughSeq")
                    require(through >= before)
                    Cut(SourceCut(owner, payload.instant("boundary"), before, through, payload.lsn()))
                }
                "progress_fact.replayed" -> {
                    val original = payload.path("event")
                    val originalType = original.string("event_type")
                    val originalVersion = original.positive("event_version")
                    require(original.string("producer") == owner && originalType.substringBefore('.') == owner)
                    require("$originalType.v$originalVersion" in factTypes)
                    original.instant("occurred_at")
                    val originalPayload = original.path("payload")
                    require(originalPayload.uuid("userId") == user)
                    Fact(original.uuid("event_id"), readFact(originalType, owner, original, originalPayload))
                }
                else -> Fact(id, readFact(type, owner, event, payload))
            }
            if (change !is Fact || change.original != id) require(event.uuid("aggregate_id") == user)
            Input(id, user, change)
        }
    } catch (_: Exception) {
        throw InvalidProgressEvent("invalid_progress_envelope")
    }

    private fun readFact(type: String, owner: String, event: JsonNode, payload: JsonNode): SourceFact {
        val user = payload.uuid("userId")
        val key = when (type) {
            "nutrition.day.changed", "nutrition.water.changed", "user.goal_override.changed", "health.activity.changed" -> payload.date("localDate").toString()
            "health.weight.changed" -> payload.uuid("measurementId").toString()
            else -> user.toString()
        }
        val aggregate = when (type) {
            "nutrition.day.changed" -> "nutrition_day" to "$user:$key"
            "nutrition.water.changed" -> "water_day" to "$user:$key"
            "health.activity.changed" -> "activity_day" to "$user:$key"
            "health.weight.changed" -> "weight_measurement" to key
            "health.connection_summary.changed" -> "health_connections" to key
            else -> "user" to user.toString()
        }
        require(event.string("aggregate_type") == aggregate.first && event.string("aggregate_id") == aggregate.second)
        when (type) {
            "user.profile.updated" -> {
                payload.zone("timezone")
                payload.bool("baseline")
                payload.bool("hasAcceptedGoal")
                payload.count("goalRevision")
            }
            "user.goal.changed" -> {
                val goal = payload.path("nutritionGoalVersion")
                goal.uuid("id")
                goal.positive("revision")
                goal.date("effectiveFrom")
                require(goal.string("outcome") in setOf("CALCULATED", "NO_CALORIE_GOAL"))
                if (goal.string("outcome") == "CALCULATED") {
                    goal.checkBand("calories", setOf("RANGE"), positiveLow = true)
                    goal.checkBand("proteinG", setOf("RANGE"))
                    goal.checkBand("fatG", setOf("RANGE"))
                    goal.checkBand("carbsG", setOf("RANGE", "UPPER_LIMIT"))
                    if (goal.path("fiberG").isObject) goal.checkBand("fiberG", setOf("AT_LEAST"))
                }
            }
            "user.goal_override.changed" -> require(payload.has("override") &&
                (payload.path("override").isNull || payload.string("override") == "CHEAT_DAY"))
            "user.hydration_goal.changed" -> {
                payload.date("effectiveFrom")
                payload.uuid("id")
                payload.positive("revision")
                require(payload.count("targetMl") in 1..Int.MAX_VALUE.toLong())
            }
            "nutrition.day.changed" -> {
                payload.count("mealCount")
                val totals = payload.path("nutritionTotals")
                for (field in listOf("calories", "proteinG", "fatG", "carbsG", "fiberG")) totals.quantity(field)
                payload.provenance()
            }
            "nutrition.water.changed" -> {
                val water = payload.count("totalMl")
                require(water <= Int.MAX_VALUE && payload.has("lastConsumedAt"))
                if (!payload.path("lastConsumedAt").isNull) payload.instant("lastConsumedAt") else require(water == 0L)
                payload.provenance()
            }
            "health.activity.changed" -> {
                payload.bool("deleted")
                payload.uuid("connectionId")
                payload.zone("timezone")
                payload.quantity("activeKcal")
                if (!payload.path("steps").isNull) require(payload.count("steps") <= Int.MAX_VALUE)
            }
            "health.weight.changed" -> if (!payload.bool("deleted")) {
                payload.instant("measuredAt")
                require(payload.quantity("valueKg")?.signum() == 1)
                require(payload.has("measurementLocalDate"))
                if (!payload.path("measurementLocalDate").isNull) payload.date("measurementLocalDate")
                require(payload.string("source") in setOf("MANUAL", "APPLE_HEALTH", "HEALTH_CONNECT"))
            }
            "health.connection_summary.changed" -> payload.count("activeConnectionCount")
            else -> error("Unsupported source kind")
        }
        return SourceFact(owner, type, key, event.positive("aggregate_version"),
            if (owner == "health") null else payload.positive("sourceSeq"), payload)
    }

    companion object {
        private val factTypes = setOf(
            "nutrition.day.changed.v3", "nutrition.water.changed.v1", "user.profile.updated.v1",
            "user.goal.changed.v2", "user.goal_override.changed.v2", "user.hydration_goal.changed.v1",
            "health.activity.changed.v2", "health.weight.changed.v2", "health.connection_summary.changed.v1",
        )
        val subjects = (factTypes + "account.deletion.requested.v1" +
            listOf("user", "nutrition").flatMap {
                listOf("$it.progress_commit.recorded.v1", "$it.progress_cut.confirmed.v1", "$it.progress_fact.replayed.v1")
            }).map { "cookie.events.$it" }.toSet()
    }
}

internal fun JsonNode.string(name: String): String = requireNotNull(path(name).stringValue()).also { require(it.isNotBlank()) }
internal fun JsonNode.uuid(name: String): UUID = UUID.fromString(string(name)).also { require(it.toString() == string(name)) }
internal fun JsonNode.date(name: String): LocalDate = LocalDate.parse(string(name)).also { require(it.year in 1..9999) }
internal fun JsonNode.instant(name: String): Instant = Instant.parse(string(name))
internal fun JsonNode.zone(name: String): ZoneId = ZoneId.of(string(name).also { require(it in ZoneId.getAvailableZoneIds()) })
internal fun JsonNode.bool(name: String): Boolean = path(name).also { require(it.isBoolean) }.booleanValue()
internal fun JsonNode.count(name: String): Long = path(name).also { require(it.isIntegralNumber && it.canConvertToLong()) }
    .longValue().also { require(it >= 0) }
internal fun JsonNode.positive(name: String): Long = count(name).also { require(it > 0 && it < Long.MAX_VALUE) }
internal fun JsonNode.quantity(name: String) = path(name).let {
    if (it.isNull) null else it.also { n -> require(n.isNumber) }.decimalValue().also { n -> require(n.signum() >= 0) }
}
private fun JsonNode.lsn(): String = string("endLsn").also { require(it.matches(Regex("[0-9A-F]{1,8}/[0-9A-F]{1,8}"))) }
private fun JsonNode.provenance() {
    val origin = path("zoneProvenance")
    when (origin.string("state")) {
        "SINGLE" -> origin.zone("timezone")
        "EMPTY", "MIXED" -> Unit
        else -> error("Invalid timezone provenance")
    }
}

private fun JsonNode.checkBand(field: String, allowed: Set<String>, positiveLow: Boolean = false) {
    val band = path(field)
    val kind = band.string("kind")
    require(kind in allowed)
    fun decimal(name: String) = band.path(name).also { require(it.isNumber) }.decimalValue().also { require(it.signum() >= 0) }
    when (kind) {
        "RANGE" -> {
            val low = decimal("low")
            val high = decimal("high")
            require(high >= low && (!positiveLow || low.signum() > 0))
            if (band.path("center").isNumber) require(decimal("center") in low..high)
        }
        "UPPER_LIMIT" -> decimal("high")
        else -> decimal("low")
    }
}
