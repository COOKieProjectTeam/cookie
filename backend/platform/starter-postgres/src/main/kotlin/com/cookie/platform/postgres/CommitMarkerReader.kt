package com.cookie.platform.postgres

import org.postgresql.PGConnection
import org.postgresql.replication.LogSequenceNumber
import org.postgresql.replication.PGReplicationStream
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit

data class MarkerCommit(val endLsn: String, val at: Instant, val markers: List<UUID>)

/** Entry point for an owner's standalone capture process, with independent database logins. */
fun runProgressCommitWorker(
    owner: String,
    createJournal: (JdbcTemplate, TransactionTemplate) -> ProgressSourceJournal,
) {
    val prefix = "COOKIE_${owner.uppercase()}_"
    fun required(suffix: String): String {
        val name = prefix + suffix
        return requireNotNull(System.getenv(name)?.takeUnless(String::isBlank)) { "$name is required" }
    }
    val url = required("DATABASE_URL")
    val source = DriverManagerDataSource(url, required("CAPTURE_USER"), required("CAPTURE_PASSWORD"))
    val tx = TransactionTemplate(DataSourceTransactionManager(source)).apply { timeout = 5 }
    val journal = createJournal(JdbcTemplate(source), tx)
    val log = System.getLogger("cookie.$owner.commit-capture")
    CommitMarkerReader(
        url, required("REPLICATION_USER"), required("REPLICATION_PASSWORD"),
        "${owner}_progress", "progress_commit_markers",
    ).use { reader ->
        Runtime.getRuntime().addShutdownHook(Thread { reader.close() })
        var warned = false
        while (!Thread.currentThread().isInterrupted) {
            try {
                reader.poll(journal::record)
                if (warned) log.log(System.Logger.Level.INFO, "Progress commit capture resumed")
                warned = false
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                if (!warned) log.log(System.Logger.Level.WARNING, "Progress commit capture failed; PostgreSQL position was retained")
                warned = true
                Thread.sleep(1_000)
            }
        }
    }
}

/**
 * pgoutput v1 for an INSERT-only publication containing one UUID column.
 * The reader never advances the slot before the caller persists the commit.
 */
class CommitMarkerReader(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String,
    private val slot: String,
    private val publication: String,
) : AutoCloseable {
    private var connection: Connection? = null
    private var stream: PGReplicationStream? = null
    private val decoder = CommitMarkerDecoder()

    @Synchronized
    fun poll(persist: (MarkerCommit) -> Unit) {
        try {
            val current = stream ?: open()
            repeat(256) {
                val message = current.readPending() ?: return
                val committed = decoder.decode(message) ?: return@repeat
                persist(committed)
                val lsn = LogSequenceNumber.valueOf(committed.endLsn)
                current.setAppliedLSN(lsn)
                current.setFlushedLSN(lsn)
                current.forceUpdateStatus()
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun open(): PGReplicationStream {
        val properties = Properties().apply {
            setProperty("user", username)
            setProperty("password", password)
            setProperty("replication", "database")
            setProperty("assumeMinServerVersion", "18")
            setProperty("preferQueryMode", "simple")
            setProperty("connectTimeout", "5")
            setProperty("socketTimeout", "5")
        }
        val sql = DriverManager.getConnection(jdbcUrl, properties).also { connection = it }
        return sql.unwrap(PGConnection::class.java).replicationAPI.replicationStream().logical()
            .withSlotName(slot)
            .withSlotOption("proto_version", 1)
            .withSlotOption("publication_names", publication)
            .withStatusInterval(1, TimeUnit.SECONDS)
            .start()
            .also { stream = it }
    }

    @Synchronized
    override fun close() {
        runCatching { stream?.close() }
        runCatching { connection?.close() }
        stream = null
        connection = null
        decoder.reset()
    }
}

internal class CommitMarkerDecoder {
    private var relationId: Int? = null
    private var markers: MutableList<UUID>? = null

    fun decode(message: ByteBuffer): MarkerCommit? {
        return when (val type = message.get().toInt().toChar()) {
            'B' -> {
                check(markers == null) { "Nested pgoutput transaction" }
                markers = mutableListOf()
                null
            }
            'R' -> {
                val id = message.int
                val known = relationId
                check(known == null || id == known) { "Unexpected pgoutput relation" }
                check(message.cString() == "public" && message.cString() == "progress_commit_markers")
                message.get()
                check(message.short.toInt() == 1) { "Expected the marker column only" }
                message.get()
                check(message.cString() == "marker_id" && message.int == 2950)
                relationId = id
                null
            }
            'I' -> {
                check(message.int == relationId && message.get().toInt().toChar() == 'N')
                check(message.short.toInt() == 1 && message.get().toInt().toChar() == 't')
                val size = message.int
                check(size == 36 && message.remaining() == size)
                val uuid = ByteArray(size).also { message.get(it) }.toString(UTF_8)
                checkNotNull(markers).add(UUID.fromString(uuid))
                null
            }
            'C' -> {
                check(message.get().toInt() == 0)
                message.long // Commit record location.
                val end = LogSequenceNumber.valueOf(message.long).asString()
                val at = POSTGRES_EPOCH.plusNanos(Math.multiplyExact(message.long, 1_000L))
                val complete = checkNotNull(markers)
                markers = null
                MarkerCommit(end, at, complete.toList())
            }
            else -> error("Unexpected pgoutput message type: $type")
        }
    }

    fun reset() {
        relationId = null
        markers = null
    }

    private fun ByteBuffer.cString(): String = buildString {
        while (true) {
            val next = get()
            if (next == 0.toByte()) break
            append(next.toInt().toChar())
        }
    }

    companion object {
        private val POSTGRES_EPOCH = Instant.parse("2000-01-01T00:00:00Z")
    }
}
