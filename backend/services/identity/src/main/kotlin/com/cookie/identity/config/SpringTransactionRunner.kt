package com.cookie.identity.config

import com.cookie.identity.application.ports.TransactionRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class SpringTransactionRunner(
    private val template: TransactionTemplate,
) : TransactionRunner {
    override fun <T : Any> required(block: () -> T): T =
        requireNotNull(template.execute { block() }) { "Transaction returned null" }

    override fun requiredUnit(block: () -> Unit) {
        template.executeWithoutResult { block() }
    }

    override fun <T : Any> requiredNullable(block: () -> T?): T? = template.execute { block() }
}
