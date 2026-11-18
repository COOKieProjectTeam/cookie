package com.cookie.platform.postgres

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

sealed interface ProgressEvidence {
    val userId: UUID
    val endLsn: String

    data class Group(
        override val userId: UUID,
        override val endLsn: String,
        val committedAt: Instant,
        val firstSeq: Long,
        val lastSeq: Long,
    ) : ProgressEvidence

    data class Cut(
        override val userId: UUID,
        override val endLsn: String,
        val boundary: Instant,
        val beforeSeq: Long,
        val throughSeq: Long,
    ) : ProgressEvidence
}

class ProgressSourceJournal(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val lockAccount: (UUID) -> Unit,
    private val publish: (ProgressEvidence) -> Unit,
) {
    private data class Marker(val userId: UUID, val seq: Long?, val boundary: Instant?, val expectedSeq: Long?)
    private data class CaptureState(val observedAt: Instant?, val fencedThrough: Instant?, val fault: Boolean, val capturedSeq: Long)

    /** Called from the domain transaction under the owner's account lock. */
    fun append(user: UUID, eventId: UUID, envelope: (Long) -> String): String {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        val seq = jdbc.queryForObject(
            """INSERT INTO progress_source_accounts(user_id, latest_seq) VALUES (?,1)
               ON CONFLICT (user_id) DO UPDATE SET latest_seq=progress_source_accounts.latest_seq+1
               WHERE NOT progress_source_accounts.evidence_fault AND
                   (progress_source_accounts.admission_boundary IS NULL OR
                    clock_timestamp()>progress_source_accounts.admission_boundary)
               RETURNING latest_seq""",
            { row, _ -> row.getLong(1) }, user,
        )
        val body = envelope(seq)
        jdbc.update("INSERT INTO progress_source_facts VALUES (?,?,?,?::jsonb)", user, seq, eventId, body)
        jdbc.update("INSERT INTO progress_commit_markers(marker_id,user_id,source_seq) VALUES (?,?,?)", eventId, user, seq)
        return body
    }

    /** The caller holds the account lock and checks the owner's deletion tombstone. */
    fun requestCut(user: UUID, boundary: Instant) {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        existingCut(user, boundary)?.let {
            publish(it)
            return
        }
        val afterBoundary = jdbc.queryForObject(
            "SELECT clock_timestamp()>?", Boolean::class.java, boundary.sqlTime(),
        )
        check(afterBoundary == true) { "Progress cut has not been reached on the owner clock" }
        jdbc.update("INSERT INTO progress_source_accounts(user_id) VALUES (?) ON CONFLICT DO NOTHING", user)
        jdbc.update("UPDATE progress_source_accounts SET admission_boundary=GREATEST(admission_boundary,?) WHERE user_id=?",
            boundary.sqlTime(), user)
        jdbc.update(
            """INSERT INTO progress_commit_markers(marker_id,user_id,boundary,expected_seq)
               SELECT ?,user_id,?,latest_seq FROM progress_source_accounts WHERE user_id=?
               ON CONFLICT (user_id,boundary) DO NOTHING""", UUID.randomUUID(), boundary.sqlTime(), user,
        )
    }

    /** Called before acknowledging the replication position. */
    fun record(commit: MarkerCommit) {
        transactions.executeWithoutResult {
            val pending = jdbc.queryForObject(
                "SELECT end_lsn < ?::pg_lsn FROM progress_wal_cursor WHERE singleton FOR UPDATE",
                Boolean::class.java, commit.endLsn,
            )
            if (pending != true) return@executeWithoutResult
            val initial = commit.markers.firstOrNull()?.let(::marker)
            if (initial != null) {
                lockAccount(initial.userId)
                val markers = commit.markers.mapNotNull(::marker)
                if (markers.isNotEmpty()) recordAccount(commit, markers)
            }
            jdbc.update("UPDATE progress_wal_cursor SET end_lsn=?::pg_lsn WHERE singleton", commit.endLsn)
        }
    }

    private fun recordAccount(commit: MarkerCommit, markers: List<Marker>) {
        val user = markers.first().userId
        check(markers.all { it.userId == user }) { "A Progress source transaction crossed accounts" }
        val clock = jdbc.queryForObject(
            "SELECT observed_at,fenced_through,evidence_fault,captured_seq FROM progress_source_accounts WHERE user_id=?",
            { row, _ -> CaptureState(
                row.getObject(1, OffsetDateTime::class.java)?.toInstant(),
                row.getObject(2, OffsetDateTime::class.java)?.toInstant(),
                row.getBoolean(3),
                row.getLong(4),
            ) }, user,
        )
        val boundary = markers.singleOrNull()?.boundary
        val seq = if (boundary == null) markers.map { checkNotNull(it.seq) }.sorted() else emptyList()
        val complete = if (boundary == null) {
            seq.first() == clock.capturedSeq + 1 && seq.last() - seq.first() == seq.size.toLong() - 1
        } else markers.single().expectedSeq == clock.capturedSeq
        if (clock.fault || clock.observedAt?.let { commit.at < it } == true ||
            clock.fencedThrough?.let { commit.at <= it } == true ||
            boundary?.let { commit.at <= it } == true || !complete
        ) {
            jdbc.update("UPDATE progress_source_accounts SET evidence_fault=true WHERE user_id=?", user)
            jdbc.update(
                "INSERT INTO progress_source_faults VALUES (?,?::pg_lsn,?,?::uuid[])",
                user, commit.endLsn, commit.at.sqlTime(), commit.markers.joinToString(",", "{", "}"),
            )
            return
        }
        jdbc.update(
            """UPDATE progress_source_accounts SET observed_at=?,fenced_through=GREATEST(fenced_through,?),captured_seq=?
               WHERE user_id=?""", commit.at.sqlTime(), boundary?.sqlTime(), seq.lastOrNull() ?: clock.capturedSeq, user,
        )
        if (boundary == null) {
            val group = ProgressEvidence.Group(user, commit.endLsn, commit.at, seq.first(), seq.last())
            jdbc.update("INSERT INTO progress_source_commits VALUES (?,?,?,?,?::pg_lsn)",
                user, group.firstSeq, group.lastSeq, group.committedAt.sqlTime(), group.endLsn)
            publish(group)
            return
        }
        val cursors = jdbc.queryForObject(
            """SELECT COALESCE(MAX(last_seq) FILTER (WHERE committed_at < ?), 0),
                      COALESCE(MAX(last_seq) FILTER (WHERE committed_at <= ?), 0)
               FROM progress_source_commits WHERE user_id=? AND committed_at<=?""",
            { row, _ -> row.getLong(1) to row.getLong(2) },
            boundary.sqlTime(), boundary.sqlTime(), user, boundary.sqlTime(),
        )
        val cut = ProgressEvidence.Cut(user, commit.endLsn, boundary, cursors.first, cursors.second)
        jdbc.update("INSERT INTO progress_source_cuts VALUES (?,?,?,?,?::pg_lsn)",
            user, boundary.sqlTime(), cut.beforeSeq, cut.throughSeq, cut.endLsn)
        publish(cut)
    }

    fun replay(user: UUID, first: Long, last: Long, send: (String) -> Unit) {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        require(first > 0 && last >= first && last - first < 64)
        val groups = jdbc.query(
            """SELECT first_seq,last_seq,committed_at,end_lsn::text FROM progress_source_commits
               WHERE user_id=? AND first_seq<=? AND last_seq>=? ORDER BY first_seq""",
            { row, _ -> ProgressEvidence.Group(user, row.getString(4),
                row.getObject(3, OffsetDateTime::class.java).toInstant(), row.getLong(1), row.getLong(2)) },
            user, last, first,
        )
        groups.forEach(publish)
        jdbc.query(
            """SELECT envelope::text FROM progress_source_facts
               WHERE user_id=? AND source_seq BETWEEN ? AND ? ORDER BY source_seq""",
            { row, _ -> row.getString(1) }, user, first, last,
        ).forEach(send)
    }

    private fun marker(id: UUID): Marker? = jdbc.query(
        "SELECT user_id,source_seq,boundary,expected_seq FROM progress_commit_markers WHERE marker_id=?",
        { row, _ -> Marker(row.getObject(1, UUID::class.java), row.getObject(2, Long::class.javaObjectType),
            row.getObject(3, OffsetDateTime::class.java)?.toInstant(), row.getObject(4, Long::class.javaObjectType)) }, id,
    ).singleOrNull()

    private fun existingCut(user: UUID, boundary: Instant): ProgressEvidence.Cut? = jdbc.query(
        """SELECT before_seq,through_seq,end_lsn::text FROM progress_source_cuts
           WHERE user_id=? AND boundary=?""",
        { row, _ -> ProgressEvidence.Cut(user, row.getString(3), boundary, row.getLong(1), row.getLong(2)) },
        user, boundary.sqlTime(),
    ).singleOrNull()

    private fun Instant.sqlTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
