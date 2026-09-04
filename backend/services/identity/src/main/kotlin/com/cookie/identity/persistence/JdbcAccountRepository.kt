package com.cookie.identity.persistence

import com.cookie.identity.application.ports.AccountRepository
import com.cookie.identity.domain.Account
import com.cookie.identity.domain.CanonicalEmail
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class JdbcAccountRepository(
    private val jdbc: JdbcTemplate,
) : AccountRepository {
    override fun lockRegistration(email: CanonicalEmail) {
        jdbc.acquireTransactionAdvisoryLock("identity:registration:${email.value}")
    }

    override fun findByEmail(email: CanonicalEmail): Account? = jdbc.query(
        "$ACCOUNT_SELECT WHERE ec.email = ?",
        ::mapAccount,
        email.value,
    ).singleOrNull()

    /**
     * Resolve the immutable aggregate identifier without a row lock, then acquire
     * locks in the global mutation order: account root, credential.
     */
    override fun findByEmailForUpdate(email: CanonicalEmail): Account? {
        requireActiveTransaction("Lock account by email")
        val accountId = jdbc.query(
            "SELECT account_id FROM email_credentials WHERE email = ?",
            { result, _ -> result.getObject("account_id", UUID::class.java) },
            email.value,
        ).singleOrNull() ?: return null
        return findByIdForUpdate(accountId)
    }

    override fun findByIdForUpdate(accountId: UUID): Account? {
        requireActiveTransaction("Lock account by id")
        val rootExists = jdbc.query(
            "SELECT id FROM accounts WHERE id = ? FOR UPDATE",
            { result, _ -> result.getObject("id", UUID::class.java) },
            accountId,
        ).singleOrNull() != null
        if (!rootExists) return null

        return jdbc.query(
            "$ACCOUNT_SELECT WHERE a.id = ? FOR UPDATE OF ec",
            ::mapAccount,
            accountId,
        ).singleOrNull()
    }

    override fun add(account: Account) {
        requireActiveTransaction("Add account")
        requireSingleRow(
            "Insert account",
            jdbc.update(
                """
                INSERT INTO accounts(id, created_at) VALUES (?, ?)
                """.trimIndent(),
                account.id,
                account.createdAt.asJdbcTimestamp(),
            ),
        )
        requireSingleRow(
            "Insert email credential",
            jdbc.update(
                """
                INSERT INTO email_credentials(
                    account_id, email, password_hash, failed_login_count,
                    locked_until, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                account.id,
                account.email.value,
                account.passwordHash,
                account.failedLoginCount,
                account.lockedUntil?.asJdbcTimestamp(),
                account.createdAt.asJdbcTimestamp(),
                account.createdAt.asJdbcTimestamp(),
            ),
        )
    }

    override fun save(account: Account) {
        requireActiveTransaction("Save account")
        requireSingleRow(
            "Update email credential",
            jdbc.update(
                """
                UPDATE email_credentials
                SET failed_login_count = ?, locked_until = ?,
                    updated_at = GREATEST(updated_at, clock_timestamp())
                WHERE account_id = ?
                """.trimIndent(),
                account.failedLoginCount,
                account.lockedUntil?.asJdbcTimestamp(),
                account.id,
            ),
        )
    }

    private fun mapAccount(result: ResultSet, @Suppress("UNUSED_PARAMETER") rowNumber: Int): Account =
        Account.reconstitute(
            id = result.getObject("id", UUID::class.java),
            email = CanonicalEmail.reconstitute(result.getString("email")),
            passwordHash = result.getString("password_hash"),
            createdAt = result.getTimestamp("created_at").toInstant(),
            failedLoginCount = result.getInt("failed_login_count"),
            lockedUntil = result.getTimestamp("locked_until")?.toInstant(),
        )

    private companion object {
        val ACCOUNT_SELECT = """
            SELECT a.id, a.created_at, ec.email, ec.password_hash,
                   ec.failed_login_count, ec.locked_until
            FROM accounts a
            JOIN email_credentials ec ON ec.account_id = a.id
        """.trimIndent()
    }
}
