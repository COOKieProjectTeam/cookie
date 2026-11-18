package com.cookie.health.persistence

import com.cookie.health.HealthFailure
import com.cookie.health.persistence.HealthMeasurements.Weight
import org.springframework.jdbc.core.RowMapper
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Component
class HealthImports(private val measurements: HealthMeasurements, private val clock: Clock) {
    private val db get() = measurements.db
    private val jdbc get() = db.jdbc

    data class Connection(
        val connectionId: UUID, val platform: String, val timezone: String, val status: String,
        val generation: Long, val version: Long, val nextSeq: Long, val initialFrom: LocalDate, val initialTo: LocalDate,
    )
    data class Primary(val connectionId: UUID, val effectiveFrom: LocalDate, val timezone: String, val revision: Long)
    private data class Activity(val timezone: String, val steps: Int?, val kcal: BigDecimal?, val deleted: Boolean)
    private data class Purge(val purgeId: UUID, val status: String)

    private val connectionRow = RowMapper { row, _ ->
        Connection(row.uuid("id"), row.getString("platform"), row.getString("timezone"), row.getString("status"),
            row.getLong("generation"), row.getLong("version"), row.getLong("next_seq"),
            row.getObject("initial_from", LocalDate::class.java), row.getObject("initial_to", LocalDate::class.java))
    }
    private val primaryRow = RowMapper { row, _ ->
        Primary(row.uuid("connection_id"), row.getObject("effective_from", LocalDate::class.java),
            row.getString("timezone"), row.getLong("revision"))
    }
    private val activityRow = RowMapper { row, _ ->
        Activity(row.getString("timezone"), row.getObject("steps", Int::class.javaObjectType),
            row.getBigDecimal("active_kcal"), row.getBoolean("deleted"))
    }

    fun register(user: UUID, key: UUID, platform: String, timezone: String): JsonNode =
        measurements.command(user, "register-connection", key, listOf(platform, timezone)) {
            require(platform in setOf("APPLE_HEALTH", "HEALTH_CONNECT"))
            val today = LocalDate.now(clock.withZone(zone(timezone)))
            val connection = Connection(UUID.randomUUID(), platform, timezone, "ACTIVE", 1, 1, 1, today.minusDays(29), today)
            jdbc.update(
                """INSERT INTO connections(user_id,id,platform,timezone,status,initial_from,initial_to)
                    VALUES (?,?,?,?,?,?,?)""",
                user, connection.connectionId, platform, timezone, connection.status, connection.initialFrom, today,
            )
            if (latestPrimary(user) == null) schedule(user, Primary(connection.connectionId, connection.initialFrom, timezone, 1))
            measurements.json(connection)
        }

    fun list(user: UUID): JsonNode = measurements.read(user) {
        val connections = jdbc.query("SELECT * FROM connections WHERE user_id=? ORDER BY status,id", connectionRow, user)
        measurements.json(mapOf("items" to connections, "primary" to latestPrimary(user)))
    }

    fun changeConnection(user: UUID, id: UUID, expected: Long, key: UUID, resume: Boolean): JsonNode =
        measurements.command(user, "connection:$id:$resume", key, expected) {
            val before = connection(user, id)
            when (before.status) {
                "PURGING", "PURGED" -> missing()
            }
            if (before.version != expected) conflict("VERSION_CONFLICT")
            val after = if (resume) before.copy(status = "ACTIVE", generation = before.generation + 1,
                version = before.version + 1, nextSeq = 1)
            else if (before.status != "DISCONNECTED") before.copy(status = "DISCONNECTED", generation = before.generation + 1,
                version = before.version + 1, nextSeq = 1)
            else before
            if (after != before) jdbc.update(
                "UPDATE connections SET generation=?,version=?,status=?,next_seq=? WHERE user_id=? AND id=?",
                after.generation, after.version, after.status, after.nextSeq, user, id,
            )
            measurements.json(after)
        }

