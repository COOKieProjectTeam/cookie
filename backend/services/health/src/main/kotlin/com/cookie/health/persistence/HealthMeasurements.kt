package com.cookie.health.persistence

import com.cookie.health.HealthFailure
import com.cookie.health.domain.AssessmentPolicy
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

@Component
class HealthMeasurements(val db: HealthDatabase, val mapper: ObjectMapper, private val clock: Clock) {
    val jdbc get() = db.jdbc

    data class Weight(
        val measurementId: UUID,
        val measuredAt: OffsetDateTime?,
        val valueKg: BigDecimal?,
        val version: Long,
        val source: String,
        val connectionId: UUID?,
        val deleted: Boolean,
        val measurementOffsetMinutes: Int? = null,
        val measurementLocalDate: LocalDate? = null,
    )

    private val weightRow = RowMapper<Weight> { row, _ ->
        Weight(row.uuid("id"), row.getObject("measured_at", OffsetDateTime::class.java),
            row.getBigDecimal("value_kg"), row.getLong("version"), row.getString("source"),
            row.getObject("connection_id", UUID::class.java), row.getBoolean("deleted"),
            row.getObject("measurement_offset_minutes", Int::class.javaObjectType),
            row.getObject("measurement_local_date", LocalDate::class.java))
    }

    fun <T : Any> read(user: UUID, action: () -> T): T = db.forAccount(user, action = action)

    fun command(user: UUID, operation: String, key: UUID, input: Any, action: () -> JsonNode): JsonNode =
        db.forAccount(user, write = true) {
            if (key.version() != 4 || key.variant() != 2) invalid("Idempotency-Key")
            val fingerprint = fingerprint(input)
            val previous = jdbc.query(
                "SELECT fingerprint,response FROM command_receipts WHERE user_id=? AND operation=? AND key=?",
                { row, _ -> row.getString(1) to row.getString(2) }, user, operation, key,
            ).singleOrNull()
            if (previous != null) {
                if (previous.first != fingerprint) conflict("IDEMPOTENCY_CONFLICT")
                return@forAccount mapper.readTree(previous.second)
            }
            val result = action()
            jdbc.update("INSERT INTO command_receipts VALUES (?,?,?,?,?::jsonb)", user, operation, key, fingerprint, result.toString())
            result
        }

    fun page(user: UUID, from: LocalDate?, to: LocalDate?, zone: String?, cursor: String?, limit: Int): JsonNode = read(user) {
        if (limit !in 1..100) invalid("limit")
        if (from != null && from.year !in 1..9999) invalid("from")
        if (to != null && to.year !in 1..9999) invalid("to")
        if (from != null && to != null && from > to) invalid("from")
        if (zone == null && (from != null || to != null)) invalid("timezone")
        val tz = zone?.let(::zone) ?: ZoneOffset.UTC
        val filter = fingerprint(listOf(user, from, to, zone))
        val start = from?.atStartOfDay(tz)?.toOffsetDateTime()
        val end = to?.plusDays(1)?.atStartOfDay(tz)?.toOffsetDateTime()
        val after = cursor?.let {
            if (it.length > 240) invalid("cursor")
            try {
                val parts = String(Base64.getUrlDecoder().decode(it), UTF_8).split('|')
                require(parts.size == 3 && parts[0] == filter)
                OffsetDateTime.parse(parts[1]) to UUID.fromString(parts[2])
            } catch (_: Exception) { invalid("cursor") }
        }
        val rows = jdbc.query(
            """SELECT w.* FROM weight_measurements w
                LEFT JOIN connections c ON c.user_id=w.user_id AND c.id=w.connection_id
                WHERE w.user_id=? AND NOT w.deleted AND (c.id IS NULL OR c.status IN ('ACTIVE','DISCONNECTED'))
                AND (?::timestamptz IS NULL OR w.measured_at>=?)
                AND (?::timestamptz IS NULL OR w.measured_at<?)
                AND (?::timestamptz IS NULL OR (w.measured_at,w.id)<(?,?::uuid))
                ORDER BY w.measured_at DESC,w.id DESC LIMIT ?""",
            weightRow, user, start, start, end, end, after?.first, after?.first, after?.second, limit + 1,
        )
        val items = rows.take(limit)
        val next = if (rows.size > limit) items.last().let {
            Base64.getUrlEncoder().withoutPadding().encodeToString("$filter|${it.measuredAt}|${it.measurementId}".toByteArray(UTF_8))
        } else null
        json(mapOf("items" to items.map(::publicWeight), "nextCursor" to next))
    }

