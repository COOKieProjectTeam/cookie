package com.cookie.progress

import com.cookie.platform.messaging.EventEnvelope
import com.cookie.platform.messaging.subject
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal class ProgressStore(
    val jdbc: JdbcTemplate,
    val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
) {
    fun lock(user: UUID, shared: Boolean = false) {
        val function = if (shared) "pg_advisory_xact_lock_shared" else "pg_advisory_xact_lock"
        jdbc.query("SELECT $function(hashtextextended(?,0))", { _, _ -> Unit }, user.toString())
    }

    fun deleted(user: UUID): Boolean =
        jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM account_tombstones WHERE user_id=?)", Boolean::class.java, user) == true

    fun erase(user: UUID, request: UUID, at: Instant) {
        jdbc.update("INSERT INTO account_tombstones VALUES (?,?,?) ON CONFLICT DO NOTHING", user, request, at.sqlTime())
        jdbc.update("DELETE FROM progress_accounts WHERE user_id=?", user)
    }

    fun acceptInbox(eventId: UUID): Boolean =
        jdbc.update("INSERT INTO inbox_events(event_id) VALUES (?) ON CONFLICT DO NOTHING", eventId) == 1

    fun ensureAccount(user: UUID) {
        jdbc.update("INSERT INTO progress_accounts(user_id) VALUES (?) ON CONFLICT DO NOTHING", user)
    }

    fun wake(user: UUID) {
        jdbc.update("UPDATE progress_accounts SET next_due_at=LEAST(next_due_at,clock_timestamp()) WHERE user_id=?", user)
    }

    fun recordFact(user: UUID, fact: SourceFact, eventId: UUID) {
        val inserted = jdbc.update(
            """INSERT INTO source_facts(user_id,owner,kind,entity_key,version,source_seq,event_id,payload)
               VALUES (?,?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING""",
            user, fact.owner, fact.kind, fact.key, fact.version, fact.sequence, eventId, fact.payload.toString(),
        )
        if (inserted != 0) return
        val same = jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM source_facts WHERE user_id=? AND owner=? AND kind=? AND entity_key=?
               AND version=? AND source_seq IS NOT DISTINCT FROM ?::bigint AND payload=?::jsonb)""",
            Boolean::class.java, user, fact.owner, fact.kind, fact.key, fact.version, fact.sequence, fact.payload.toString(),
        )
        if (same != true) throw InvalidProgressEvent("conflicting_fact_version")
    }

    fun recordGroup(user: UUID, group: SourceGroup) {
        val inconsistent = jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM source_groups WHERE user_id=? AND owner=? AND first_seq<>? AND
                 ((first_seq < ? AND (last_seq >= ? OR committed_at > ?)) OR
                  (first_seq > ? AND (first_seq <= ? OR committed_at < ?))))""",
            Boolean::class.java, user, group.owner, group.first, group.first, group.first, group.at.sqlTime(),
            group.first, group.last, group.at.sqlTime(),
        )
        if (inconsistent == true) throw InvalidProgressEvent("inconsistent_commit_group")
        val inserted = jdbc.update(
            "INSERT INTO source_groups VALUES (?,?,?,?,?,?::pg_lsn) ON CONFLICT DO NOTHING",
            user, group.owner, group.first, group.last, group.at.sqlTime(), group.endLsn,
        )
        if (inserted == 0 && group != jdbc.query(
                "SELECT * FROM source_groups WHERE user_id=? AND owner=? AND first_seq=?",
                groupRow, user, group.owner, group.first,
            ).singleOrNull()
        ) throw InvalidProgressEvent("conflicting_commit_group")
    }

    fun recordCut(user: UUID, cut: SourceCut) {
        val inserted = jdbc.update(
            "INSERT INTO source_cuts VALUES (?,?,?,?,?,?::pg_lsn) ON CONFLICT DO NOTHING",
            user, cut.owner, cut.boundary.sqlTime(), cut.before, cut.through, cut.endLsn,
        )
        if (inserted == 0 && cut != cut(user, cut.owner, cut.boundary)) throw InvalidProgressEvent("conflicting_source_cut")
    }

    fun cut(user: UUID, owner: String, boundary: Instant): SourceCut? = jdbc.query(
        "SELECT * FROM source_cuts WHERE user_id=? AND owner=? AND boundary=?",
        { row, _ -> SourceCut(owner, boundary, row.getLong("before_seq"), row.getLong("through_seq"), row.getString("end_lsn")) },
        user, owner, boundary.sqlTime(),
    ).singleOrNull()

    fun cursor(user: UUID, owner: String): Long = jdbc.query(
        "SELECT applied_through FROM source_cursors WHERE user_id=? AND owner=?", { row, _ -> row.getLong(1) }, user, owner,
    ).singleOrNull() ?: 0

    fun advance(user: UUID, owner: String) {
        val from = cursor(user, owner)
        val through = jdbc.queryForObject(
            """WITH RECURSIVE complete(last_seq) AS (
                 VALUES (?::bigint)
                 UNION ALL
                 SELECT g.last_seq FROM complete c
                 JOIN source_groups g ON g.user_id=? AND g.owner=? AND g.first_seq=c.last_seq+1
                 WHERE g.last_seq-g.first_seq+1 = (SELECT count(*) FROM source_facts f
                     WHERE f.user_id=g.user_id AND f.owner=g.owner AND f.source_seq BETWEEN g.first_seq AND g.last_seq)
               ) SELECT max(last_seq) FROM complete""",
            Long::class.java, from, user, owner,
        )!!
        if (through > from) jdbc.update(
            """INSERT INTO source_cursors VALUES (?,?,?) ON CONFLICT (user_id, owner)
               DO UPDATE SET applied_through=EXCLUDED.applied_through""", user, owner, through,
        )
    }

    fun highEvidence(user: UUID, owner: String): Long = jdbc.queryForObject(
        """SELECT GREATEST(
                (SELECT max(last_seq) FROM source_groups WHERE user_id=? AND owner=?),
                (SELECT max(source_seq) FROM source_facts WHERE user_id=? AND owner=?),0)""",
        Long::class.java, user, owner, user, owner,
    )!!

    fun latest(user: UUID, kind: String, key: String, through: Long? = null): SourceFact? = jdbc.query(
        """SELECT f.* FROM source_facts f LEFT JOIN source_cursors c USING(user_id,owner)
           WHERE f.user_id=? AND f.kind=? AND f.entity_key=? AND
           (f.source_seq IS NULL OR f.source_seq <= COALESCE(?::bigint,c.applied_through,0))
           ORDER BY f.version DESC LIMIT 1""",
        factRow, user, kind, key, through,
    ).singleOrNull()

    fun history(user: UUID, kind: String, through: Long = cursor(user, "user")): List<Pair<SourceFact, Instant>> = jdbc.query(
        """SELECT f.*,g.committed_at FROM source_facts f JOIN source_groups g USING(user_id,owner)
           WHERE f.user_id=? AND f.kind=? AND f.source_seq BETWEEN g.first_seq AND g.last_seq AND f.source_seq<=?
           ORDER BY f.source_seq""",
        { row, index -> factRow.mapRow(row, index) to row.instant("committed_at")!! }, user, kind, through,
    )

    fun pendingRequest(user: UUID, key: String, now: Instant): Boolean = jdbc.update(
        """INSERT INTO source_requests VALUES (?,?,?) ON CONFLICT (user_id,key)
           DO UPDATE SET last_requested_at=EXCLUDED.last_requested_at
           WHERE source_requests.last_requested_at <= EXCLUDED.last_requested_at - interval '30 seconds'""",
        user, key, now.sqlTime(),
    ) == 1

    fun enqueue(user: UUID, type: String, version: Int, aggregate: String, id: String, payload: JsonNode, revision: Long? = null) {
        val event = EventEnvelope(UUID.randomUUID(), type, version, Instant.now(), "progress", aggregate, id, payload, revision)
        jdbc.update("INSERT INTO outbox_events(event_id,user_id,subject,envelope) VALUES (?,?,?,?::jsonb)",
            event.eventId, user, event.subject(), mapper.writeValueAsString(event))
    }

    private val groupRow = RowMapper { row, _ ->
        SourceGroup(row.getString("owner"), row.getLong("first_seq"), row.getLong("last_seq"),
            row.instant("committed_at")!!, row.getString("end_lsn"))
    }
    internal val factRow = RowMapper { row, _ ->
        SourceFact(row.getString("owner"), row.getString("kind"), row.getString("entity_key"), row.getLong("version"),
            row.getObject("source_seq", Long::class.javaObjectType), mapper.readTree(row.getString("payload")))
    }
}

internal fun Instant.sqlTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
internal fun java.sql.ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