    fun setPrimary(user: UUID, key: UUID, id: UUID, expected: Long, timezone: String?): JsonNode =
        measurements.command(user, "primary-connection", key, listOf(id, expected, timezone)) {
            val source = connection(user, id)
            if (source.status != "ACTIVE") conflict("CONNECTION_INACTIVE")
            val previous = latestPrimary(user) ?: missing()
            if (previous.revision != expected) conflict("VERSION_CONFLICT")
            val now = clock.instant()
            val current = jdbc.query(
                "SELECT * FROM primary_connection_periods WHERE user_id=? AND effective_at<=? ORDER BY revision DESC LIMIT 1",
                primaryRow, user, now.atOffset(java.time.ZoneOffset.UTC),
            ).singleOrNull() ?: previous
            val newZone = timezone?.let(::zone)?.id ?: current.timezone
            val tomorrow = now.atZone(zone(current.timezone)).toLocalDate().plusDays(1)
            val effectiveAt = tomorrow.atStartOfDay(zone(current.timezone)).toOffsetDateTime()
            val next = Primary(id, tomorrow, newZone, previous.revision + 1)
            schedule(user, next, effectiveAt)
            measurements.json(next)
        }

    private fun latestPrimary(user: UUID): Primary? = jdbc.query(
        "SELECT * FROM primary_connection_periods WHERE user_id=? ORDER BY revision DESC LIMIT 1",
        primaryRow, user,
    ).singleOrNull()

    private fun schedule(user: UUID, value: Primary, effectiveAt: OffsetDateTime = value.effectiveFrom.atStartOfDay(zone(value.timezone)).toOffsetDateTime()) {
        jdbc.update(
            "INSERT INTO primary_connection_periods(user_id,effective_from,timezone,effective_at,connection_id,revision) VALUES (?,?,?,?,?,?)",
            user, value.effectiveFrom, value.timezone, effectiveAt, value.connectionId, value.revision,
        )
    }

    private fun connection(user: UUID, id: UUID): Connection =
        jdbc.query("SELECT * FROM connections WHERE user_id=? AND id=?", connectionRow, user, id).singleOrNull() ?: missing()

    fun applyBatch(user: UUID, id: UUID, key: UUID, body: JsonNode): JsonNode = db.forAccount(user, write = true) {
        val source = connection(user, id)
        val generation = body.path("generation").asLong()
        val seq = body.path("seq").asLong()
        val items = body.path("items")
        if (generation <= 0 || seq <= 0 || seq == Long.MAX_VALUE || !items.isArray || items.size() !in 1..100) invalid("batch")
        if (source.generation != generation) conflict("STALE_GENERATION")
        if (source.status != "ACTIVE") conflict("CONNECTION_INACTIVE")
        if (key.version() != 4 || key.variant() != 2) invalid("Idempotency-Key")
        val fingerprint = measurements.fingerprint(body)
        val keyed = jdbc.query(
            "SELECT generation,seq,fingerprint FROM import_batches WHERE user_id=? AND connection_id=? AND idempotency_key=?",
            { row, _ -> Triple(row.getLong(1), row.getLong(2), row.getString(3)) }, user, id, key,
        ).singleOrNull()
        if (keyed != null && keyed != Triple(generation, seq, fingerprint)) conflict("IDEMPOTENCY_CONFLICT")
        if (seq > source.nextSeq) throw HealthFailure("SEQUENCE_GAP", 409, expectedSequence = source.nextSeq)
        if (seq < source.nextSeq) {
            val old = jdbc.query(
                """SELECT fingerprint,idempotency_key,CASE WHEN created_at >= now()-interval '30 days' THEN response::text END
                    FROM import_batches WHERE user_id=? AND connection_id=? AND generation=? AND seq=?""",
                { row, _ -> Triple(row.getString(1),row.getObject(2,UUID::class.java),row.getString(3)) }, user, id, generation, seq,
            ).singleOrNull()
            if (old == null || old.first != fingerprint || old.second != key) conflict("IDEMPOTENCY_CONFLICT")
            return@forAccount old.third?.let(measurements.mapper::readTree)
                ?: measurements.json(mapOf("seq" to seq, "nextSeq" to source.nextSeq, "status" to "ALREADY_APPLIED"))
        }
        val affected = linkedSetOf<LocalDate>()
        val results = items.mapIndexed { index, item ->
            try {
                when (item.path("op").asString()) {
                    "WEIGHT_UPSERT" -> weight(user, source, item, false)
                    "WEIGHT_DELETE" -> weight(user, source, item, true)
                    "ACTIVITY_SNAPSHOT" -> activity(user, source, item, false, affected)
                    "ACTIVITY_DELETE" -> activity(user, source, item, true, affected)
                    else -> invalid("op")
                }
            } catch (e: HealthFailure) {
                throw HealthFailure(e.code, e.status, e.field, index = index)
            } catch (_: IllegalArgumentException) {
                throw HealthFailure("INVALID_REQUEST", 400, "item", index = index)
            } catch (_: java.time.DateTimeException) {
                throw HealthFailure("INVALID_REQUEST", 400, "date", index = index)
            }
        }
        for (date in affected) emitDay(user, source.connectionId, date)
        val result = measurements.json(mapOf("seq" to seq, "nextSeq" to seq + 1, "status" to "COMMITTED", "items" to results))
        jdbc.update(
            """INSERT INTO import_batches(user_id,connection_id,generation,seq,idempotency_key,fingerprint,response)
                VALUES (?,?,?,?,?,?,?::jsonb)""",
            user, id, generation, seq, key, fingerprint, result.toString(),
        )
        jdbc.update("UPDATE connections SET next_seq=? WHERE user_id=? AND id=?", seq + 1, user, id)
        result
    }

