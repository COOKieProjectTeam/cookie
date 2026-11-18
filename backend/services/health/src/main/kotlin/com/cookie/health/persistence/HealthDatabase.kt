package com.cookie.health.persistence

import com.cookie.health.HealthFailure
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Component
class HealthDatabase(val jdbc: JdbcTemplate, val transactions: TransactionTemplate) {
    fun lock(account: UUID, shared: Boolean = false) {
        val lock = if (shared) "pg_advisory_xact_lock_shared" else "pg_advisory_xact_lock"
        jdbc.query("SELECT $lock(hashtextextended(?,0))", { _ -> }, account.toString())
    }

    fun deleted(account: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM account_tombstones WHERE user_id=?)", Boolean::class.java, account,
    ) == true

    fun <T : Any> forAccount(account: UUID, write: Boolean = false, action: () -> T): T =
        requireNotNull(transactions.execute {
            lock(account, shared = !write)
            if (deleted(account)) throw HealthFailure("NOT_FOUND", 404)
            action()
        })

    fun ready(): Boolean =
        jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM account_tombstones,assessment_executions,weight_measurements WHERE false)", Boolean::class.java) == false
}
