package com.cookie.identity.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal fun JdbcTemplate.acquireTransactionAdvisoryLock(key: String) {
    requireActiveTransaction("Transaction-scoped advisory lock")
    query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        { _: ResultSet -> },
        key,
    )
}

internal fun requireActiveTransaction(operation: String) {
    check(TransactionSynchronizationManager.isActualTransactionActive()) {
        "$operation requires an active database transaction"
    }
}

internal fun Instant.asJdbcTimestamp(): OffsetDateTime = atOffset(ZoneOffset.UTC)

internal fun requireSingleRow(operation: String, affectedRows: Int) {
    check(affectedRows == 1) { "$operation affected $affectedRows rows instead of one" }
}