    fun addManual(user: UUID, key: UUID, weight: BigDecimal, time: OffsetDateTime, offset: Int): JsonNode =
        command(user, "manual-weight", key, listOf(weight, time.toInstant(), offset)) {
            validateWeight(weight, time)
            val record = Weight(UUID.randomUUID(), time, weight, 1, "MANUAL", null, false,
                offset, measurementDate(time, offset))
            insert(user, record)
            publicWeight(record)
        }

    fun editManual(user: UUID, id: UUID, expected: Long, key: UUID, value: BigDecimal?, time: OffsetDateTime?, offset: Int?): JsonNode =
        command(user, "manual-weight-update:$id", key, listOf(expected, value, time?.toInstant(), offset)) {
            val previous = manual(user, id, expected)
            if (value == null && time == null && offset == null) invalid("weight")
            if (time != null && offset == null) invalid("measurementOffsetMinutes")
            val weight = value ?: requireNotNull(previous.valueKg)
            val measuredAt = time ?: requireNotNull(previous.measuredAt)
            validateWeight(weight, measuredAt)
            val placeOffset = offset ?: previous.measurementOffsetMinutes
            val placeDate = offset?.let { measurementDate(measuredAt, it) } ?: previous.measurementLocalDate
            if (previous.valueKg?.compareTo(weight) == 0 && previous.measuredAt?.toInstant() == measuredAt.toInstant() &&
                previous.measurementOffsetMinutes == placeOffset
            ) {
                return@command publicWeight(previous)
            }
            val updated = previous.copy(valueKg = weight, measuredAt = measuredAt, version = previous.version + 1,
                measurementOffsetMinutes = placeOffset, measurementLocalDate = placeDate)
            update(user, updated)
            publicWeight(updated)
        }

    fun deleteManual(user: UUID, id: UUID, expected: Long, key: UUID) {
        command(user, "manual-weight-delete:$id", key, expected) {
            delete(user, manual(user, id, expected))
            json(mapOf("deleted" to true))
        }
    }

    private fun manual(user: UUID, id: UUID, version: Long): Weight {
        val weight = jdbc.query("SELECT * FROM weight_measurements WHERE user_id=? AND id=? AND NOT deleted", weightRow, user, id)
            .singleOrNull() ?: missing()
        if (weight.source != "MANUAL") conflict("IMPORT_OWNED")
        if (version != weight.version) conflict("VERSION_CONFLICT")
        return weight
    }

    fun byExternal(user: UUID, connection: UUID, external: String): Weight? =
        jdbc.query("SELECT * FROM weight_measurements WHERE user_id=? AND connection_id=? AND external_id=?",
            weightRow, user, connection, external).singleOrNull()

    fun importedForPurge(user: UUID, connection: UUID, limit: Int): List<Weight> =
        jdbc.query("SELECT * FROM weight_measurements WHERE user_id=? AND connection_id=? AND NOT deleted ORDER BY id LIMIT ?",
            weightRow, user, connection, limit)