    private fun weight(user: UUID, source: Connection, item: JsonNode, deleted: Boolean): Map<String, Any> {
        val external = item.path("externalId").asString()
        if (external.isBlank() || external.length > 200 || external.any(Char::isISOControl)) invalid("externalId")
        if (source.platform == "APPLE_HEALTH" && !UUID.fromString(external).toString().equals(external, true)) invalid("externalId")
        val key = if (source.platform == "APPLE_HEALTH") external.lowercase() else external
        val previous = measurements.byExternal(user, source.connectionId, key)
        val version = previous?.version ?: 0
        val id = previous?.measurementId ?: UUID.randomUUID()
        fun status(value: String, current: Long) = mapOf("status" to value, "measurementId" to id, "version" to current)
        fields(item, if (deleted) setOf("op", "externalId") else setOf("op", "externalId", "valueKg", "measuredAt"))
        if (deleted) {
            if (previous?.deleted == true) return status("ALREADY_DELETED", version)
            if (previous != null) return status("APPLIED", measurements.delete(user, previous).version)
            measurements.insert(user, Weight(id, null, null, 1, source.platform, source.connectionId, true), key)
            return status("APPLIED", 1)
        }
        val value = item.path("valueKg").takeIf { it.isNumber }?.decimalValue() ?: invalid("valueKg")
        val measuredAt = OffsetDateTime.parse(item.path("measuredAt").asString())
        measurements.validateWeight(value, measuredAt)
        if (previous == null && measuredAt.atZoneSameInstant(zone(source.timezone)).toLocalDate() < source.initialFrom) invalid("measuredAt")
        if (previous?.deleted == true) return status("ALREADY_DELETED", version)
        if (previous?.valueKg?.compareTo(value) == 0 && previous.measuredAt?.toInstant() == measuredAt.toInstant()) {
            return status("UNCHANGED", version)
        }
        val current = Weight(id, measuredAt, value, version + 1, source.platform, source.connectionId, false)
        if (previous == null) measurements.insert(user, current, key) else measurements.update(user, current)
        return status("APPLIED", current.version)
    }

