package com.cookie.identity.application.ports

import com.cookie.identity.application.IssuedTokens
import com.cookie.identity.application.PublicJwk
import java.util.UUID

fun interface RegisterWithEmailUseCase {
    fun execute(
        registrationAttemptId: UUID,
        rawEmail: String,
        password: String,
        registrationProof: String,
        locale: String?,
        ip: String,
    )
}

fun interface ResendEmailVerificationUseCase {
    fun execute(registrationAttemptId: UUID, rawEmail: String, registrationProof: String, ip: String)
}

fun interface ConfirmEmailUseCase {
    fun execute(rawToken: String, registrationProof: String, ip: String)
}

fun interface LoginWithEmailUseCase {
    fun execute(rawEmail: String, password: String, rawDeviceId: String?, ip: String): IssuedTokens
}

fun interface RefreshSessionUseCase {
    fun execute(rawToken: String, idempotencyKey: UUID, ip: String): IssuedTokens
}

fun interface LogoutUseCase {
    fun execute(rawToken: String, ip: String)
}

fun interface GetIdentityJwksUseCase {
    fun execute(): List<PublicJwk>
}