    fun insert(user: UUID, weight: Weight, external: String? = null, first: Boolean = false) {
        jdbc.update(
            """INSERT INTO weight_measurements(user_id,id,source,connection_id,external_id,first_weight,version,value_kg,measured_at,deleted,
                measurement_offset_minutes,measurement_local_date) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
            user, weight.measurementId, weight.source, weight.connectionId, external, first,
            weight.version, weight.valueKg, weight.measuredAt, weight.deleted, weight.measurementOffsetMinutes, weight.measurementLocalDate,
        )
        if (!weight.deleted) weightEvent(user, weight)
    }

    fun update(user: UUID, weight: Weight) {
        jdbc.update("""UPDATE weight_measurements SET value_kg=?,measured_at=?,version=?,deleted=?,
            measurement_offset_minutes=?,measurement_local_date=? WHERE user_id=? AND id=?""",
            weight.valueKg, weight.measuredAt, weight.version, weight.deleted,
            weight.measurementOffsetMinutes, weight.measurementLocalDate, user, weight.measurementId)
        weightEvent(user, weight)
    }

    fun delete(user: UUID, weight: Weight): Weight =
        weight.copy(valueKg = null, measuredAt = null, deleted = true, version = weight.version + 1,
            measurementOffsetMinutes = null, measurementLocalDate = null).also { update(user, it) }

    fun measurementDate(time: OffsetDateTime, offsetMinutes: Int): LocalDate {
        if (offsetMinutes !in -1080..1080) invalid("measurementOffsetMinutes")
        val date = time.withOffsetSameInstant(ZoneOffset.ofTotalSeconds(offsetMinutes * 60)).toLocalDate()
        if (date.year !in 1..9999) invalid("measurementOffsetMinutes")
        return date
    }

    fun validateWeight(weight: BigDecimal, time: OffsetDateTime) {
        if (!AssessmentPolicy.validWeight(weight)) invalid("valueKg")
        if (time.toInstant() > clock.instant() || time.year !in 1..9999 || time.nano % 1000 != 0) invalid("measuredAt")
    }

    private fun publicWeight(weight: Weight): JsonNode = json(mapOf(
        "measurementId" to weight.measurementId, "measuredAt" to weight.measuredAt, "valueKg" to weight.valueKg,
        "version" to weight.version, "source" to weight.source, "connectionId" to weight.connectionId,
        "measurementLocalDate" to weight.measurementLocalDate,
    ))

    private fun weightEvent(user: UUID, weight: Weight) {
        val properties = linkedMapOf<String, Any?>(
            "userId" to user, "measurementId" to weight.measurementId, "deleted" to weight.deleted,
        )
        if (!weight.deleted) {
            properties["valueKg"] = weight.valueKg
            properties["measuredAt"] = weight.measuredAt?.toInstant()?.toString()
            properties["measurementLocalDate"] = weight.measurementLocalDate
            properties["source"] = weight.source
        }
        event(user, "health.weight.changed", 2, "weight_measurement", weight.measurementId.toString(), weight.version, properties, weight.connectionId)
    }

    /** Called under the account lock by activation and connection transitions. */
    fun connectionSummary(user: UUID) {
        val active = jdbc.queryForObject("SELECT count(*) FROM connections WHERE user_id=? AND status='ACTIVE'", Long::class.java, user)!!
        val revision = jdbc.query(
            """INSERT INTO connection_summaries(user_id,active_count,version) VALUES (?,?,1)
               ON CONFLICT (user_id) DO UPDATE SET active_count=EXCLUDED.active_count,version=connection_summaries.version+1
               WHERE connection_summaries.active_count <> EXCLUDED.active_count RETURNING version""",
            { row, _ -> row.getLong(1) }, user, active,
        ).singleOrNull() ?: return
        event(user, "health.connection_summary.changed", 1, "health_connections", user.toString(), revision,
            mapOf("userId" to user, "activeConnectionCount" to active))
    }

    fun event(user: UUID, type: String, wireVersion: Int, aggregate: String, id: String, version: Long, payload: Any, connection: UUID? = null) {
        jdbc.update(
            """INSERT INTO outbox_events(event_id,user_id,event_type,event_version,aggregate_type,aggregate_id,aggregate_version,payload,connection_id)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?)""",
            UUID.randomUUID(), user, type, wireVersion, aggregate, id, version, mapper.writeValueAsString(payload), connection,
        )
    }

    fun fingerprint(value: Any): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical(mapper.valueToTree(value)))),
    )

    private fun canonical(node: JsonNode): Any? = when {
        node.isObject -> node.properties().associate { it.key to canonical(it.value) }.toSortedMap()
        node.isArray -> node.toList().map(::canonical)
        node.isNumber -> node.decimalValue().stripTrailingZeros()
        node.isString -> node.asString()
        node.isBoolean -> node.asBoolean()
        else -> null
    }

    fun json(value: Any): JsonNode = mapper.valueToTree(value)
}

internal fun ResultSet.uuid(name: String): UUID = getObject(name, UUID::class.java)
internal fun invalid(field: String): Nothing = throw HealthFailure("INVALID_REQUEST", 400, field)
internal fun conflict(code: String): Nothing = throw HealthFailure(code, 409)
internal fun missing(): Nothing = throw HealthFailure("NOT_FOUND", 404)
internal fun zone(value: String): ZoneId {
    if (value !in ZoneId.getAvailableZoneIds()) invalid("timezone")
    return ZoneId.of(value)
}