    private fun activity(user: UUID, source: Connection, item: JsonNode, deleted: Boolean, affected: MutableSet<LocalDate>): Map<String, String> {
        val expectedFields = setOf("op", "localDate", "timezone") + if (deleted) emptySet() else setOf("steps", "activeKcal")
        fields(item, expectedFields)
        if (item.properties().map { it.key }.toSet() != expectedFields) invalid("activity")
        val date = LocalDate.parse(item.path("localDate").asString())
        val timezone = zone(item.path("timezone").asString()).id
        if (date.year !in 1..9999 || date > LocalDate.now(clock.withZone(zone(timezone)))) invalid("localDate")
        val steps = item.get("steps")?.takeUnless { it.isNull }?.let {
            if (!it.isIntegralNumber || !it.canConvertToInt() || it.asInt() < 0) invalid("steps")
            it.asInt()
        }
        val kcal = item.get("activeKcal")?.takeUnless { it.isNull }?.let {
            if (!it.isNumber) invalid("activeKcal")
            it.decimalValue().also { n ->
                if (n.signum() < 0 || n > "999999999.999".toBigDecimal() || n.stripTrailingZeros().scale() > 3) invalid("activeKcal")
            }.setScale(3)
        }
        val id = source.connectionId
        val previous = jdbc.query("SELECT * FROM activity_source_days WHERE user_id=? AND connection_id=? AND local_date=?",
            activityRow, user, id, date).singleOrNull()
        if (previous == null && date < source.initialFrom) invalid("localDate")
        if (previous == Activity(timezone, steps, kcal, deleted)) return mapOf("status" to if (deleted) "ALREADY_DELETED" else "UNCHANGED")
        val calendar = jdbc.query(
            "SELECT * FROM primary_connection_periods WHERE user_id=? AND effective_at<=? ORDER BY revision DESC LIMIT 1",
            primaryRow, user, clock.instant().atOffset(java.time.ZoneOffset.UTC),
        ).singleOrNull()
        if (calendar != null && date > LocalDate.now(clock.withZone(zone(calendar.timezone)))) invalid("localDate")
        val primary = jdbc.query(
            """SELECT * FROM primary_connection_periods WHERE user_id=? AND effective_from<=? AND effective_at<=?
                ORDER BY effective_from DESC,revision DESC LIMIT 1""",
            primaryRow, user, date, clock.instant().atOffset(java.time.ZoneOffset.UTC),
        ).singleOrNull()
        jdbc.update(
            """INSERT INTO activity_day_versions(user_id,local_date,connection_id,timezone) VALUES (?,?,?,?)
                ON CONFLICT (user_id,local_date) DO NOTHING""",
            user, date, primary?.connectionId, primary?.timezone ?: timezone,
        )
        val fixed = jdbc.queryForMap("SELECT connection_id,timezone FROM activity_day_versions WHERE user_id=? AND local_date=?", user, date)
        if (id == fixed["connection_id"] && timezone != fixed["timezone"]) invalid("timezone")
        jdbc.update(
            """INSERT INTO activity_source_days(user_id,connection_id,local_date,timezone,steps,active_kcal,deleted)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT(user_id,connection_id,local_date) DO UPDATE
                SET timezone=excluded.timezone,steps=excluded.steps,active_kcal=excluded.active_kcal,deleted=excluded.deleted""",
            user, id, date, timezone, steps, kcal, deleted,
        )
        if (id == fixed["connection_id"]) affected.add(date)
        return mapOf("status" to "APPLIED")
    }

    private fun fields(node: JsonNode, allowed: Set<String>) {
        if (!node.isObject || node.properties().any { it.key !in allowed }) invalid("item")
    }

    private fun emitDay(user: UUID, id: UUID, date: LocalDate) {
        val version = jdbc.queryForObject(
            "UPDATE activity_day_versions SET version=version+1 WHERE user_id=? AND local_date=? RETURNING version",
            Long::class.java, user, date,
        )!!
        val day = jdbc.query(
            "SELECT * FROM activity_source_days WHERE user_id=? AND connection_id=? AND local_date=?",
            activityRow, user, id, date,
        ).single()
        measurements.event(user, "health.activity.changed", 2, "activity_day", "$user:$date", version, mapOf(
            "userId" to user, "localDate" to date, "timezone" to day.timezone, "connectionId" to id,
            "steps" to day.steps, "activeKcal" to day.kcal, "deleted" to day.deleted,
        ), id)
    }

