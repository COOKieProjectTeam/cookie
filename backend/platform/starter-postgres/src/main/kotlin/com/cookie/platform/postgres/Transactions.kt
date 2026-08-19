package com.cookie.platform.postgres

import org.springframework.transaction.support.TransactionTemplate

class Transactions(private val template: TransactionTemplate) {
    fun <T : Any> required(block: () -> T): T =
        requireNotNull(template.execute { block() }) { "Transaction returned null" }

    fun requiredUnit(block: () -> Unit) {
        template.executeWithoutResult { block() }
    }

    fun <T : Any> requiredNullable(block: () -> T?): T? = template.execute { block() }
}
