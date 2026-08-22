package com.cookie.identity.application.ports

import com.cookie.identity.application.IssuedTokens
import com.cookie.identity.application.PublicJwk

fun interface RegisterWithEmailUseCase {
    fun execute(rawEmail: String, password: String, locale: String?, ip: String)
}

fun interface ResendEmailVerificationUseCase {
    fun execute(rawEmail: String, ip: String)
}

fun interface ConfirmEmailUseCase {
    fun execute(rawToken: String, deviceId: String?, ip: String): IssuedTokens
}

fun interface LoginWithEmailUseCase {
    fun execute(rawEmail: String, password: String, deviceId: String?, ip: String): IssuedTokens
}

fun interface RefreshSessionUseCase {
    fun execute(rawToken: String, ip: String): IssuedTokens
}

fun interface LogoutUseCase {
    fun execute(rawToken: String, ip: String)
}

fun interface GetIdentityJwksUseCase {
    fun execute(): List<PublicJwk>
}