    fun startPurge(user: UUID, id: UUID, key: UUID, expected: Long): JsonNode =
        measurements.command(user, "purge-connection:$id", key, expected) {
            val source = connection(user, id)
            if (source.status in setOf("PURGING", "PURGED")) {
                val purge = jdbc.queryForObject("SELECT id FROM purge_jobs WHERE user_id=? AND connection_id=?", UUID::class.java, user, id)!!
                return@command purge(user, id, purge)
            }
            if (source.version != expected) conflict("VERSION_CONFLICT")
            val purgeId = UUID.randomUUID()
            jdbc.update("UPDATE connections SET generation=generation+1,version=version+1,status='PURGING' WHERE user_id=? AND id=?", user, id)
            jdbc.update("DELETE FROM outbox_events WHERE user_id=? AND connection_id=? AND payload->>'deleted'='false'", user, id)
            jdbc.update("INSERT INTO purge_jobs(user_id,connection_id,id) VALUES (?,?,?)", user, id, purgeId)
            measurements.json(Purge(purgeId, "PENDING"))
        }

    fun purge(user: UUID, id: UUID, purgeId: UUID): JsonNode = measurements.read(user) {
        val completed = jdbc.query("SELECT completed FROM purge_jobs WHERE user_id=? AND connection_id=? AND id=?",
            { row, _ -> row.getBoolean(1) }, user, id, purgeId).singleOrNull() ?: missing()
        measurements.json(Purge(purgeId, if (completed) "COMPLETED" else "PENDING"))
    }

    @Scheduled(fixedDelay = 500)
    fun finishPurges() {
        val jobs = jdbc.query("SELECT user_id,connection_id FROM purge_jobs WHERE NOT completed AND retry_at<=now() ORDER BY retry_at,id LIMIT 20",
            { row, _ -> row.uuid("user_id") to row.uuid("connection_id") })
        for ((user, connection) in jobs) {
            try {
                db.forAccount(user, write = true) { purgeChunk(user, connection) }
            } catch (_: HealthFailure) {
                // A concurrent account deletion has already removed its job.
            } catch (_: org.springframework.dao.DataAccessException) {
                runCatching {
                    jdbc.update("UPDATE purge_jobs SET retry_at=now()+interval '30 seconds' WHERE user_id=? AND connection_id=?", user, connection)
                }
            }
        }
    }

    @Scheduled(fixedDelay = 60000)
    fun scrubImportMetadata() {
        jdbc.update("""UPDATE import_batches SET response=NULL WHERE (user_id,connection_id,generation,seq) IN (
            SELECT user_id,connection_id,generation,seq FROM import_batches
            WHERE response IS NOT NULL AND created_at<now()-interval '30 days' LIMIT 500)""")
        jdbc.update("""DELETE FROM outbox_events WHERE event_id IN (
            SELECT event_id FROM outbox_events WHERE published_at<now()-interval '7 days' LIMIT 500)""")
    }

    private fun purgeChunk(user: UUID, id: UUID) {
        if (connection(user, id).status != "PURGING") return
        val weights = measurements.importedForPurge(user, id, 100)
        for (weight in weights) measurements.delete(user, weight)
        if (weights.isNotEmpty()) return
        val dates = jdbc.query(
            "SELECT local_date FROM activity_source_days WHERE user_id=? AND connection_id=? ORDER BY local_date LIMIT 100",
            { row, _ -> row.getObject(1, LocalDate::class.java) }, user, id,
        )
        for (date in dates) {
            val selected = jdbc.queryForObject(
                """SELECT EXISTS(SELECT 1 FROM activity_day_versions WHERE user_id=? AND local_date=? AND connection_id=?)""",
                Boolean::class.java, user, date, id,
            ) == true
            if (selected) {
                val changed = jdbc.update(
                    """UPDATE activity_source_days SET steps=NULL,active_kcal=NULL,deleted=true
                        WHERE user_id=? AND connection_id=? AND local_date=? AND NOT deleted""", user, id, date,
                )
                if (changed > 0) emitDay(user, id, date)
            }
            jdbc.update("DELETE FROM activity_source_days WHERE user_id=? AND connection_id=? AND local_date=?", user, id, date)
        }
        if (dates.isNotEmpty()) return
        jdbc.update("DELETE FROM weight_measurements WHERE user_id=? AND connection_id=?", user, id)
        jdbc.update("DELETE FROM import_batches WHERE user_id=? AND connection_id=?", user, id)
        jdbc.update("UPDATE connections SET status='PURGED' WHERE user_id=? AND id=?", user, id)
        jdbc.update("UPDATE purge_jobs SET completed=true WHERE user_id=? AND connection_id=?", user, id)
    }
}
