package com.cookie.identity.config

import com.cookie.identity.application.ports.TransactionRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Component
class SpringTransactionRunner(
    transactionManager: PlatformTransactionManager,
    private val jdbc: JdbcTemplate,
    private val properties: IdentityDatabaseProperties,
) : TransactionRunner {
    private val template = TransactionTemplate(transactionManager).apply {
        timeout = properties.transactionTimeoutSeconds
    }

    override fun <T : Any> required(block: () -> T): T =
        requireNotNull(template.execute { configureDatabaseTimeouts(); block() }) { "Transaction returned null" }

    override fun requiredUnit(block: () -> Unit) {
        template.executeWithoutResult { configureDatabaseTimeouts(); block() }
    }

    private fun configureDatabaseTimeouts() {
        jdbc.queryForObject(
            "SELECT set_config('lock_timeout', ?, true)",
            String::class.java,
            "${properties.lockTimeoutMilliseconds}ms",
        )
        jdbc.queryForObject(
            "SELECT set_config('statement_timeout', ?, true)",
            String::class.java,
            "${properties.statementTimeoutMilliseconds}ms",
        )
    }
}
