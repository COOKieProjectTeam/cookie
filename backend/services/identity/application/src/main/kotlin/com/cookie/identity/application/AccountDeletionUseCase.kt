package com.cookie.identity.application

import com.cookie.identity.application.ports.AccessTokenVerifier
import com.cookie.identity.application.ports.AccountDeletionRequestRepository
import com.cookie.identity.application.ports.AccountRepository
import com.cookie.identity.application.ports.CurrentTimeProvider
import com.cookie.identity.application.ports.IdentityEventRecorder
import com.cookie.identity.application.ports.IdGenerator
import com.cookie.identity.application.ports.PasswordHashing
import com.cookie.identity.application.ports.RefreshFamilyRepository
import com.cookie.identity.application.ports.RequestAccountDeletionUseCase
import com.cookie.identity.application.ports.TransactionRunner
import com.cookie.identity.domain.Account
import com.cookie.identity.domain.AccountDeletionRequest
import com.cookie.identity.domain.InvalidInputException
import com.cookie.identity.domain.PasswordAuthenticationResult
import com.cookie.identity.domain.PasswordPolicy
import java.util.UUID

class RequestAccountDeletionHandler(
    private val accounts: AccountRepository,
    private val deletionRequests: AccountDeletionRequestRepository,
    private val families: RefreshFamilyRepository,
    private val transactions: TransactionRunner,
    private val accessTokens: AccessTokenVerifier,
    private val passwordPolicy: PasswordPolicy,
    private val passwordHashing: PasswordHashing,
    private val rateLimiter: IdentityRateLimiter,
    private val ids: IdGenerator,
    private val events: IdentityEventRecorder,
    private val currentTime: CurrentTimeProvider,
) : RequestAccountDeletionUseCase {
    override fun execute(command: AccountDeletionCommand): AccountDeletionResult {
        rateLimiter.accountDeletionIp(command.ip)
        requireRandomUuidV4(command.idempotencyKey)

        val authenticated = accessTokens.verify(command.accessToken, currentTime.now())
        rateLimiter.accountDeletionAccount(authenticated.accountId.toString())

        deletionRequests.findByAccountId(authenticated.accountId)?.let { return it.toResult() }

        val password = passwordPolicy.prepareForAuthentication(command.currentPassword)
        var observed = accounts.findById(authenticated.accountId)
        repeat(MAX_SNAPSHOT_ATTEMPTS) {
            val passwordMatches = passwordHashing.matches(
                password.value,
                observed?.passwordHash ?: passwordHashing.dummyHash,
            )
            when (val outcome = transactions.required {
                val current = accounts.findByIdForUpdate(authenticated.accountId)
                deletionRequests.findByAccountId(authenticated.accountId)?.let {
                    return@required DeletionOutcome.Success(it)
                }
                if (!sameCredential(observed, current)) return@required DeletionOutcome.Retry
                if (current == null) return@required DeletionOutcome.Invalid

                val now = currentTime.now()
                when (current.authenticatePassword(passwordMatches, now)) {
                    PasswordAuthenticationResult.AUTHENTICATED -> {
                        val started = AccountDeletionRequest.start(
                            id = ids.next(),
                            accountId = current.id,
                            idempotencyKey = command.idempotencyKey,
                            now = now,
                        )
                        current.requestDeletion(now)
                        accounts.save(current)
                        deletionRequests.add(started.request)
                        families.revokeAllForAccount(current.id, now)
                        events.accountDeletionRequested(started.event)
                        DeletionOutcome.Success(started.request)
                    }
                    PasswordAuthenticationResult.REJECTED_WITH_RECORDED_FAILURE -> {
                        accounts.save(current)
                        DeletionOutcome.Invalid
                    }
                    PasswordAuthenticationResult.REJECTED -> DeletionOutcome.Invalid
                }
            }) {
                is DeletionOutcome.Success -> return outcome.request.toResult()
                DeletionOutcome.Invalid -> throw InvalidCredentialsException()
                DeletionOutcome.Retry -> observed = accounts.findById(authenticated.accountId)
            }
        }
        throw IdentityUnavailableException("Credential changed during account deletion authentication")
    }

    private fun sameCredential(observed: Account?, current: Account?): Boolean =
        observed?.id == current?.id && observed?.passwordHash == current?.passwordHash

    private fun AccountDeletionRequest.toResult(): AccountDeletionResult = AccountDeletionResult(
        deletionRequestId = id,
        status = status,
        requestedAt = requestedAt,
    )

    private fun requireRandomUuidV4(value: UUID) {
        if (value.version() != UUID_V4 || value.variant() != RFC_4122_VARIANT) {
            throw InvalidInputException("Invalid account deletion idempotency key")
        }
    }

    private sealed interface DeletionOutcome {
        data class Success(val request: AccountDeletionRequest) : DeletionOutcome
        data object Invalid : DeletionOutcome
        data object Retry : DeletionOutcome
    }

    private companion object {
        const val MAX_SNAPSHOT_ATTEMPTS = 2
        const val UUID_V4 = 4
        const val RFC_4122_VARIANT = 2
    }
}
